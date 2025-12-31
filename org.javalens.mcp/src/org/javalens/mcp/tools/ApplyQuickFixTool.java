package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
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
import java.util.function.Supplier;

/**
 * Apply a quick fix by ID and return text edits.
 *
 * Parses the fixId format ({type}:{param}) and generates the appropriate
 * text edits to apply the fix.
 */
public class ApplyQuickFixTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyQuickFixTool.class);

    public ApplyQuickFixTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "apply_quick_fix";
    }

    @Override
    public String getDescription() {
        return """
            Apply a fix by ID.

            USAGE: apply_quick_fix(filePath="...", fixId="add_import:java.util.List")
            OUTPUT: Text edits to apply the fix

            Fix ID formats:
            - add_import:{fullyQualifiedName} - Add an import statement
            - remove_import:{index} - Remove import at index
            - add_throws:{exceptionType} - Add throws declaration to method
            - surround_try_catch:{exceptionType} - Wrap statement in try-catch

            IMPORTANT: Uses ZERO-BASED line numbers.

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
                "description", "Path to source file"
            ),
            "fixId", Map.of(
                "type", "string",
                "description", "The fix ID from get_quick_fixes (e.g., 'add_import:java.util.List')"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number (required for some fixes like add_throws)"
            )
        ));
        schema.put("required", List.of("filePath", "fixId"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String fixId = getStringParam(arguments, "fixId");
        int line = getIntParam(arguments, "line", -1);

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        if (fixId == null || fixId.isBlank()) {
            return ToolResponse.invalidParameter("fixId", "Required");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Parse fixId: {type}:{param}
            int colonIndex = fixId.indexOf(':');
            if (colonIndex <= 0) {
                return ToolResponse.invalidParameter("fixId", "Invalid format. Expected type:param");
            }

            String fixType = fixId.substring(0, colonIndex);
            String fixParam = fixId.substring(colonIndex + 1);

            // Parse AST
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            List<Map<String, Object>> edits = switch (fixType) {
                case "add_import" -> applyAddImport(cu, ast, fixParam, service);
                case "remove_import" -> applyRemoveImport(cu, ast, fixParam, service);
                case "add_throws" -> applyAddThrows(cu, ast, line, fixParam, service);
                case "surround_try_catch" -> applySurroundTryCatch(cu, ast, line, fixParam, service);
                default -> throw new IllegalArgumentException("Unknown fix type: " + fixType);
            };

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("fixId", fixId);
            data.put("fixType", fixType);
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_diagnostics to verify the fix",
                    "get_quick_fixes to check for remaining issues"
                ))
                .build());

        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("fixId", e.getMessage());
        } catch (Exception e) {
            log.error("Error applying quick fix '{}': {}", fixId, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Add an import statement.
     */
    private List<Map<String, Object>> applyAddImport(ICompilationUnit cu, CompilationUnit ast,
                                                      String fullyQualifiedName, IJdtService service)
            throws Exception {
        @SuppressWarnings("unchecked")
        List<ImportDeclaration> imports = ast.imports();
        String source = cu.getSource();

        int insertOffset;
        String newText;

        if (imports.isEmpty()) {
            // Insert after package declaration (or at start)
            PackageDeclaration pkg = ast.getPackage();
            if (pkg != null) {
                insertOffset = pkg.getStartPosition() + pkg.getLength();
                // Skip any whitespace/newlines after package
                while (insertOffset < source.length() &&
                       (source.charAt(insertOffset) == '\n' || source.charAt(insertOffset) == '\r')) {
                    insertOffset++;
                }
                newText = "\nimport " + fullyQualifiedName + ";\n";
            } else {
                insertOffset = 0;
                newText = "import " + fullyQualifiedName + ";\n\n";
            }
        } else {
            // Find correct position in sorted import list
            int insertIdx = findImportInsertionIndex(imports, fullyQualifiedName);

            if (insertIdx < imports.size()) {
                ImportDeclaration nextImport = imports.get(insertIdx);
                insertOffset = nextImport.getStartPosition();
                newText = "import " + fullyQualifiedName + ";\n";
            } else {
                ImportDeclaration lastImport = imports.get(imports.size() - 1);
                insertOffset = lastImport.getStartPosition() + lastImport.getLength();
                // Skip trailing whitespace
                while (insertOffset < source.length() &&
                       (source.charAt(insertOffset) == '\n' || source.charAt(insertOffset) == '\r')) {
                    insertOffset++;
                }
                newText = "import " + fullyQualifiedName + ";\n";
            }
        }

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "insert");
        edit.put("offset", insertOffset);
        edit.put("line", ast.getLineNumber(insertOffset) - 1);
        edit.put("newText", newText);

        return List.of(edit);
    }

    /**
     * Find the index where a new import should be inserted (sorted).
     */
    private int findImportInsertionIndex(List<ImportDeclaration> imports, String newImport) {
        int newOrder = getImportOrder(newImport);

        for (int i = 0; i < imports.size(); i++) {
            ImportDeclaration imp = imports.get(i);
            if (imp.isStatic() || imp.isOnDemand()) continue;

            String existingImport = imp.getName().getFullyQualifiedName();
            int existingOrder = getImportOrder(existingImport);

            if (newOrder < existingOrder) {
                return i;
            } else if (newOrder == existingOrder && newImport.compareTo(existingImport) < 0) {
                return i;
            }
        }

        return imports.size();
    }

    private int getImportOrder(String importName) {
        if (importName.startsWith("java.")) return 0;
        if (importName.startsWith("javax.")) return 1;
        return 2;
    }

    /**
     * Remove an import at the specified index.
     */
    private List<Map<String, Object>> applyRemoveImport(ICompilationUnit cu, CompilationUnit ast,
                                                         String indexStr, IJdtService service)
            throws Exception {
        int index = Integer.parseInt(indexStr);

        @SuppressWarnings("unchecked")
        List<ImportDeclaration> imports = ast.imports();

        if (index < 0 || index >= imports.size()) {
            throw new IllegalArgumentException("Import index out of range: " + index);
        }

        ImportDeclaration toRemove = imports.get(index);
        int start = toRemove.getStartPosition();
        int end = start + toRemove.getLength();

        // Include trailing newline
        String source = cu.getSource();
        while (end < source.length() && (source.charAt(end) == '\n' || source.charAt(end) == '\r')) {
            end++;
        }

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "delete");
        edit.put("startOffset", start);
        edit.put("endOffset", end);
        edit.put("startLine", ast.getLineNumber(start) - 1);
        edit.put("endLine", ast.getLineNumber(end) - 1);

        return List.of(edit);
    }

    /**
     * Add throws declaration to enclosing method.
     */
    private List<Map<String, Object>> applyAddThrows(ICompilationUnit cu, CompilationUnit ast,
                                                      int line, String exceptionType, IJdtService service)
            throws Exception {
        if (line < 0) {
            throw new IllegalArgumentException("Line parameter required for add_throws");
        }

        // Find enclosing method at line
        MethodDeclaration method = findMethodAtLine(ast, line);
        if (method == null) {
            throw new IllegalArgumentException("No method found at line " + line);
        }

        @SuppressWarnings("unchecked")
        List<Type> thrownExceptions = method.thrownExceptionTypes();
        String source = cu.getSource();

        int insertOffset;
        String newText;

        if (thrownExceptions.isEmpty()) {
            // Find the closing paren of method parameters
            int methodEnd = method.getName().getStartPosition() + method.getName().getLength();
            int parenCount = 0;
            insertOffset = methodEnd;

            // Find the closing paren
            for (int i = methodEnd; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') parenCount++;
                else if (c == ')') {
                    if (parenCount == 0) {
                        insertOffset = i + 1;
                        break;
                    }
                    parenCount--;
                }
            }

            newText = " throws " + exceptionType;
        } else {
            // Append to existing throws
            Type lastException = thrownExceptions.get(thrownExceptions.size() - 1);
            insertOffset = lastException.getStartPosition() + lastException.getLength();
            newText = ", " + exceptionType;
        }

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "insert");
        edit.put("offset", insertOffset);
        edit.put("line", ast.getLineNumber(insertOffset) - 1);
        edit.put("newText", newText);

        return List.of(edit);
    }

    /**
     * Surround statement at line with try-catch.
     */
    private List<Map<String, Object>> applySurroundTryCatch(ICompilationUnit cu, CompilationUnit ast,
                                                             int line, String exceptionType,
                                                             IJdtService service) throws Exception {
        if (line < 0) {
            throw new IllegalArgumentException("Line parameter required for surround_try_catch");
        }

        // Find statement at line
        Statement stmt = findStatementAtLine(ast, line);
        if (stmt == null) {
            throw new IllegalArgumentException("No statement found at line " + line);
        }

        String source = cu.getSource();
        int start = stmt.getStartPosition();
        int end = start + stmt.getLength();
        String originalCode = source.substring(start, end);

        // Determine indentation
        String indent = getIndentation(source, start);
        String innerIndent = indent + "    ";

        // Build try-catch block
        StringBuilder tryCatch = new StringBuilder();
        tryCatch.append("try {\n");
        tryCatch.append(innerIndent).append(originalCode.trim()).append("\n");
        tryCatch.append(indent).append("} catch (").append(exceptionType).append(" e) {\n");
        tryCatch.append(innerIndent).append("// TODO: handle exception\n");
        tryCatch.append(innerIndent).append("e.printStackTrace();\n");
        tryCatch.append(indent).append("}");

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "replace");
        edit.put("startOffset", start);
        edit.put("endOffset", end);
        edit.put("startLine", ast.getLineNumber(start) - 1);
        edit.put("endLine", ast.getLineNumber(end) - 1);
        edit.put("newText", tryCatch.toString());

        return List.of(edit);
    }

    /**
     * Find the method declaration containing the given line.
     */
    private MethodDeclaration findMethodAtLine(CompilationUnit ast, int line) {
        final MethodDeclaration[] result = {null};
        int targetLine = line + 1; // Convert to 1-based for AST

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int startLine = ast.getLineNumber(node.getStartPosition());
                int endLine = ast.getLineNumber(node.getStartPosition() + node.getLength());

                if (targetLine >= startLine && targetLine <= endLine) {
                    result[0] = node;
                }
                return true;
            }
        });

        return result[0];
    }

    /**
     * Find a statement at the given line.
     */
    private Statement findStatementAtLine(CompilationUnit ast, int line) {
        final Statement[] result = {null};
        int targetLine = line + 1; // Convert to 1-based

        ast.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Statement stmt && !(node instanceof Block)) {
                    int nodeLine = ast.getLineNumber(node.getStartPosition());
                    if (nodeLine == targetLine) {
                        result[0] = stmt;
                    }
                }
            }
        });

        return result[0];
    }

    /**
     * Get the indentation of the line containing the given offset.
     */
    private String getIndentation(String source, int offset) {
        // Find start of line
        int lineStart = offset;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        // Collect leading whitespace
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }

        return indent.toString();
    }
}
