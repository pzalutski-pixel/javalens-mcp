package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Inline a method call by replacing it with the method body.
 */
public class InlineMethodTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(InlineMethodTool.class);

    public InlineMethodTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "inline_method";
    }

    @Override
    public String getDescription() {
        return """
            Inline a method call by replacing it with the method body.

            Returns the text edit needed to inline the method call.
            The caller should apply this edit to perform the inlining.

            USAGE: Position cursor on a method call
            OUTPUT: Edit to replace call with method body

            IMPORTANT: Uses ZERO-BASED coordinates.

            LIMITATIONS:
            - Method must be in the same project (source available)
            - Works best with simple methods (no complex control flow)
            - Single return statement is handled, multiple returns may need review

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
                "description", "Path to source file containing the method call"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number of method call"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number (on method name)"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
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

        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
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

            // Calculate offset
            int offset = ast.getPosition(line + 1, column);
            if (offset < 0) {
                return ToolResponse.invalidParameter("position", "Invalid position");
            }

            // Find the method invocation at this position
            NodeFinder finder = new NodeFinder(ast, offset, 0);
            ASTNode node = finder.getCoveringNode();

            MethodInvocation invocation = findMethodInvocation(node);
            if (invocation == null) {
                return ToolResponse.invalidParameter("position",
                    "No method call found at position");
            }

            // Resolve the method binding
            IMethodBinding methodBinding = invocation.resolveMethodBinding();
            if (methodBinding == null) {
                return ToolResponse.invalidParameter("method",
                    "Cannot resolve method binding");
            }

            // Get the method declaration source
            IJavaElement javaElement = methodBinding.getJavaElement();
            if (!(javaElement instanceof IMethod method)) {
                return ToolResponse.invalidParameter("method",
                    "Cannot find method element");
            }

            // Check if we have source for this method
            ISourceRange sourceRange = method.getSourceRange();
            if (sourceRange == null || sourceRange.getOffset() < 0) {
                return ToolResponse.invalidParameter("method",
                    "Method source not available (may be from a library)");
            }

            // Get the compilation unit containing the method
            ICompilationUnit methodCu = method.getCompilationUnit();
            if (methodCu == null) {
                return ToolResponse.invalidParameter("method",
                    "Cannot find compilation unit for method");
            }

            // Parse the method's compilation unit
            ASTParser methodParser = ASTParser.newParser(AST.getJLSLatest());
            methodParser.setSource(methodCu);
            methodParser.setResolveBindings(true);
            methodParser.setBindingsRecovery(true);
            CompilationUnit methodAst = (CompilationUnit) methodParser.createAST(null);

            // Find the method declaration
            MethodDeclaration methodDecl = findMethodDeclaration(methodAst, method);
            if (methodDecl == null) {
                return ToolResponse.invalidParameter("method",
                    "Cannot find method declaration in AST");
            }

            // Get the method body
            Block body = methodDecl.getBody();
            if (body == null) {
                return ToolResponse.invalidParameter("method",
                    "Method has no body (abstract or native method)");
            }

            // Check for 'this' references
            if (usesThisExpression(methodDecl) && invocation.getExpression() == null) {
                // 'this' in method body refers to the same 'this' as caller - OK
            }

            // Build parameter substitution map
            @SuppressWarnings("unchecked")
            List<SingleVariableDeclaration> params = methodDecl.parameters();
            @SuppressWarnings("unchecked")
            List<Expression> args = invocation.arguments();

            if (params.size() != args.size()) {
                return ToolResponse.invalidParameter("method",
                    "Parameter/argument count mismatch");
            }

            Map<String, String> paramSubstitutions = new HashMap<>();
            for (int i = 0; i < params.size(); i++) {
                String paramName = params.get(i).getName().getIdentifier();
                String argText = args.get(i).toString();
                paramSubstitutions.put(paramName, argText);
            }

            // Build the inlined code
            String inlinedCode = buildInlinedCode(body, paramSubstitutions, invocation, cu);

            // Determine what to replace
            // If the invocation is the whole statement, replace the statement
            // If it's part of an expression (e.g., assignment), just replace the invocation

            ASTNode parent = invocation.getParent();
            ASTNode nodeToReplace;
            boolean isExpressionContext;

            if (parent instanceof ExpressionStatement) {
                nodeToReplace = parent;
                isExpressionContext = false;
            } else {
                nodeToReplace = invocation;
                isExpressionContext = true;
            }

            // Build edit
            List<Map<String, Object>> edits = new ArrayList<>();
            Map<String, Object> replaceEdit = new LinkedHashMap<>();
            replaceEdit.put("type", "replace");
            replaceEdit.put("startLine", ast.getLineNumber(nodeToReplace.getStartPosition()) - 1);
            replaceEdit.put("startColumn", ast.getColumnNumber(nodeToReplace.getStartPosition()));
            replaceEdit.put("endLine", ast.getLineNumber(nodeToReplace.getStartPosition() + nodeToReplace.getLength()) - 1);
            replaceEdit.put("endColumn", ast.getColumnNumber(nodeToReplace.getStartPosition() + nodeToReplace.getLength()));
            replaceEdit.put("startOffset", nodeToReplace.getStartPosition());
            replaceEdit.put("endOffset", nodeToReplace.getStartPosition() + nodeToReplace.getLength());
            replaceEdit.put("oldText", getNodeText(cu, nodeToReplace));
            replaceEdit.put("newText", inlinedCode);
            edits.add(replaceEdit);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("methodName", method.getElementName());
            data.put("methodClass", method.getDeclaringType().getElementName());
            data.put("parameterCount", params.size());
            data.put("isExpressionContext", isExpressionContext);
            data.put("inlinedCode", inlinedCode);
            data.put("edits", edits);

            // Add warnings if applicable
            List<String> warnings = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Statement> statements = body.statements();
            if (statements.size() > 1) {
                warnings.add("Method has multiple statements - review inlined code carefully");
            }
            if (countReturnStatements(body) > 1) {
                warnings.add("Method has multiple return statements - review needed");
            }
            if (!warnings.isEmpty()) {
                data.put("warnings", warnings);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edit to complete the inlining",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error inlining method: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private MethodInvocation findMethodInvocation(ASTNode node) {
        while (node != null) {
            if (node instanceof MethodInvocation mi) {
                return mi;
            }
            node = node.getParent();
        }
        return null;
    }

    private MethodDeclaration findMethodDeclaration(CompilationUnit ast, IMethod method) {
        final MethodDeclaration[] result = {null};
        final String methodName = method.getElementName();

        try {
            ISourceRange nameRange = method.getNameRange();
            final int nameOffset = nameRange != null ? nameRange.getOffset() : -1;

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    if (methodName.equals(node.getName().getIdentifier())) {
                        // Match by offset if available
                        if (nameOffset >= 0 && node.getName().getStartPosition() == nameOffset) {
                            result[0] = node;
                            return false;
                        }
                        // Fallback to first match by name
                        if (result[0] == null) {
                            result[0] = node;
                        }
                    }
                    return true;
                }
            });
        } catch (JavaModelException e) {
            log.debug("Error finding method declaration: {}", e.getMessage());
        }

        return result[0];
    }

    private boolean usesThisExpression(MethodDeclaration method) {
        final boolean[] usesThis = {false};

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(ThisExpression node) {
                usesThis[0] = true;
                return false;
            }
        });

        return usesThis[0];
    }

    private int countReturnStatements(Block body) {
        final int[] count = {0};

        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                count[0]++;
                return true;
            }
        });

        return count[0];
    }

    private String buildInlinedCode(Block body, Map<String, String> paramSubstitutions,
                                    MethodInvocation invocation, ICompilationUnit callSiteCu) {
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();

        if (statements.isEmpty()) {
            return "/* empty method body */";
        }

        // Handle single return statement specially
        if (statements.size() == 1 && statements.get(0) instanceof ReturnStatement rs) {
            Expression returnExpr = rs.getExpression();
            if (returnExpr != null) {
                String code = returnExpr.toString();
                code = substituteParameters(code, paramSubstitutions);
                // If used in expression context, wrap in parentheses if needed
                if (isComplexExpression(returnExpr)) {
                    code = "(" + code + ")";
                }
                return code;
            } else {
                return "/* void return */";
            }
        }

        // Handle single expression statement
        if (statements.size() == 1 && statements.get(0) instanceof ExpressionStatement es) {
            String code = es.getExpression().toString();
            code = substituteParameters(code, paramSubstitutions);
            return code;
        }

        // Multiple statements - wrap in block or expand
        StringBuilder result = new StringBuilder();

        // Check if the last statement is a return
        Statement lastStmt = statements.get(statements.size() - 1);
        boolean endsWithReturn = lastStmt instanceof ReturnStatement;

        // For simple cases without return, we can just emit statements
        if (!endsWithReturn) {
            result.append("{\n");
            for (Statement stmt : statements) {
                String stmtText = stmt.toString().trim();
                stmtText = substituteParameters(stmtText, paramSubstitutions);
                result.append("    ").append(stmtText).append("\n");
            }
            result.append("}");
        } else {
            // Method returns a value - need special handling
            // For expression context: wrap everything before return in initializer block
            // This is complex - for now, emit with comment

            result.append("/* Inlined from method - review needed */\n");
            for (int i = 0; i < statements.size() - 1; i++) {
                String stmtText = statements.get(i).toString().trim();
                stmtText = substituteParameters(stmtText, paramSubstitutions);
                result.append(stmtText).append("\n");
            }

            // Handle the return
            ReturnStatement rs = (ReturnStatement) lastStmt;
            if (rs.getExpression() != null) {
                String returnCode = rs.getExpression().toString();
                returnCode = substituteParameters(returnCode, paramSubstitutions);
                result.append("/* return: ").append(returnCode).append(" */");
            }
        }

        return result.toString();
    }

    private String substituteParameters(String code, Map<String, String> substitutions) {
        // Simple word-boundary substitution
        // This is a basic implementation - could be improved with proper AST rewriting
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            String param = entry.getKey();
            String arg = entry.getValue();

            // Use word boundary matching
            code = code.replaceAll("\\b" + param + "\\b", arg);
        }
        return code;
    }

    private boolean isComplexExpression(Expression expr) {
        int nodeType = expr.getNodeType();
        return nodeType == ASTNode.INFIX_EXPRESSION ||
               nodeType == ASTNode.CONDITIONAL_EXPRESSION ||
               nodeType == ASTNode.ASSIGNMENT ||
               nodeType == ASTNode.CAST_EXPRESSION;
    }

    private String getNodeText(ICompilationUnit cu, ASTNode node) {
        try {
            String source = cu.getSource();
            if (source != null) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                return source.substring(start, end);
            }
        } catch (JavaModelException e) {
            log.debug("Error getting node text: {}", e.getMessage());
        }
        return "";
    }
}
