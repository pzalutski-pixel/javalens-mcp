package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.ElementKindResolver;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rename a symbol across the project.
 * Returns text edits for all occurrences that need to be changed.
 */
public class RenameSymbolTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RenameSymbolTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "var", "yield",
        "record", "sealed", "permits", "non-sealed"
    );

    public RenameSymbolTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "rename_symbol";
    }

    @Override
    public String getDescription() {
        return """
            Rename a symbol (variable, method, field, class, etc.) across the project.

            Returns text edits for all occurrences that need to be changed.
            The caller should apply these edits to perform the rename.

            USAGE: Position on symbol, provide new name
            OUTPUT: List of text edits to apply

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the symbol")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .required("newName", "string", "New name for the symbol")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String newName = getStringParam(arguments, "newName");

        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
        }

        if (newName == null || newName.isBlank()) {
            return ToolResponse.invalidParameter("newName", "Required");
        }

        if (!isValidJavaIdentifier(newName)) {
            return ToolResponse.invalidParameter("newName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);

            // Use getElementAtPosition - the same reliable approach other tools use
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol at position");
            }

            String oldName = element.getElementName();
            String symbolKind = getElementKind(element);

            if (oldName.equals(newName)) {
                return ToolResponse.invalidParameter("newName", "Same as current name");
            }

            // Dispatch: IMember (IType, IField, IMethod) uses SearchEngine, which is
            // index-driven and resilient to per-call binding-resolution failures that
            // emerged at scale. ILocalVariable and ITypeParameter are not indexed by
            // SearchEngine in a useful way; they stay on the AST-binding path whose
            // file scope is the declaring CU only (locals/type-params don't cross files).
            if (element instanceof IMember member) {
                return renameViaSearch(member, oldName, newName, symbolKind, service);
            }
            return renameViaAstBinding(element, oldName, newName, symbolKind, service);

        } catch (Exception e) {
            log.error("Error renaming symbol: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * SearchEngine-based rename for {@link IMember} targets. Closes issue #13's failure
     * mode where the AST-binding path lost references at scale: visiting every project
     * file with full binding resolution intermittently produced null bindings under JDT
     * index pressure on multi-thousand-file projects. SearchEngine is index-driven —
     * it doesn't re-resolve bindings per file — so it stays correct at scale.
     */
    private ToolResponse renameViaSearch(IMember target, String oldName, String newName,
                                          String symbolKind, IJdtService service) throws Exception {
        // Override-chain: for methods, every overrider in subtypes and every overridden
        // parent in supertypes must be renamed in lockstep. For non-methods this list
        // is just the target.
        List<IMember> compatibleMembers = computeCompatibleMembers(target);

        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        Set<String> emittedKeys = new LinkedHashSet<>();
        int totalEdits = 0;

        for (IMember member : compatibleMembers) {
            // Declaration site (SearchEngine REFERENCES omits the declaration itself).
            ISourceRange nameRange = member.getNameRange();
            if (nameRange != null && nameRange.getOffset() >= 0) {
                ICompilationUnit memberCu =
                    (ICompilationUnit) member.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (memberCu != null) {
                    String filePath = formatPathFor(memberCu, service);
                    String dedupKey = filePath + "@" + nameRange.getOffset();
                    if (filePath != null && emittedKeys.add(dedupKey)) {
                        Map<String, Object> declEdit = buildEdit(
                            memberCu, nameRange.getOffset(), nameRange.getLength(),
                            oldName, newName, service);
                        if (declEdit != null) {
                            editsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(declEdit);
                            totalEdits++;
                        }
                    }
                }
            }

            // Reference sites via SearchEngine. The cap is high so the rename surfaces
            // every site; per-tool maxResults capping doesn't apply here because rename
            // edits are not paginated — applying a partial rename would break compilation.
            SearchResult result = service.getSearchService()
                .findAllReferences(member, 1_000_000);
            for (SearchMatch match : result.matches()) {
                ICompilationUnit matchCu = MatchResolver.resolveCu(match);
                if (matchCu == null) continue;
                String filePath = formatPathFor(matchCu, service);
                if (filePath == null) continue;
                String dedupKey = filePath + "@" + match.getOffset();
                // A method-binding ref and an overrider's ref may coincide at the same
                // offset in JDT's index when the call resolves through dynamic dispatch.
                // Dedup so each occurrence emits exactly one edit.
                if (!emittedKeys.add(dedupKey)) continue;
                Map<String, Object> refEdit = buildEdit(
                    matchCu, match.getOffset(), oldName.length(),
                    oldName, newName, service);
                if (refEdit != null) {
                    editsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(refEdit);
                    totalEdits++;
                }
            }
        }

        // Sort per-file edits reverse-position so a caller applying them in order does
        // not invalidate earlier offsets.
        for (List<Map<String, Object>> fileEdits : editsByFile.values()) {
            fileEdits.sort((a, b) -> {
                int lineA = (int) a.get("line");
                int lineB = (int) b.get("line");
                if (lineA != lineB) return Integer.compare(lineB, lineA);
                int colA = (int) a.get("column");
                int colB = (int) b.get("column");
                return Integer.compare(colB, colA);
            });
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", oldName);
        data.put("newName", newName);
        data.put("symbolKind", symbolKind);
        data.put("totalEdits", totalEdits);
        data.put("filesAffected", editsByFile.size());
        data.put("editsByFile", editsByFile);

        if (target instanceof IType) {
            data.put("note", "Renaming a type may require renaming the file as well");
        }

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(totalEdits)
            .returnedCount(totalEdits)
            .suggestedNextTools(List.of(
                "Apply the text edits to complete the rename",
                "get_diagnostics to verify no errors after rename"
            ))
            .build());
    }

    /**
     * AST-binding rename for {@link ILocalVariable} and {@link ITypeParameter} targets.
     * These elements have no SearchEngine indexing path; locals don't cross files and
     * type parameters are constrained to the declaring class/method body, so parsing
     * the single declaring CU is sufficient and avoids the scale-driven binding-
     * resolution failures of the project-wide AST scan.
     */
    private ToolResponse renameViaAstBinding(IJavaElement element, String oldName, String newName,
                                              String symbolKind, IJdtService service) throws Exception {
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) {
            return ToolResponse.symbolNotFound("Cannot find compilation unit for element");
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        String targetKey = findBindingKey(element, ast, oldName);
        if (targetKey == null) {
            targetKey = element.getHandleIdentifier();
            log.debug("Using handle identifier as fallback: {}", targetKey);
        }

        Set<String> compatibleKeys = computeCompatibleKeys(element, targetKey);

        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        int totalEdits = 0;

        List<Map<String, Object>> fileEdits = findRenameEdits(ast, compatibleKeys, oldName, newName);
        if (!fileEdits.isEmpty()) {
            Path sourceFile = cu.getResource().getLocation().toFile().toPath();
            String relativePath = service.getPathUtils().formatPath(sourceFile);
            editsByFile.put(relativePath, fileEdits);
            totalEdits = fileEdits.size();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", oldName);
        data.put("newName", newName);
        data.put("symbolKind", symbolKind);
        data.put("totalEdits", totalEdits);
        data.put("filesAffected", editsByFile.size());
        data.put("editsByFile", editsByFile);

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(totalEdits)
            .returnedCount(totalEdits)
            .suggestedNextTools(List.of(
                "Apply the text edits to complete the rename",
                "get_diagnostics to verify no errors after rename"
            ))
            .build());
    }

    private Map<String, Object> buildEdit(ICompilationUnit cu, int offset, int length,
                                           String oldName, String newName, IJdtService service) {
        try {
            int lineNum = service.getLineNumber(cu, offset);
            int columnNum = service.getColumnNumber(cu, offset);
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("line", lineNum);
            edit.put("column", columnNum);
            edit.put("endColumn", columnNum + oldName.length());
            edit.put("oldText", oldName);
            edit.put("newText", newName);
            edit.put("startOffset", offset);
            edit.put("endOffset", offset + length);
            return edit;
        } catch (Exception e) {
            log.debug("Failed to build edit at offset {}: {}", offset, e.getMessage());
            return null;
        }
    }

    private String formatPathFor(ICompilationUnit cu, IJdtService service) {
        if (cu == null || cu.getResource() == null) return null;
        IPath location = cu.getResource().getLocation();
        if (location == null) return null;
        return service.getPathUtils().formatPath(location.toOSString());
    }

    /**
     * Companion to {@link #computeCompatibleKeys} that returns IMember instances
     * (needed for the SearchEngine path) rather than binding-key strings.
     */
    private List<IMember> computeCompatibleMembers(IMember target) {
        List<IMember> members = new ArrayList<>();
        members.add(target);

        if (!(target instanceof IMethod targetMethod)) return members;

        try {
            IType declaringType = targetMethod.getDeclaringType();
            if (declaringType == null) return members;

            ITypeHierarchy hierarchy = declaringType.newTypeHierarchy(new NullProgressMonitor());
            for (IType subtype : hierarchy.getAllSubtypes(declaringType)) {
                addMatchingMethodMember(subtype, targetMethod, members);
            }
            for (IType supertype : hierarchy.getAllSupertypes(declaringType)) {
                addMatchingMethodMember(supertype, targetMethod, members);
            }
        } catch (Exception e) {
            log.debug("Failed to compute override-related members: {}", e.getMessage());
        }
        return members;
    }

    private void addMatchingMethodMember(IType type, IMethod target, List<IMember> members) {
        try {
            String targetName = target.getElementName();
            String[] targetParamTypes = target.getParameterTypes();
            for (IMethod m : type.getMethods()) {
                if (!m.getElementName().equals(targetName)) continue;
                String[] paramTypes = m.getParameterTypes();
                if (paramTypes.length != targetParamTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!paramTypes[i].equals(targetParamTypes[i])) { match = false; break; }
                }
                if (match) members.add(m);
            }
        } catch (Exception e) {
            log.debug("Error matching method in {}: {}", type.getFullyQualifiedName(), e.getMessage());
        }
    }

    /**
     * For a method, gather the binding keys of every related method whose name must
     * change in lockstep: the method itself, every overrider in the subtype hierarchy,
     * and every overridden method in the supertype hierarchy. Renaming an interface
     * method without propagating to implementors would leave the implementor signature
     * mismatched against the interface — the project would stop compiling. For non-
     * methods (fields, types, locals) the set is just the single target key.
     */
    private Set<String> computeCompatibleKeys(IJavaElement element, String targetKey) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(targetKey);

        if (!(element instanceof IMethod targetMethod)) return keys;

        try {
            IType declaringType = targetMethod.getDeclaringType();
            if (declaringType == null) return keys;

            ITypeHierarchy hierarchy = declaringType.newTypeHierarchy(new NullProgressMonitor());
            for (IType subtype : hierarchy.getAllSubtypes(declaringType)) {
                addBindingKeyForMatchingMethod(subtype, targetMethod, keys);
            }
            for (IType supertype : hierarchy.getAllSupertypes(declaringType)) {
                addBindingKeyForMatchingMethod(supertype, targetMethod, keys);
            }
        } catch (Exception e) {
            log.debug("Failed to compute override-related binding keys: {}", e.getMessage());
        }
        return keys;
    }

    private void addBindingKeyForMatchingMethod(IType type, IMethod target, Set<String> keys) {
        try {
            String targetName = target.getElementName();
            String[] targetParamTypes = target.getParameterTypes();
            for (IMethod m : type.getMethods()) {
                if (!m.getElementName().equals(targetName)) continue;
                String[] paramTypes = m.getParameterTypes();
                if (paramTypes.length != targetParamTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!paramTypes[i].equals(targetParamTypes[i])) { match = false; break; }
                }
                if (!match) continue;

                ICompilationUnit cu = m.getCompilationUnit();
                if (cu == null) continue;
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                parser.setBindingsRecovery(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                String key = findBindingKey(m, ast, targetName);
                if (key != null) keys.add(key);
            }
        } catch (Exception e) {
            log.debug("Error matching method in {}: {}", type.getFullyQualifiedName(), e.getMessage());
        }
    }

    private List<Map<String, Object>> findRenameEdits(CompilationUnit ast, Set<String> compatibleKeys,
                                                       String oldName, String newName) {
        List<Map<String, Object>> edits = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!oldName.equals(node.getIdentifier())) {
                    return true;
                }

                IBinding nodeBinding = node.resolveBinding();
                if (nodeBinding != null && compatibleKeys.contains(nodeBinding.getKey())) {
                    Map<String, Object> edit = new LinkedHashMap<>();
                    edit.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                    edit.put("column", ast.getColumnNumber(node.getStartPosition()));
                    edit.put("endColumn", ast.getColumnNumber(node.getStartPosition()) + oldName.length());
                    edit.put("oldText", oldName);
                    edit.put("newText", newName);
                    edit.put("startOffset", node.getStartPosition());
                    edit.put("endOffset", node.getStartPosition() + node.getLength());
                    edits.add(edit);
                }
                return true;
            }
        });

        // Sort edits by position (reverse order for safe application)
        edits.sort((a, b) -> {
            int lineA = (int) a.get("line");
            int lineB = (int) b.get("line");
            if (lineA != lineB) return Integer.compare(lineB, lineA);
            int colA = (int) a.get("column");
            int colB = (int) b.get("column");
            return Integer.compare(colB, colA);
        });

        return edits;
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }

    private String getElementKind(IJavaElement element) {
        return ElementKindResolver.kindOf(element);
    }

    private String findBindingKey(IJavaElement element, CompilationUnit ast, String name) {
        try {
            ISourceRange range = null;
            if (element instanceof IMember member) {
                range = member.getNameRange();
                if (range == null) {
                    range = member.getSourceRange();
                }
            } else if (element instanceof ILocalVariable local) {
                range = local.getNameRange();
            } else if (element instanceof ITypeParameter typeParam) {
                // Type parameters have no getNameRange(); the source range
                // covers just the identifier (`T` in `<T>`).
                range = typeParam.getSourceRange();
            }

            if (range == null || range.getOffset() < 0) {
                return null;
            }

            // Find SimpleName at the element's name location
            final int offset = range.getOffset();
            final String[] foundKey = {null};

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.getStartPosition() == offset && name.equals(node.getIdentifier())) {
                        IBinding binding = node.resolveBinding();
                        if (binding != null) {
                            foundKey[0] = binding.getKey();
                        }
                        return false;  // Stop visiting
                    }
                    return true;
                }
            });

            return foundKey[0];
        } catch (JavaModelException e) {
            log.debug("Error finding binding key: {}", e.getMessage());
            return null;
        }
    }
}
