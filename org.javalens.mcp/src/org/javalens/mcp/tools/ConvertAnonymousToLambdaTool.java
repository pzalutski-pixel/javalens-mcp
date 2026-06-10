package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Convert an anonymous class implementing a functional interface to a lambda expression.
 */
public class ConvertAnonymousToLambdaTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ConvertAnonymousToLambdaTool.class);

    public ConvertAnonymousToLambdaTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "convert_anonymous_to_lambda";
    }

    @Override
    public String getDescription() {
        return """
            Convert an anonymous class implementing a functional interface to a lambda expression.

            Returns the text edit needed to convert the anonymous class to a lambda.
            The caller should apply this edit to perform the conversion.

            USAGE: Position cursor on the 'new' keyword of the anonymous class
            OUTPUT: Edit to replace anonymous class with lambda

            IMPORTANT: Uses ZERO-BASED coordinates.
            REQUIREMENTS: The anonymous class must implement a functional interface
            (exactly one abstract method).

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number of anonymous class (on 'new' keyword)")
            .required("column", "integer", "Zero-based column number")
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

            // Find the ClassInstanceCreation with AnonymousClassDeclaration
            NodeFinder finder = new NodeFinder(ast, offset, 0);
            ASTNode node = finder.getCoveringNode();

            ClassInstanceCreation creation = findClassInstanceCreation(node);
            if (creation == null) {
                return ToolResponse.invalidParameter("position",
                    "No anonymous class found at position. Position cursor on 'new' keyword.");
            }

            AnonymousClassDeclaration anonymousClass = creation.getAnonymousClassDeclaration();
            if (anonymousClass == null) {
                return ToolResponse.invalidParameter("position",
                    "This is not an anonymous class declaration");
            }

            // Check if it's a functional interface
            // Use getType().resolveBinding() to get the interface type, not the anonymous class type
            ITypeBinding typeBinding = creation.getType().resolveBinding();
            if (typeBinding == null) {
                return ToolResponse.invalidParameter("type", "Cannot resolve type binding");
            }

            IMethodBinding samMethod = findSingleAbstractMethod(typeBinding);
            if (samMethod == null) {
                return ToolResponse.invalidParameter("type",
                    "Not a functional interface - must have exactly one abstract method");
            }

            // Get the method declaration from the anonymous class
            @SuppressWarnings("unchecked")
            List<?> bodyDecls = anonymousClass.bodyDeclarations();
            MethodDeclaration methodDecl = null;

            for (Object decl : bodyDecls) {
                if (decl instanceof MethodDeclaration md) {
                    if (methodDecl != null) {
                        // Multiple methods - can't convert to lambda
                        return ToolResponse.invalidParameter("anonymousClass",
                            "Anonymous class has multiple methods, cannot convert to lambda");
                    }
                    methodDecl = md;
                } else {
                    // Fields, initializers, and nested type declarations cannot be
                    // carried over to a lambda. Converting would drop the member
                    // and break any references to it from the SAM body.
                    return ToolResponse.invalidParameter("anonymousClass",
                        "Anonymous class declares non-method members (field, initializer, or "
                            + "nested type) that cannot be represented in a lambda. "
                            + "Manual review required.");
                }
            }

            if (methodDecl == null) {
                return ToolResponse.invalidParameter("anonymousClass",
                    "Anonymous class has no method implementation");
            }

            // Check for references whose binding context differs between an
            // anonymous class and a lambda. Bare `this` and any `super` form
            // bind to the anonymous instance; a lambda body rebinds them to
            // the enclosing class. Qualified Outer.this resolves identically
            // in both contexts and is safe to leave in.
            if (usesAnonymousInstanceBinding(methodDecl)) {
                return ToolResponse.invalidParameter("anonymousClass",
                    "Method uses bare `this` or a `super` reference whose binding "
                        + "differs in a lambda body. Manual review required.");
            }

            // Synthesize the lambda as a real LambdaExpression node and let
            // ASTRewrite render it in place of the anonymous creation: parameter
            // parentheses, the arrow, and body form (expression vs block) come
            // from the node structure rather than string assembly.
            String source = cu.getSource();
            AST astFactory = ast.getAST();
            ASTRewrite rewrite = ASTRewrite.create(astFactory);
            LambdaExpression lambda = buildLambdaNode(methodDecl, source, astFactory, rewrite);
            rewrite.replace(creation, lambda, null);

            TextEdit rewriteEdit = rewrite.rewriteAST();
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewriteEdit, source, ast);
            if (edits.size() != 1 || !"replace".equals(edits.get(0).get("type"))) {
                return ToolResponse.internalError(
                    "Unexpected rewrite shape for the lambda conversion: " + edits);
            }
            String lambdaExpression = (String) edits.get(0).get("newText");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("interfaceType", typeBinding.getName());
            data.put("methodName", samMethod.getName());
            data.put("lambdaExpression", lambdaExpression);
            data.put("edits", edits);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "Apply the text edit to complete the conversion",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error converting to lambda: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ClassInstanceCreation findClassInstanceCreation(ASTNode node) {
        while (node != null) {
            if (node instanceof ClassInstanceCreation cic) {
                if (cic.getAnonymousClassDeclaration() != null) {
                    return cic;
                }
            }
            node = node.getParent();
        }
        return null;
    }

    private IMethodBinding findSingleAbstractMethod(ITypeBinding typeBinding) {
        IMethodBinding samMethod = null;

        // Check declared methods
        for (IMethodBinding method : typeBinding.getDeclaredMethods()) {
            if (isAbstractMethod(method)) {
                if (samMethod != null) {
                    return null; // More than one abstract method
                }
                samMethod = method;
            }
        }

        // Check interface hierarchy
        for (ITypeBinding superInterface : typeBinding.getInterfaces()) {
            IMethodBinding inherited = findSingleAbstractMethod(superInterface);
            if (inherited != null) {
                if (samMethod != null && !methodsMatch(samMethod, inherited)) {
                    return null; // Multiple different abstract methods
                }
                if (samMethod == null) {
                    samMethod = inherited;
                }
            }
        }

        return samMethod;
    }

    private boolean isAbstractMethod(IMethodBinding method) {
        // Check if method is abstract (not default, not static)
        int modifiers = method.getModifiers();
        if ((modifiers & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
            return false;
        }
        if ((modifiers & org.eclipse.jdt.core.dom.Modifier.DEFAULT) != 0) {
            return false;
        }
        // Object methods are not counted
        if (isObjectMethod(method)) {
            return false;
        }
        return true;
    }

    private boolean isObjectMethod(IMethodBinding method) {
        String name = method.getName();
        ITypeBinding[] params = method.getParameterTypes();

        // toString(), hashCode(), equals(Object), etc.
        if ("toString".equals(name) && params.length == 0) return true;
        if ("hashCode".equals(name) && params.length == 0) return true;
        if ("equals".equals(name) && params.length == 1 &&
            "java.lang.Object".equals(params[0].getQualifiedName())) return true;
        if ("clone".equals(name) && params.length == 0) return true;
        if ("finalize".equals(name) && params.length == 0) return true;

        return false;
    }

    private boolean methodsMatch(IMethodBinding m1, IMethodBinding m2) {
        if (!m1.getName().equals(m2.getName())) return false;
        ITypeBinding[] params1 = m1.getParameterTypes();
        ITypeBinding[] params2 = m2.getParameterTypes();
        if (params1.length != params2.length) return false;
        for (int i = 0; i < params1.length; i++) {
            if (!params1[i].getErasure().isEqualTo(params2[i].getErasure())) {
                return false;
            }
        }
        return true;
    }

    private boolean usesAnonymousInstanceBinding(MethodDeclaration method) {
        final boolean[] flag = {false};

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(ThisExpression node) {
                // Qualified Outer.this resolves to the enclosing instance in
                // both anonymous-class and lambda contexts. Only bare `this`
                // rebinds across the conversion.
                if (node.getQualifier() == null) {
                    flag[0] = true;
                }
                return false;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                flag[0] = true;
                return false;
            }

            @Override
            public boolean visit(SuperFieldAccess node) {
                flag[0] = true;
                return false;
            }

            @Override
            public boolean visit(SuperMethodReference node) {
                flag[0] = true;
                return false;
            }
        });

        return flag[0];
    }

    /**
     * Builds the replacement LambdaExpression: untyped parameters named after
     * the SAM implementation's parameters (parentheses omitted for exactly
     * one), and a body that is the return expression / sole expression where
     * the expression form applies, or a block of the original statements
     * (carried as source-slice placeholders) otherwise.
     */
    @SuppressWarnings("unchecked")
    private LambdaExpression buildLambdaNode(MethodDeclaration method, String source,
                                             AST astFactory, ASTRewrite rewrite) {
        LambdaExpression lambda = astFactory.newLambdaExpression();

        List<SingleVariableDeclaration> params = method.parameters();
        lambda.setParentheses(params.size() != 1);
        for (SingleVariableDeclaration param : params) {
            VariableDeclarationFragment fragment = astFactory.newVariableDeclarationFragment();
            fragment.setName(astFactory.newSimpleName(param.getName().getIdentifier()));
            lambda.parameters().add(fragment);
        }

        Block body = method.getBody();
        List<Statement> statements = body == null
            ? List.of() : (List<Statement>) body.statements();

        if (statements.size() == 1 && statements.get(0) instanceof ReturnStatement rs
                && rs.getExpression() != null) {
            lambda.setBody(placeholderFor(rs.getExpression(), source, rewrite));
        } else if (statements.size() == 1 && statements.get(0) instanceof ExpressionStatement es) {
            lambda.setBody(placeholderFor(es.getExpression(), source, rewrite));
        } else {
            Block block = astFactory.newBlock();
            for (Statement stmt : statements) {
                if (statements.size() == 1 && stmt instanceof ReturnStatement rs
                        && rs.getExpression() == null) {
                    continue; // a lone `return;` becomes an empty block
                }
                block.statements().add(placeholderFor(stmt, source, rewrite));
            }
            lambda.setBody(block);
        }
        return lambda;
    }

    private ASTNode placeholderFor(ASTNode original, String source, ASTRewrite rewrite) {
        String text = source.substring(original.getStartPosition(),
            original.getStartPosition() + original.getLength());
        return rewrite.createStringPlaceholder(text, original.getNodeType());
    }
}
