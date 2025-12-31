package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.Statement;
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
 * Extract an expression into a local variable.
 */
public class ExtractVariableTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractVariableTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractVariableTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "extract_variable";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression at the given position into a local variable.

            Returns the text edits needed to extract the expression.
            The caller should apply these edits to perform the extraction.

            USAGE: Select expression by providing start and end positions
            OUTPUT: Variable declaration and replacement edits

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
                "description", "Path to source file"
            ),
            "startLine", Map.of(
                "type", "integer",
                "description", "Zero-based start line of expression"
            ),
            "startColumn", Map.of(
                "type", "integer",
                "description", "Zero-based start column of expression"
            ),
            "endLine", Map.of(
                "type", "integer",
                "description", "Zero-based end line of expression"
            ),
            "endColumn", Map.of(
                "type", "integer",
                "description", "Zero-based end column of expression"
            ),
            "variableName", Map.of(
                "type", "string",
                "description", "Name for the new variable (optional, will suggest if not provided)"
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        int startLine = getIntParam(arguments, "startLine", -1);
        int startColumn = getIntParam(arguments, "startColumn", -1);
        int endLine = getIntParam(arguments, "endLine", -1);
        int endColumn = getIntParam(arguments, "endColumn", -1);
        String variableName = getStringParam(arguments, "variableName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return ToolResponse.invalidParameter("positions", "All positions must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Parse to AST with bindings
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Calculate offsets
            int startOffset = ast.getPosition(startLine + 1, startColumn);
            int endOffset = ast.getPosition(endLine + 1, endColumn);

            if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
                return ToolResponse.invalidParameter("positions", "Invalid selection range");
            }

            // Find the expression at this range
            NodeFinder finder = new NodeFinder(ast, startOffset, endOffset - startOffset);
            ASTNode coveredNode = finder.getCoveredNode();
            ASTNode coveringNode = finder.getCoveringNode();

            Expression expression = null;
            if (coveredNode instanceof Expression expr) {
                expression = expr;
            } else if (coveringNode instanceof Expression expr) {
                expression = expr;
            }

            if (expression == null) {
                return ToolResponse.invalidParameter("selection", "No extractable expression at selection");
            }

            // Get the type of the expression
            ITypeBinding typeBinding = expression.resolveTypeBinding();
            String typeName = typeBinding != null ? typeBinding.getName() : "var";

            // Generate variable name if not provided
            if (variableName == null || variableName.isBlank()) {
                variableName = suggestVariableName(expression, typeBinding);
            }

            if (!isValidJavaIdentifier(variableName)) {
                return ToolResponse.invalidParameter("variableName", "Not a valid Java identifier");
            }

            // Find the containing statement to insert before
            Statement containingStatement = findContainingStatement(expression);
            if (containingStatement == null) {
                return ToolResponse.invalidParameter("selection", "Cannot find containing statement");
            }

            // Get expression text
            String expressionText = expression.toString();

            // Build the variable declaration
            String declaration = typeName + " " + variableName + " = " + expressionText + ";";

            // Calculate insertion point
            int insertOffset = containingStatement.getStartPosition();
            int insertLine = ast.getLineNumber(insertOffset) - 1;

            // Get indentation
            String indent = getIndentation(cu, containingStatement);

            // Build edits
            List<Map<String, Object>> edits = new ArrayList<>();

            // Edit 1: Insert variable declaration
            Map<String, Object> insertEdit = new LinkedHashMap<>();
            insertEdit.put("type", "insert");
            insertEdit.put("line", insertLine);
            insertEdit.put("column", 0);
            insertEdit.put("offset", insertOffset);
            insertEdit.put("newText", indent + declaration + "\n");
            edits.add(insertEdit);

            // Edit 2: Replace expression with variable name
            Map<String, Object> replaceEdit = new LinkedHashMap<>();
            replaceEdit.put("type", "replace");
            replaceEdit.put("startLine", ast.getLineNumber(expression.getStartPosition()) - 1);
            replaceEdit.put("startColumn", ast.getColumnNumber(expression.getStartPosition()));
            replaceEdit.put("endLine", ast.getLineNumber(expression.getStartPosition() + expression.getLength()) - 1);
            replaceEdit.put("endColumn", ast.getColumnNumber(expression.getStartPosition() + expression.getLength()));
            replaceEdit.put("startOffset", expression.getStartPosition());
            replaceEdit.put("endOffset", expression.getStartPosition() + expression.getLength());
            replaceEdit.put("oldText", expressionText);
            replaceEdit.put("newText", variableName);
            edits.add(replaceEdit);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("variableName", variableName);
            data.put("variableType", typeName);
            data.put("expressionText", expressionText);
            data.put("declaration", declaration);
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the extraction",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error extracting variable: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Statement findContainingStatement(ASTNode node) {
        while (node != null) {
            if (node instanceof Statement stmt && !(node instanceof Block)) {
                return stmt;
            }
            node = node.getParent();
        }
        return null;
    }

    private String getIndentation(ICompilationUnit cu, ASTNode node) {
        try {
            String source = cu.getSource();
            if (source == null) return "        ";

            int nodeStart = node.getStartPosition();
            int lineStart = source.lastIndexOf('\n', nodeStart - 1) + 1;

            StringBuilder indent = new StringBuilder();
            for (int i = lineStart; i < nodeStart && i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == ' ' || c == '\t') {
                    indent.append(c);
                } else {
                    break;
                }
            }
            return indent.toString();
        } catch (JavaModelException e) {
            log.debug("Error getting indentation: {}", e.getMessage());
            return "        ";
        }
    }

    private String suggestVariableName(Expression expression, ITypeBinding typeBinding) {
        String suggested = null;

        if (typeBinding != null) {
            String typeName = typeBinding.getName();
            if (typeName.length() > 0) {
                int genericIndex = typeName.indexOf('<');
                if (genericIndex > 0) {
                    typeName = typeName.substring(0, genericIndex);
                }
                suggested = Character.toLowerCase(typeName.charAt(0)) +
                       (typeName.length() > 1 ? typeName.substring(1) : "");
            }
        }

        if (suggested == null && expression instanceof MethodInvocation mi) {
            String methodName = mi.getName().getIdentifier();
            if (methodName.startsWith("get") && methodName.length() > 3) {
                suggested = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else {
                suggested = methodName + "Result";
            }
        }

        if (suggested == null && expression instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            suggested = Character.toLowerCase(typeName.charAt(0)) +
                   (typeName.length() > 1 ? typeName.substring(1) : "");
        }

        if (suggested == null) {
            suggested = "extracted";
        }

        // Fall back to generic names if suggested name is a reserved word
        if (RESERVED_WORDS.contains(suggested)) {
            if (typeBinding != null && typeBinding.isPrimitive()) {
                return suggested + "Value";  // e.g., "intValue", "booleanValue"
            }
            return "value";
        }

        return suggested;
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
}
