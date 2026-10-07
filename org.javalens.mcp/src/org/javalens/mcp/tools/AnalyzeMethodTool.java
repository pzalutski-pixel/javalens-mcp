package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
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
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.javalens.core.IJdtService;
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
import org.javalens.core.search.SearchService;
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
 * Comprehensive method analysis in a single call.
 * Combines method info, parameters, callers, callees, and override information.
 */
public class AnalyzeMethodTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeMethodTool.class);

    public AnalyzeMethodTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_method";
    }

    @Override
    public String getDescription() {
        return """
            Comprehensive method analysis in a single call.

            USAGE: analyze_method(filePath="path/to/File.java", line=N, column=N)
            OUTPUT: Method info, parameters, exceptions, callers, callees, override info

            Combines:
            - Method info (signature, modifiers, return type)
            - Parameters with types
            - Declared exceptions
            - Incoming calls (who calls this method)
            - Outgoing calls (what this method calls, in its lambdas included; a
              call in an anonymous or local class declared in it is a call of
              that class's member; creating an anonymous class is a call of the
              superclass constructor its constructor runs)
            - Override information (super method, overriding methods)

            Use this instead of multiple calls to get_method_at_position + call hierarchy tools.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("maxCallers", "integer", "Maximum callers to return (default 20)")
            .optional("maxCallees", "integer", "Maximum callees to return (default 50)")
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
        int maxCallers = getIntParam(arguments, "maxCallers", 20);
        int maxCallees = getIntParam(arguments, "maxCallees", 50);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position", "Position is not on a method");
            }

            Map<String, Object> data = new LinkedHashMap<>();

            // Method info
            data.put("method", createMethodInfo(method, service, cu));

            // Parameters
            data.put("parameters", createParameterInfo(method));

            // Exceptions
            String[] exceptions = method.getExceptionTypes();
            if (exceptions.length > 0) {
                List<String> exceptionNames = new ArrayList<>();
                for (String exc : exceptions) {
                    exceptionNames.add(Signature.getSimpleName(Signature.toString(exc)));
                }
                data.put("exceptions", exceptionNames);
            }

            // Incoming calls (callers)
            data.put("callers", findCallers(method, service, maxCallers));

            // The method's declaration, with bindings: its callees and the method it overrides
            // are read from it.
            MethodDeclaration declaration = findDeclaration(method, cu);

            // Outgoing calls (callees)
            data.put("callees", findCallees(declaration, maxCallees));

            // Override info
            data.put("overrides", findOverrideInfo(method, declaration, service));

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "analyze_type for declaring type analysis",
                    "find_references for all usages",
                    "go_to_definition to navigate to a callee"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error analyzing method: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service, ICompilationUnit cu)
            throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());
        info.put("constructor", method.isConstructor());
        info.put("modifiers", ModifierFormatter.format(method.getFlags()));

        // Declaring type
        IType declaringType = method.getDeclaringType();
        info.put("declaringType", ElementNames.qualifiedName(declaringType));

        info.put("signature", MethodFormatter.signature(method));
        String returnType = MethodFormatter.returnTypeSimpleName(method);
        if (returnType != null) {
            info.put("returnType", returnType);
        }

        // Method-level type parameters (the `<T extends Bound>` clause).
        // Without this a consumer sees a generic method as non-generic.
        ITypeParameter[] methodTypeParams = method.getTypeParameters();
        if (methodTypeParams != null && methodTypeParams.length > 0) {
            List<String> tpNames = new ArrayList<>();
            Map<String, List<String>> tpBounds = new LinkedHashMap<>();
            for (ITypeParameter tp : methodTypeParams) {
                tpNames.add(tp.getElementName());
                String[] bounds = tp.getBounds();
                tpBounds.put(tp.getElementName(), bounds != null ? List.of(bounds) : List.of());
            }
            info.put("typeParameters", tpNames);
            info.put("typeParameterBounds", tpBounds);
        }

        // Location
        info.put("file", service.getPathUtils().formatPath(
            cu.getResource().getLocation().toOSString()));
        info.put("line", service.getLineNumber(cu, method.getSourceRange().getOffset()));

        return info;
    }

    private List<Map<String, Object>> createParameterInfo(IMethod method) throws Exception {
        List<Map<String, Object>> params = new ArrayList<>();
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++) {
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("type", Signature.getSimpleName(Signature.toString(paramTypes[i])));
            if (i < paramNames.length) {
                param.put("name", paramNames[i]);
            }
            params.add(param);
        }

        return params;
    }

    private Map<String, Object> findCallers(IMethod method, IJdtService service, int maxCallers) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> callers = new ArrayList<>();

        try {
            // Every reference: callers are the distinct enclosing methods, so the first
            // maxCallers of them can lie past any fixed number of references.
            List<SearchMatch> references = service.getSearchService()
                .findAllReferences(method, SearchService.ALL_MATCHES).matches();

            Set<String> seenCallers = new HashSet<>();

            for (SearchMatch match : references) {
                if (callers.size() >= maxCallers) break;

                // A call is attributed to the member holding it that has a name: a call in a
                // lambda belongs to the method, initializer or field the lambda is written in.
                // An initializer or field initializer calls as <initializer>.
                if (match.getElement() instanceof IJavaElement javaElement
                        && ElementNames.namedMember(javaElement) instanceof IMember caller
                        && !(caller instanceof IType)) {
                    String key = caller.getHandleIdentifier();
                    if (!seenCallers.contains(key)) {
                        seenCallers.add(key);

                        Map<String, Object> callerInfo = new LinkedHashMap<>();
                        callerInfo.put("method", caller instanceof IMethod ? caller.getElementName()
                            : ElementNames.INITIALIZER);
                        callerInfo.put("qualifiedType", ElementNames.qualifiedName(caller.getDeclaringType()));

                        // Call site location
                        ICompilationUnit callerCu = caller.getCompilationUnit();
                        if (callerCu != null && callerCu.getResource() != null) {
                            callerInfo.put("file", service.getPathUtils().formatPath(
                                callerCu.getResource().getLocation().toOSString()));
                            callerInfo.put("callLine", service.getLineNumber(callerCu, match.getOffset()));
                        }

                        callers.add(callerInfo);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error finding callers: {}", e.getMessage());
        }

        result.put("count", callers.size());
        result.put("list", callers);
        return result;
    }

    /**
     * The declaration of {@code method} in {@code cu}, parsed with bindings, or null when the
     * unit does not parse.
     */
    private MethodDeclaration findDeclaration(IMethod method, ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);

            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            if (ast == null) {
                return null;
            }
            int offset = method.getSourceRange().getOffset();
            return findEnclosingMethod(new NodeFinder(ast, offset, 0).getCoveringNode());
        } catch (Exception e) {
            log.debug("Error parsing the declaration of {}: {}", method.getElementName(), e.getMessage());
            return null;
        }
    }

    /**
     * The calls {@code methodDecl} makes itself: those in its body and in the lambdas written in
     * it. A call in the body of an anonymous or local class declared in the method is a call of
     * that class's member; the creation of an anonymous class and its arguments are calls of
     * the method.
     */
    private Map<String, Object> findCallees(MethodDeclaration methodDecl, int maxCallees) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> callees = new ArrayList<>();

        try {
            if (methodDecl != null) {
                CompilationUnit ast = (CompilationUnit) methodDecl.getRoot();
                Set<String> seenCallees = new HashSet<>();

                methodDecl.accept(new ASTVisitor() {
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
                        if (callees.size() >= maxCallees) return false;
                        addCallee(node.resolveMethodBinding(), "METHOD", node.getName().toString(),
                            ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        return true;
                    }

                    @Override
                    public boolean visit(SuperMethodInvocation node) {
                        if (callees.size() >= maxCallees) return false;
                        addCallee(node.resolveMethodBinding(), "SUPER_METHOD", node.getName().toString(),
                            ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        return true;
                    }

                    @Override
                    public boolean visit(ClassInstanceCreation node) {
                        if (callees.size() >= maxCallees) return false;
                        // An anonymous class's creation is a call of the superclass constructor
                        // its constructor runs (CreationCalls).
                        IMethodBinding constructor = CreationCalls.constructorCalled(node);
                        if (constructor != null) {
                            addCallee(CreationCalls.key(node, constructor), constructor, "CONSTRUCTOR",
                                ElementNames.typeExpression(constructor.getDeclaringClass()),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(ConstructorInvocation node) {
                        if (callees.size() >= maxCallees) return false;
                        IMethodBinding binding = node.resolveConstructorBinding();
                        if (binding != null) {
                            addCallee(binding, "THIS_CONSTRUCTOR", ElementNames.typeExpression(binding.getDeclaringClass()),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(SuperConstructorInvocation node) {
                        if (callees.size() >= maxCallees) return false;
                        IMethodBinding binding = node.resolveConstructorBinding();
                        if (binding != null) {
                            addCallee(binding, "SUPER_CONSTRUCTOR", ElementNames.typeExpression(binding.getDeclaringClass()),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                        return true;
                    }

                    // Method references and constructor references are deferred-invocation
                    // dispatch sites. They belong in the callee list as METHOD_REFERENCE.
                    @Override
                    public boolean visit(ExpressionMethodReference node) {
                        visitMethodReference(node);
                        return true;
                    }
                    @Override
                    public boolean visit(TypeMethodReference node) {
                        visitMethodReference(node);
                        return true;
                    }
                    @Override
                    public boolean visit(SuperMethodReference node) {
                        visitMethodReference(node);
                        return true;
                    }
                    @Override
                    public boolean visit(CreationReference node) {
                        visitMethodReference(node);
                        return true;
                    }

                    private void visitMethodReference(MethodReference node) {
                        if (callees.size() >= maxCallees) return;
                        IMethodBinding binding = node.resolveMethodBinding();
                        if (binding != null) {
                            addCallee(binding, "METHOD_REFERENCE", binding.getName(),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.debug("Error finding callees: {}", e.getMessage());
        }

        result.put("count", callees.size());
        result.put("list", callees);
        return result;
    }

    private void addCallee(IMethodBinding binding, String kind, String name, int line,
                          Set<String> seen, List<Map<String, Object>> callees) {
        if (binding == null) return;
        addCallee(binding.getKey(), binding, kind, name, line, seen, callees);
    }

    /** {@code binding}'s call, listed once per {@code key}. */
    private void addCallee(String key, IMethodBinding binding, String kind, String name, int line,
                          Set<String> seen, List<Map<String, Object>> callees) {
        if (seen.contains(key)) return;
        seen.add(key);

        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("name", name);
        callee.put("kind", kind);

        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass != null) {
            callee.put("qualifiedType", ElementNames.qualifiedName(declaringClass));
        }

        // Build signature
        StringBuilder sig = new StringBuilder(name).append("(");
        ITypeBinding[] paramTypes = binding.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(ElementNames.typeExpression(paramTypes[i]));
        }
        sig.append(")");
        callee.put("signature", sig.toString());

        callee.put("callLine", line - 1); // Convert to 0-based

        callees.add(callee);
    }

    private Map<String, Object> findOverrideInfo(IMethod method, MethodDeclaration declaration,
                                                 IJdtService service) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // The method this one overrides, as the compiler binds it
            IMethodBinding overridden = findOverriddenMethod(declaration);
            if (overridden != null) {
                Map<String, Object> superInfo = new LinkedHashMap<>();
                superInfo.put("method", overridden.getName());
                superInfo.put("qualifiedType", ElementNames.qualifiedName(overridden.getDeclaringClass()));
                result.put("overrides", superInfo);
            }

            // Find every overriding method
            List<SearchMatch> overriders = service.getSearchService()
                .findOverridingMethods(method, SearchService.ALL_MATCHES).matches();
            if (!overriders.isEmpty()) {
                List<Map<String, Object>> overriderList = new ArrayList<>();
                for (SearchMatch match : overriders) {
                    if (match.getElement() instanceof IMethod overrider) {
                        if (!overrider.equals(method)) { // Exclude self
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("method", overrider.getElementName());
                            info.put("qualifiedType", ElementNames.qualifiedName(overrider.getDeclaringType()));
                            overriderList.add(info);
                        }
                    }
                }
                if (!overriderList.isEmpty()) {
                    result.put("overriddenBy", overriderList);
                }
            }
        } catch (Exception e) {
            log.debug("Error finding override info: {}", e.getMessage());
        }

        return result;
    }

    /**
     * The method {@code declaration} overrides, as JDT finds it from the compiler's bindings
     * ({@code Bindings.findOverriddenMethod}, which JDT's own quick fixes use to find the method
     * a method overrides): the nearest declaration of a method it overrides in its superclass
     * chain, else in its interfaces, visible from its package. The binding knows an anonymous
     * class's supertype and a type parameter's argument ({@code compareTo(String)} overrides
     * {@code Comparable<T>.compareTo(T)}). A static or private method or a constructor overrides
     * nothing.
     */
    private IMethodBinding findOverriddenMethod(MethodDeclaration declaration) {
        IMethodBinding binding = declaration == null ? null : declaration.resolveBinding();
        return binding == null ? null : Bindings.findOverriddenMethod(binding, true);
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

}
