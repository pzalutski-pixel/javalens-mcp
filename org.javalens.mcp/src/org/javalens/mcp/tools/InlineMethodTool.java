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
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.text.edits.TextEdit;
import org.javalens.core.IJdtService;
import org.javalens.mcp.rewrite.TextEditConverter;
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
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the method call")
            .required("line", "integer", "Zero-based line number of method call")
            .required("column", "integer", "Zero-based column number (on method name)")
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

            // Bind each parameter to its argument's source text. Substitution is
            // binding-driven: only SimpleNames that RESOLVE to the parameter are
            // replaced, so identical text inside string literals, comments, or
            // shadowing locals is never touched (the old word-boundary regex was).
            String callSource = cu.getSource();
            String methodSource = methodCu.getSource();
            Map<String, String> argTextByParamKey = new HashMap<>();
            for (int i = 0; i < params.size(); i++) {
                IVariableBinding paramBinding = params.get(i).resolveBinding();
                if (paramBinding == null) {
                    return ToolResponse.invalidParameter("method",
                        "Cannot resolve parameter binding for substitution");
                }
                Expression arg = args.get(i);
                argTextByParamKey.put(paramBinding.getKey(),
                    callSource.substring(arg.getStartPosition(),
                        arg.getStartPosition() + arg.getLength()));
            }

            // Build the inlined code from method-source slices with the
            // parameter occurrences spliced out.
            String inlinedCode = buildInlinedCode(body, methodSource, argTextByParamKey);

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

            // Synthesize the call-site replacement via ASTRewrite.
            ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
            rewrite.replace(nodeToReplace,
                rewrite.createStringPlaceholder(inlinedCode, nodeToReplace.getNodeType()), null);
            TextEdit rewriteEdit = rewrite.rewriteAST();
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewriteEdit, callSource, ast);

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
            if (bodyUsesSuper(methodDecl)) {
                warnings.add("Method body references `super` (super.method(), super.field, " +
                    "super(), or super::method). Inlining substitutes the body textually, but " +
                    "`super` in the inlined code will resolve against the CALL SITE's class " +
                    "hierarchy, not the original declaring class. If the call site is in a " +
                    "different class than the declaration, the dispatch target changes. " +
                    "Review carefully and replace `super.X` with the explicit target if needed.");
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

    /**
     * Returns true if the method body references {@code super} in any AST form —
     * SuperMethodInvocation (super.foo()), SuperFieldAccess (super.field),
     * SuperConstructorInvocation (super()), or SuperMethodReference (super::foo).
     * Inlining a body containing any of these into a call site in a different
     * class changes the dispatch target of `super`, which is rarely the user's
     * intent. The tool emits a warning when this is detected.
     */
    private boolean bodyUsesSuper(MethodDeclaration method) {
        final boolean[] usesSuper = {false};

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SuperMethodInvocation node) {
                usesSuper[0] = true;
                return false;
            }
            @Override
            public boolean visit(SuperFieldAccess node) {
                usesSuper[0] = true;
                return false;
            }
            @Override
            public boolean visit(SuperConstructorInvocation node) {
                usesSuper[0] = true;
                return false;
            }
            @Override
            public boolean visit(SuperMethodReference node) {
                usesSuper[0] = true;
                return false;
            }
        });

        return usesSuper[0];
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

    private String buildInlinedCode(Block body, String methodSource,
                                    Map<String, String> argTextByParamKey) {
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();

        if (statements.isEmpty()) {
            return "/* empty method body */";
        }

        // Handle single return statement specially
        if (statements.size() == 1 && statements.get(0) instanceof ReturnStatement rs) {
            Expression returnExpr = rs.getExpression();
            if (returnExpr != null) {
                String code = substituteParameters(returnExpr, methodSource, argTextByParamKey);
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
            return substituteParameters(es.getExpression(), methodSource, argTextByParamKey);
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
                String stmtText = substituteParameters(stmt, methodSource, argTextByParamKey).trim();
                result.append("    ").append(stmtText).append("\n");
            }
            result.append("}");
        } else {
            // Method returns a value - need special handling
            // For expression context: wrap everything before return in initializer block
            // This is complex - for now, emit with comment

            result.append("/* Inlined from method - review needed */\n");
            for (int i = 0; i < statements.size() - 1; i++) {
                String stmtText = substituteParameters(statements.get(i), methodSource, argTextByParamKey).trim();
                result.append(stmtText).append("\n");
            }

            // Handle the return
            ReturnStatement rs = (ReturnStatement) lastStmt;
            if (rs.getExpression() != null) {
                String returnCode = substituteParameters(rs.getExpression(), methodSource, argTextByParamKey);
                result.append("/* return: ").append(returnCode).append(" */");
            }
        }

        return result.toString();
    }

    /**
     * Returns {@code node}'s source slice with every SimpleName that RESOLVES
     * to one of the method's parameters replaced by the call's argument text.
     * Binding-driven: occurrences inside string literals or belonging to
     * shadowing declarations are untouched by construction.
     */
    private String substituteParameters(ASTNode node, String methodSource,
                                        Map<String, String> argTextByParamKey) {
        List<SimpleName> paramReferences = new ArrayList<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.resolveBinding() instanceof IVariableBinding vb
                        && argTextByParamKey.containsKey(vb.getKey())) {
                    paramReferences.add(name);
                }
                return true;
            }
        });

        int nodeStart = node.getStartPosition();
        StringBuilder out = new StringBuilder(
            methodSource.substring(nodeStart, nodeStart + node.getLength()));
        // Splice replacements back-to-front so earlier offsets stay valid.
        paramReferences.sort((a, b) -> Integer.compare(b.getStartPosition(), a.getStartPosition()));
        for (SimpleName ref : paramReferences) {
            IVariableBinding vb = (IVariableBinding) ref.resolveBinding();
            int from = ref.getStartPosition() - nodeStart;
            out.replace(from, from + ref.getLength(), argTextByParamKey.get(vb.getKey()));
        }
        return out.toString();
    }

    private boolean isComplexExpression(Expression expr) {
        int nodeType = expr.getNodeType();
        return nodeType == ASTNode.INFIX_EXPRESSION ||
               nodeType == ASTNode.CONDITIONAL_EXPRESSION ||
               nodeType == ASTNode.ASSIGNMENT ||
               nodeType == ASTNode.CAST_EXPRESSION;
    }
}
