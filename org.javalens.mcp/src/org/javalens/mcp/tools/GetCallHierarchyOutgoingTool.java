package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Find all methods called by a method (outgoing calls).
 *
 * Parses the method body AST and collects all dispatch-graph nodes including:
 * - MethodInvocation (regular method calls)
 * - SuperMethodInvocation (super.method() calls)
 * - ClassInstanceCreation (new Foo() constructor calls)
 * - ConstructorInvocation (this() calls)
 * - SuperConstructorInvocation (super() calls)
 * - ExpressionMethodReference (instance::method)
 * - TypeMethodReference (Foo::method)
 * - SuperMethodReference (super::method)
 * - CreationReference (Foo::new)
 *
 * Method-reference callees carry callType="METHOD_REFERENCE" — they are
 * deferred dispatches (the method is invoked when the functional interface
 * is later applied, not at the reference site itself) but still part of the
 * dispatch graph.
 *
 * The calls are those the method makes itself, the inverse of incoming calls: a
 * call in a lambda written in the method belongs to the method, and a call in
 * the body of an anonymous or local class declared in it belongs to that class's
 * member. Creating an anonymous class is a call of the superclass constructor
 * its constructor runs, returning the anonymous class (CreationCalls).
 */
public class GetCallHierarchyOutgoingTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetCallHierarchyOutgoingTool.class);

    public GetCallHierarchyOutgoingTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_call_hierarchy_outgoing";
    }

    @Override
    public String getDescription() {
        return """
            Find all methods called by a method (outgoing calls).

            USAGE: Position cursor on a method name
            OUTPUT: List of methods that this method calls

            A call in a lambda written in the method is the method's call; a
            call in the body of an anonymous or local class declared in it is a
            call of that class's member, as get_call_hierarchy_incoming reports
            it. Creating an anonymous class, and its arguments, are calls of the
            method; the creation is a call of the superclass constructor the
            anonymous class's constructor runs (Object() for an interface),
            returning the anonymous class.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Useful for understanding what a method depends on.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        try {
            Path path = Path.of(filePath);

            // Get compilation unit
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Parse to get AST with bindings using ASTParser
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setStatementsRecovery(true);

            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            if (ast == null) {
                return ToolResponse.internalError("Failed to get AST for file");
            }

            // Find the method declaration at position
            int offset = service.getOffset(cu, line, column);
            NodeFinder finder = new NodeFinder(ast, offset, 0);
            ASTNode coveringNode = finder.getCoveringNode();

            MethodDeclaration methodDecl = findEnclosingMethod(coveringNode);
            if (methodDecl == null) {
                return ToolResponse.invalidParameter("position", "Position is not within a method");
            }

            IMethodBinding methodBinding = methodDecl.resolveBinding();
            if (methodBinding == null) {
                return ToolResponse.symbolNotFound("Could not resolve method binding");
            }

            // Get method metadata
            String methodName = methodBinding.getName();
            ITypeBinding declaringTypeBinding = methodBinding.getDeclaringClass();
            String declaringClass = declaringTypeBinding != null ?
                ElementNames.qualifiedName(declaringTypeBinding) : "Unknown";
            String signature = buildSignature(methodBinding);

            // Collect all callees
            List<Map<String, Object>> callees = findCallees(ast, methodDecl, path, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", methodName);
            data.put("declaringClass", declaringClass);
            data.put("signature", signature);
            data.put("totalCallees", callees.size());
            data.put("callees", callees);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(callees.size())
                .returnedCount(callees.size())
                .suggestedNextTools(List.of(
                    "get_call_hierarchy_incoming to see who calls this method",
                    "go_to_definition to navigate to a called method"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding callees: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private MethodDeclaration findEnclosingMethod(ASTNode node) {
        while (node != null) {
            if (node instanceof MethodDeclaration md) {
                return md;
            }
            node = node.getParent();
        }
        return null;
    }

    private List<Map<String, Object>> findCallees(CompilationUnit ast, MethodDeclaration method,
                                                   Path filePath, IJdtService service) {
        List<Map<String, Object>> callees = new ArrayList<>();
        Set<String> seenMethods = new HashSet<>();

        method.accept(new ASTVisitor() {
            // The body of an anonymous or local class declared in the method holds the calls of
            // that class's members, as incoming calls attribute them; the creation of an
            // anonymous class and its arguments stay calls of this method.
            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(TypeDeclarationStatement node) {
                return false;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    String key = binding.getKey();
                    if (seenMethods.add(key)) {
                        addMethodCallee(node, binding, callees, ast, filePath, service, "METHOD");
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    String key = binding.getKey();
                    if (seenMethods.add(key)) {
                        addSuperMethodCallee(node, binding, callees, ast, filePath, service);
                    }
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                IMethodBinding constructor = CreationCalls.constructorCalled(node);
                if (constructor != null && seenMethods.add(CreationCalls.key(node, constructor))) {
                    addConstructorCallee(node, constructor, callees, ast, filePath, service);
                }
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation node) {
                IMethodBinding binding = node.resolveConstructorBinding();
                if (binding != null) {
                    String key = binding.getKey();
                    if (seenMethods.add(key)) {
                        addThisConstructorCallee(node, binding, callees, ast, filePath, service);
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SuperConstructorInvocation node) {
                IMethodBinding binding = node.resolveConstructorBinding();
                if (binding != null) {
                    String key = binding.getKey();
                    if (seenMethods.add(key)) {
                        addSuperConstructorCallee(node, binding, callees, ast, filePath, service);
                    }
                }
                return true;
            }

            // Method references (Foo::method, instance::method, super::method, Foo::new)
            // are deferred-invocation dispatch sites: at the position of the reference,
            // the method is not called, but at the position where the functional
            // interface is invoked, this method runs. From the call-hierarchy
            // perspective, the referenced method IS a callee of the enclosing
            // method — the dispatch graph includes it.
            @Override
            public boolean visit(ExpressionMethodReference node) {
                addMethodReferenceCallee(node, callees, seenMethods, ast, filePath, service);
                return true;
            }

            @Override
            public boolean visit(TypeMethodReference node) {
                addMethodReferenceCallee(node, callees, seenMethods, ast, filePath, service);
                return true;
            }

            @Override
            public boolean visit(SuperMethodReference node) {
                addMethodReferenceCallee(node, callees, seenMethods, ast, filePath, service);
                return true;
            }

            @Override
            public boolean visit(CreationReference node) {
                addMethodReferenceCallee(node, callees, seenMethods, ast, filePath, service);
                return true;
            }
        });

        return callees;
    }

    private void addMethodReferenceCallee(MethodReference node,
                                          List<Map<String, Object>> callees,
                                          Set<String> seenMethods, CompilationUnit ast,
                                          Path filePath, IJdtService service) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (binding == null) return;
        String key = binding.getKey();
        if (!seenMethods.add(key)) return;
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", binding.getName());
        ITypeBinding declaring = binding.getDeclaringClass();
        callee.put("declaringClass", declaring != null ? ElementNames.qualifiedName(declaring) : "Unknown");
        callee.put("signature", buildSignature(binding));
        ITypeBinding returnType = binding.getReturnType();
        callee.put("returnType", returnType != null ? ElementNames.typeExpression(returnType) : "");
        callee.put("callType", "METHOD_REFERENCE");
        callee.put("isFromSource", declaring != null && declaring.isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    private void addMethodCallee(MethodInvocation node, IMethodBinding binding,
                                  List<Map<String, Object>> callees, CompilationUnit ast,
                                  Path filePath, IJdtService service, String callType) {
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", binding.getName());
        callee.put("declaringClass", ElementNames.qualifiedName(binding.getDeclaringClass()));
        callee.put("signature", buildSignature(binding));
        callee.put("returnType", ElementNames.typeExpression(binding.getReturnType()));
        callee.put("callType", callType);
        callee.put("isFromSource", binding.getDeclaringClass().isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    private void addSuperMethodCallee(SuperMethodInvocation node, IMethodBinding binding,
                                       List<Map<String, Object>> callees, CompilationUnit ast,
                                       Path filePath, IJdtService service) {
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", binding.getName());
        callee.put("declaringClass", ElementNames.qualifiedName(binding.getDeclaringClass()));
        callee.put("signature", buildSignature(binding));
        callee.put("returnType", ElementNames.typeExpression(binding.getReturnType()));
        callee.put("callType", "SUPER_METHOD");
        callee.put("isFromSource", binding.getDeclaringClass().isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    /**
     * The call of {@code binding}, the constructor {@code node} runs (CreationCalls): the
     * superclass constructor for an anonymous class, whose returned type is the anonymous class.
     */
    private void addConstructorCallee(ClassInstanceCreation node, IMethodBinding binding,
                                       List<Map<String, Object>> callees, CompilationUnit ast,
                                       Path filePath, IJdtService service) {
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", "<init>");
        callee.put("declaringClass", ElementNames.qualifiedName(binding.getDeclaringClass()));
        callee.put("signature", "new " + ElementNames.typeExpression(binding.getDeclaringClass()) + buildParamList(binding));
        callee.put("returnType", ElementNames.typeExpression(CreationCalls.createdClass(node, binding)));
        callee.put("callType", "CONSTRUCTOR");
        callee.put("isFromSource", binding.getDeclaringClass().isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    private void addThisConstructorCallee(ConstructorInvocation node, IMethodBinding binding,
                                           List<Map<String, Object>> callees, CompilationUnit ast,
                                           Path filePath, IJdtService service) {
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", "<init>");
        callee.put("declaringClass", ElementNames.qualifiedName(binding.getDeclaringClass()));
        callee.put("signature", "this" + buildParamList(binding));
        callee.put("callType", "THIS_CONSTRUCTOR");
        callee.put("isFromSource", binding.getDeclaringClass().isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    private void addSuperConstructorCallee(SuperConstructorInvocation node, IMethodBinding binding,
                                            List<Map<String, Object>> callees, CompilationUnit ast,
                                            Path filePath, IJdtService service) {
        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("method", "<init>");
        callee.put("declaringClass", ElementNames.qualifiedName(binding.getDeclaringClass()));
        callee.put("signature", "super" + buildParamList(binding));
        callee.put("callType", "SUPER_CONSTRUCTOR");
        callee.put("isFromSource", binding.getDeclaringClass().isFromSource());
        callee.put("callLocation", buildCallLocation(node, ast, filePath, service));
        callees.add(callee);
    }

    private Map<String, Object> buildCallLocation(ASTNode node, CompilationUnit ast,
                                                   Path filePath, IJdtService service) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("filePath", service.getPathUtils().formatPath(filePath));
        location.put("line", ast.getLineNumber(node.getStartPosition()) - 1); // 0-based
        location.put("column", ast.getColumnNumber(node.getStartPosition()));
        return location;
    }

    private String buildSignature(IMethodBinding method) {
        StringBuilder sig = new StringBuilder();
        sig.append(method.getName()).append("(");
        ITypeBinding[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(ElementNames.typeExpression(params[i]));
        }
        sig.append(")");
        return sig.toString();
    }

    private String buildParamList(IMethodBinding method) {
        StringBuilder sig = new StringBuilder("(");
        ITypeBinding[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(ElementNames.typeExpression(params[i]));
        }
        sig.append(")");
        return sig.toString();
    }
}
