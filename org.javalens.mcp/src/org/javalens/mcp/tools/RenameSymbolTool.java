package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file containing the symbol"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "newName", Map.of(
                "type", "string",
                "description", "New name for the symbol"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column", "newName"));
        return schema;
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

            // Get the binding key by parsing the AST at the element's location
            ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu == null) {
                return ToolResponse.symbolNotFound("Cannot find compilation unit for element");
            }

            // Parse AST to get binding key
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Find the binding key using the element's source range
            String targetKey = findBindingKey(element, ast, oldName);
            if (targetKey == null) {
                // Fallback to handle identifier matching
                targetKey = element.getHandleIdentifier();
                log.debug("Using handle identifier as fallback: {}", targetKey);
            }

            // Find all references across project
            Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
            int totalEdits = 0;

            for (Path sourceFile : service.getAllJavaFiles()) {
                try {
                    ICompilationUnit sourceCu = service.getCompilationUnit(sourceFile);
                    if (sourceCu == null) continue;

                    ASTParser sourceParser = ASTParser.newParser(AST.getJLSLatest());
                    sourceParser.setSource(sourceCu);
                    sourceParser.setResolveBindings(true);
                    sourceParser.setBindingsRecovery(true);
                    CompilationUnit sourceAst = (CompilationUnit) sourceParser.createAST(null);

                    List<Map<String, Object>> fileEdits = findRenameEdits(
                        sourceAst, targetKey, oldName, newName
                    );

                    if (!fileEdits.isEmpty()) {
                        String relativePath = service.getPathUtils().formatPath(sourceFile);
                        editsByFile.put(relativePath, fileEdits);
                        totalEdits += fileEdits.size();
                    }
                } catch (Exception e) {
                    log.debug("Error finding rename edits in file: {}", e.getMessage());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("oldName", oldName);
            data.put("newName", newName);
            data.put("symbolKind", symbolKind);
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", editsByFile.size());
            data.put("editsByFile", editsByFile);

            if (element instanceof IType) {
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

        } catch (Exception e) {
            log.error("Error renaming symbol: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private List<Map<String, Object>> findRenameEdits(CompilationUnit ast, String targetKey,
                                                       String oldName, String newName) {
        List<Map<String, Object>> edits = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!oldName.equals(node.getIdentifier())) {
                    return true;
                }

                IBinding nodeBinding = node.resolveBinding();
                if (nodeBinding != null && targetKey.equals(nodeBinding.getKey())) {
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
        if (element instanceof IType type) {
            try {
                if (type.isInterface()) return "Interface";
                if (type.isEnum()) return "Enum";
                if (type.isAnnotation()) return "Annotation";
            } catch (JavaModelException e) {
                log.debug("Error checking type kind: {}", e.getMessage());
            }
            return "Class";
        }
        if (element instanceof IMethod method) {
            try {
                return method.isConstructor() ? "Constructor" : "Method";
            } catch (JavaModelException e) {
                return "Method";
            }
        }
        if (element instanceof IField) return "Field";
        if (element instanceof ILocalVariable) return "LocalVariable";
        if (element instanceof ITypeParameter) return "TypeParameter";
        return "Unknown";
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
