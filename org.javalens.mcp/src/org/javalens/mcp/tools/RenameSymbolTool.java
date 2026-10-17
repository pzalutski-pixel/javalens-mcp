package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameEnumConstProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameLocalVariableProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameModuleProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeParameterProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.javalens.core.ElementKindResolver;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
            OUTPUT: List of text edits to apply, and the files to rename

            Each edit replaces exactly the symbol's simple name, also where a
            reference is qualified (an import, a static import, a qualified name in
            code or in a Javadoc link). An annotation element written as the single
            value of its annotation, @Tag("x"), is named before that value:
            @Tag(label = "x").

            A type is renamed with its explicit constructors. A top-level type whose
            file is named after it renames that file: renamedFiles lists it, as
            {filePath, newFilePath}; apply the edits (editsByFile is keyed by the
            current paths), then rename the file. renamedFiles is present only when
            a file is renamed. A top-level type renamed to the name of a file that
            already exists in its folder is refused: INVALID_PARAMETER.

            A method is renamed together with every method it overrides, every
            method that overrides it and every method a subtype inherits together
            with it, in and after lambda bodies included, and every call of them.
            A method of a class file, or a method renamed together with one, is
            refused, naming that method: it cannot be renamed.

            A rename JDT's rename refactoring finds a conflict for is refused:
            INVALID_PARAMETER for newName, naming every conflict in JDT's words, by
            the place each names (a name another declaration in scope already has,
            a reference the renamed element would take over or lose, a unit with
            syntax errors, a native method, a static import of the new name). The
            conflicts of a type, a field, a local variable, a type parameter and a
            private, static or constructor method are those JDT's rename processors
            find; those of any other method are the conditions of JDT's method
            rename (RenameMethodProcessor, RenameVirtualMethodProcessor) checked on
            the Java model's own hierarchy, local and anonymous classes in lambda
            bodies included. JDT's warnings on a rename that goes ahead (such as a
            discouraged name, a constructor's name, or a method of the hierarchy
            with the new name and other parameter types) are listed in warnings,
            by place; warnings is present only when there is one.

            A place JDT's search reports but cannot confirm as a reference, where a
            type or name does not resolve, is never edited: potentialMatches lists
            every one, to review by hand. A position on a name the compiler cannot
            resolve has no symbol to rename: SYMBOL_NOT_FOUND.

            IMPORTANT: Uses ZERO-BASED coordinates.

            """ + POTENTIAL_MATCHES + """
            A module is renamed in its declaration and its references. A package
            (renamed with its folder, which no text edit does), a package
            declaration, an import declaration or the import list is refused:
            INVALID_PARAMETER for position, naming it. So is a type, field or
            method of a library (binary), which has no source to edit.

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

            // An initializer and an anonymous class have no name, so there is nothing to rename.
            if (element.getElementType() == IJavaElement.INITIALIZER) {
                return ToolResponse.invalidParameter("position",
                    "Symbol at position is an initializer, which has no name to rename");
            }
            if (element instanceof IType type && type.isAnonymous()) {
                return ToolResponse.invalidParameter("position",
                    "Symbol at position is the anonymous class " + ElementNames.qualifiedName(type)
                        + ", which has no name to rename");
            }
            // A package is renamed with its folder, which no edit of a file's text can do; a package
            // or import declaration, or the import list, is no symbol of its own
            String unrenamed = element instanceof IPackageFragment ? "package"
                : element instanceof IPackageDeclaration ? "package declaration"
                : element instanceof IImportDeclaration ? "import declaration"
                : element instanceof IImportContainer ? "import list" : null;
            if (unrenamed != null) {
                String name = element instanceof IImportContainer ? "" : " " + element.getElementName();
                return ToolResponse.invalidParameter("position", "Symbol at position is the " + unrenamed + name
                    + ", which rename_symbol does not rename");
            }
            // A type or field of a class file has no source to edit, as a method of one has not
            if (element instanceof IType type && type.isBinary()) {
                return ToolResponse.invalidParameter("position",
                    "Type at position, " + ElementNames.qualifiedName(type) + ", is binary and cannot be renamed");
            }
            if (element instanceof IField field && field.isBinary()) {
                return ToolResponse.invalidParameter("position", "Field at position, " + field.getElementName() + " in "
                    + ElementNames.qualifiedName(field.getDeclaringType()) + ", is binary and cannot be renamed");
            }

            String oldName = element.getElementName();
            String symbolKind = getElementKind(element);

            if (oldName.equals(newName)) {
                return ToolResponse.invalidParameter("newName", "Same as current name");
            }

            // A method is renamed with every method it must keep its name with: those it
            // overrides, those overriding it and those a subtype inherits together with it.
            // A method of a class file cannot be renamed, so neither can a method related to one.
            if (element instanceof IMethod method) {
                String position = "Method at position, " + oldName + " in "
                    + ElementNames.qualifiedName(method.getDeclaringType());
                if (method.isBinary()) {
                    return ToolResponse.invalidParameter("position", position + ", is binary and cannot be renamed");
                }
                List<IMethod> ripple = service.getSearchService().rippleOf(method);
                String binary = RelatedMethods.binaryRefusal(method, ripple);
                if (binary != null) {
                    return ToolResponse.invalidParameter("position", position + ", cannot be renamed: " + binary);
                }
                Collected collected = collect(ripple, oldName, newName, service);
                RefactoringInvoker.Findings findings = MethodChecks.isVirtual(method)
                    ? RefactoringInvoker.findings(VirtualMethodRenameConditions.check(
                        method, ripple, newName, collected.occurrences(), collected.potential(), service), service)
                    : jdtFindings(method, newName, service);
                if (!findings.conflicts().isEmpty()) {
                    return ToolResponse.invalidParameter("newName", String.join("; ", findings.conflicts()));
                }
                return respond(method, collected, oldName, newName, symbolKind, findings.warnings(), service);
            }

            // A top-level type cannot take the name of a file its folder already holds
            // (JDT Checks.checkCompilationUnitNewName :236-243, checked for every top-level
            // type by RenameTypeProcessor.doCheckFinalConditions :503-508).
            if (element instanceof IType type && type.getDeclaringType() == null) {
                ICompilationUnit unit = type.getCompilationUnit();
                String renamedUnit = renamedUnitName(unit, newName);
                if (!renamedUnit.equals(unit.getElementName())
                        && unit.getResource().getParent().findMember(renamedUnit) != null) {
                    return ToolResponse.invalidParameter("newName",
                        "Compilation unit '" + renamedUnit + "' already exists");
                }
            }

            RefactoringInvoker.Findings findings = jdtFindings(element, newName, service);
            if (!findings.conflicts().isEmpty()) {
                return ToolResponse.invalidParameter("newName", String.join("; ", findings.conflicts()));
            }

            // Dispatch: IMember (IType, IField) uses SearchEngine, which is
            // index-driven and resilient to per-call binding-resolution failures that
            // emerged at scale. ILocalVariable and ITypeParameter are not indexed by
            // SearchEngine in a useful way; they stay on the AST-binding path whose
            // file scope is the declaring CU only (locals/type-params don't cross files).
            if (element instanceof IMember member) {
                return respond(member, collect(List.of(member), oldName, newName, service),
                    oldName, newName, symbolKind, findings.warnings(), service);
            }
            return renameViaAstBinding(element, oldName, newName, symbolKind, findings.warnings(), service);

        } catch (Exception e) {
            log.error("Error renaming symbol: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * What JDT's rename refactoring of {@code element} to {@code newName} finds, updating
     * references as rename_symbol does and nothing else. The processors are JDT's own:
     * RenameTypeProcessor, RenameFieldProcessor and RenameEnumConstProcessor,
     * RenameNonVirtualMethodProcessor (for a method MethodChecks.isVirtual rejects: its related
     * methods are the method alone, RippleMethodFinder2.getRelatedMethods :175-184),
     * RenameLocalVariableProcessor, RenameTypeParameterProcessor and RenameModuleProcessor.
     */
    private static RefactoringInvoker.Findings jdtFindings(IJavaElement element, String newName,
                                                           IJdtService service) throws CoreException {
        JavaRenameProcessor processor;
        if (element instanceof IType type) {
            RenameTypeProcessor typeProcessor = new RenameTypeProcessor(type);
            typeProcessor.setUpdateReferences(true);
            processor = typeProcessor;
        } else if (element instanceof IField field) {
            RenameFieldProcessor fieldProcessor = field.isEnumConstant()
                ? new RenameEnumConstProcessor(field) : new RenameFieldProcessor(field);
            fieldProcessor.setUpdateReferences(true);
            fieldProcessor.setRenameGetter(false);
            fieldProcessor.setRenameSetter(false);
            processor = fieldProcessor;
        } else if (element instanceof IMethod method && !MethodChecks.isVirtual(method)) {
            RenameNonVirtualMethodProcessor methodProcessor = new RenameNonVirtualMethodProcessor(method);
            methodProcessor.setUpdateReferences(true);
            processor = methodProcessor;
        } else if (element instanceof ILocalVariable local) {
            RenameLocalVariableProcessor localProcessor = new RenameLocalVariableProcessor(local);
            localProcessor.setUpdateReferences(true);
            processor = localProcessor;
        } else if (element instanceof ITypeParameter parameter) {
            RenameTypeParameterProcessor parameterProcessor = new RenameTypeParameterProcessor(parameter);
            parameterProcessor.setUpdateReferences(true);
            processor = parameterProcessor;
        } else if (element instanceof IModuleDescription module) {
            RenameModuleProcessor moduleProcessor = new RenameModuleProcessor(module);
            moduleProcessor.setUpdateReferences(true);
            processor = moduleProcessor;
        } else {
            throw new IllegalStateException("No JDT rename processor for " + element.getElementName());
        }
        JavaRenameProcessor renaming = processor;
        return RefactoringInvoker.findings(new RenameRefactoring(renaming),
            () -> renaming.setNewElementName(newName), service);
    }

    /**
     * A place a rename edits: {@code length} characters at {@code offset} of {@code unit} become
     * {@code newText}, the name of a declaration when {@code declaration}, else of a reference.
     */
    record Occurrence(ICompilationUnit unit, int offset, int length, String newText, boolean declaration) {
    }

    /**
     * The edits of a rename by file, as the response lists them, their count, the places the
     * search could not confirm, and every edit as an {@link Occurrence}.
     */
    record Collected(Map<String, List<Map<String, Object>>> editsByFile, int totalEdits,
                     List<SearchMatch> potential, List<Occurrence> occurrences) {
    }

    /**
     * The edits renaming {@code members} (a type or a field, or a method and every method renamed
     * with it, or a module) from {@code oldName} to {@code newName}, found by SearchEngine, which is
     * index-driven and resolves no bindings per file, so the references it finds do not depend on
     * how many files the project has.
     */
    private Collected collect(List<? extends IMember> members, String oldName, String newName,
                              IJdtService service) throws Exception {
        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        Set<String> emittedKeys = new LinkedHashSet<>();
        int totalEdits = 0;
        List<SearchMatch> potential = new ArrayList<>();
        Set<String> potentialKeys = new HashSet<>();
        List<Occurrence> occurrences = new ArrayList<>();

        for (IMember member : members) {
            // Declaration sites (SearchEngine REFERENCES omits the declarations themselves): the
            // member's name, and a type's explicit constructors, renamed with it as JDT's
            // RenameTypeProcessor.addConstructorRenames does (:1186-1201).
            List<IMember> declared = new ArrayList<>(List.of(member));
            if (member instanceof IType type) {
                for (IMethod method : type.getMethods()) {
                    if (method.isConstructor()) {
                        declared.add(method);
                    }
                }
            }
            for (IMember declaration : declared) {
                ISourceRange nameRange = declaration.getNameRange();
                if (nameRange == null || nameRange.getOffset() < 0) {
                    continue;
                }
                ICompilationUnit memberCu =
                    (ICompilationUnit) declaration.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (memberCu != null) {
                    String filePath = formatPathFor(memberCu, service);
                    String dedupKey = filePath + "@" + nameRange.getOffset();
                    if (filePath != null && emittedKeys.add(dedupKey)) {
                        editsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(buildEdit(
                            memberCu, nameRange.getOffset(), nameRange.getLength(), oldName, newName, service));
                        occurrences.add(new Occurrence(memberCu, nameRange.getOffset(), nameRange.getLength(),
                            newName, true));
                        totalEdits++;
                    }
                }
            }

            // Reference sites via SearchEngine, every one of them: rename edits are not
            // paginated, and a partial rename would break compilation.
            // A method's references are searched in the project's sources only: the edits
            // are made only there, and a search of the JDK resolves each JDK call of a
            // method of the same name, for every method of the ripple.
            SearchResult result = member instanceof IMethod
                ? service.getSearchService().findAllReferencesInSources(member, SearchService.ALL_MATCHES)
                : service.getSearchService().findAllReferences(member, SearchService.ALL_MATCHES);
            // A place the search could not confirm is never edited: it is reported, once.
            for (SearchMatch match : result.potentialMatches()) {
                String at = (match.getResource() == null ? "" : match.getResource().getFullPath().toString())
                    + "@" + match.getOffset();
                if (potentialKeys.add(at)) {
                    potential.add(match);
                }
            }
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
                Map<String, Object> edit = referenceEdit(member, match, matchCu, oldName, newName, service);
                editsByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(edit);
                int start = (int) edit.get("startOffset");
                occurrences.add(new Occurrence(matchCu, start, (int) edit.get("endOffset") - start,
                    (String) edit.get("newText"), false));
                totalEdits++;
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
        return new Collected(editsByFile, totalEdits, potential, occurrences);
    }

    /**
     * The answer renaming {@code target} with the edits {@code collected} holds, listing
     * {@code warnings} when there is one.
     */
    private ToolResponse respond(IMember target, Collected collected, String oldName, String newName,
                                 String symbolKind, List<String> warnings, IJdtService service) {
        Map<String, List<Map<String, Object>>> editsByFile = collected.editsByFile();
        int totalEdits = collected.totalEdits();
        List<SearchMatch> potential = collected.potential();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", oldName);
        data.put("newName", newName);
        data.put("symbolKind", symbolKind);
        data.put("totalEdits", totalEdits);
        data.put("filesAffected", editsByFile.size());
        data.put("editsByFile", editsByFile);
        // A top-level type whose file is named after it renames that file (JDT
        // RenameTypeProcessor.isPrimaryType :255-259, createChangeForRenamedCUStandardResource
        // :1100-1105); a file already named after the new name was refused before.
        if (target instanceof IType type && type.getDeclaringType() == null) {
            ICompilationUnit unit = type.getCompilationUnit();
            if (JavaCore.removeJavaLikeExtension(unit.getElementName()).equals(oldName)) {
                Path file = Path.of(unit.getResource().getLocation().toOSString());
                Map<String, Object> renamed = new LinkedHashMap<>();
                renamed.put("filePath", service.getPathUtils().formatPath(file));
                renamed.put("newFilePath", service.getPathUtils().formatPath(
                    file.resolveSibling(renamedUnitName(unit, newName))));
                data.put("renamedFiles", List.of(renamed));
            }
        }
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        Map<String, Object> potentialMatches = potentialMatches(potential, potential.size(), service);
        if (potentialMatches != null) {
            data.put("potentialMatches", potentialMatches);
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
                                              String symbolKind, List<String> warnings,
                                              IJdtService service) throws Exception {
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) {
            return ToolResponse.symbolNotFound("Cannot find compilation unit for element");
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());

        String targetKey = findBindingKey(element, ast, oldName);
        if (targetKey == null) {
            targetKey = element.getHandleIdentifier();
            log.debug("Using handle identifier as fallback: {}", targetKey);
        }

        Set<String> compatibleKeys = Set.of(targetKey);

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
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
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

    /** The edit replacing the {@code length} characters at {@code offset}, {@code oldText}, with {@code newText}. */
    private Map<String, Object> buildEdit(ICompilationUnit cu, int offset, int length,
                                           String oldText, String newText, IJdtService service) {
        int lineNum = service.getLineNumber(cu, offset);
        int columnNum = service.getColumnNumber(cu, offset);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("line", lineNum);
        edit.put("column", columnNum);
        edit.put("endColumn", columnNum + length);
        edit.put("oldText", oldText);
        edit.put("newText", newText);
        edit.put("startOffset", offset);
        edit.put("endOffset", offset + length);
        return edit;
    }

    /**
     * The edit renaming the reference {@code match} to {@code member}, on the reference's simple
     * name as JDT's rename refactorings find it: the match itself when it is the name, else the
     * last identifier in it that is the name, before the arguments of a method reference. A type
     * reference is reported from its first qualifier ({@code a.b.Foo}, TypeReferenceLocator
     * :289-300), its simple name the last segment (TypeOccurrenceCollector.acceptSearchMatch2
     * :48-89); a method reference over its arguments, and a static import from its first
     * qualifier, its name the last identifier before '(' (MethodOccurenceCollector.acceptSearchMatch
     * :67-102). An annotation element written as the single value of its annotation is named
     * before that value (MethodOccurenceCollector :58-61, RenameMethodProcessor.createReplaceEdit
     * :867-878).
     */
    private Map<String, Object> referenceEdit(IMember member, SearchMatch match, ICompilationUnit cu,
                                              String oldName, String newName, IJdtService service)
            throws JavaModelException {
        int start = match.getOffset();
        IJavaProject project = cu.getJavaProject();
        if (member instanceof IMethod && match.isImplicit()) {
            String assignment = newName
                + (JavaCore.INSERT.equals(project.getOption(
                    DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_BEFORE_ASSIGNMENT_OPERATOR, true)) ? " " : "")
                + "="
                + (JavaCore.INSERT.equals(project.getOption(
                    DefaultCodeFormatterConstants.FORMATTER_INSERT_SPACE_AFTER_ASSIGNMENT_OPERATOR, true)) ? " " : "");
            return buildEdit(cu, start, 0, "", assignment, service);
        }
        String matched = cu.getSource().substring(start, start + match.getLength());
        if (matched.equals(oldName)) {
            return buildEdit(cu, start, oldName.length(), oldName, newName, service);
        }
        int nameStart = lastIdentifier(matched, oldName, member instanceof IMethod);
        if (nameStart < 0) {
            throw new IllegalStateException("The reference '" + matched + "' does not name " + oldName);
        }
        return buildEdit(cu, start + nameStart, oldName.length(), oldName, newName, service);
    }

    /**
     * The start in {@code text} of its last identifier that is {@code name}, read as JDT's scanner
     * reads tokens, comments and whitespace between them; when {@code beforeParenthesis}, only
     * before the first '(' (the arguments of a method reference). -1 when there is none.
     */
    private static int lastIdentifier(String text, String name, boolean beforeParenthesis) {
        int found = -1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else if (c == '(' && beforeParenthesis) {
                break;
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                if (text.substring(start, i).equals(name)) {
                    found = start;
                }
            } else {
                i++;
            }
        }
        return found;
    }

    /** The name of {@code unit} after its type is renamed {@code newName}, its extension kept. */
    private static String renamedUnitName(ICompilationUnit unit, String newName) {
        String name = unit.getElementName();
        return newName + name.substring(JavaCore.removeJavaLikeExtension(name).length());
    }

    private String formatPathFor(ICompilationUnit cu, IJdtService service) {
        if (cu == null || cu.getResource() == null) return null;
        IPath location = cu.getResource().getLocation();
        if (location == null) return null;
        return service.getPathUtils().formatPath(location.toOSString());
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

    private String findBindingKey(IJavaElement element, CompilationUnit ast, String name) throws JavaModelException {
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
    }
}
