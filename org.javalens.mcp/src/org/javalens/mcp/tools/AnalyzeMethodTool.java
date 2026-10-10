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
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
import org.javalens.core.search.ModelHierarchy;
import org.javalens.core.search.SearchResult;
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

            callers.count and callees.count are the exact numbers of distinct
            callers and callees; each list holds the first maxCallers (default 20)
            or maxCallees (default 50) of them, callers in search order and callees
            in source order. meta.totalCount counts both, meta.returnedCount those
            listed, and meta.truncated is true when either list is cut. A negative
            maxCallers or maxCallees is INVALID_PARAMETER.

            The method is the one the position names, wherever it is declared: its
            file and line are those of its declaration, and its callees are read
            there. A method of a class file reports no file, line or callees (there
            is no source to read them from); its callers are those in the project's
            source files. callers.potentialMatches holds the references JDT could not
            confirm, the first maxCallers of them; overrides.potentialMatches the
            places naming the declaring type, or a subtype, as a supertype that JDT
            could not confirm. overrides.unresolvedSupertypes, present only when
            there are any, names each supertype of the declaring type the compiler
            cannot resolve, with the type whose declaration names it: the method may
            override a method of one of them.

            Use this instead of multiple calls to get_method_at_position + call hierarchy tools.

            IMPORTANT: Uses ZERO-BASED coordinates.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file")
            .required("line", "integer", "Zero-based line number")
            .required("column", "integer", "Zero-based column number")
            .optional("maxCallers", "integer", "Maximum callers listed (default 20); callers.count counts all")
            .optional("maxCallees", "integer", "Maximum callees listed (default 50); callees.count counts all")
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
        if (maxCallers < 0) {
            return ToolResponse.invalidParameter("maxCallers", "Must be >= 0; got: " + maxCallers);
        }
        if (maxCallees < 0) {
            return ToolResponse.invalidParameter("maxCallees", "Must be >= 0; got: " + maxCallees);
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

            // Method info, where the method is declared
            data.put("method", createMethodInfo(method, service));

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

            // Incoming calls (callers), and the references the search could not confirm
            SearchResult references = service.getSearchService().findAllReferences(method, SearchService.ALL_MATCHES);
            List<Map<String, Object>> callers = findCallers(references, service);
            Map<String, Object> callerBlock = calls(callers, maxCallers);
            List<SearchMatch> potential = references.potentialMatches();
            Map<String, Object> potentialCallers = potentialMatches(
                potential.subList(0, Math.min(maxCallers, potential.size())), references.potentialTotal(), service);
            if (potentialCallers != null) {
                callerBlock.put("potentialMatches", potentialCallers);
            }
            data.put("callers", callerBlock);

            // Outgoing calls (callees), read from the method's own declaration; a method of a
            // class file has no source to read them from.
            List<Map<String, Object>> callees = List.of();
            if (method.getCompilationUnit() != null) {
                callees = findCallees(findDeclaration(method));
                data.put("callees", calls(callees, maxCallees));
            }

            // Override info
            data.put("overrides", findOverrideInfo(method, service));

            int total = callers.size() + callees.size();
            int returned = listed(callers, maxCallers).size() + listed(callees, maxCallees).size();
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned)
                .truncated(returned < total)
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

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service)
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

        // Location: the file and line of the declaration; none for a method of a class file, as
        // get_super_method and get_method_at_position render one (no compilation unit)
        ICompilationUnit declaring = method.getCompilationUnit();
        if (declaring != null && declaring.getResource() != null) {
            info.put("file", service.getPathUtils().formatPath(
                declaring.getResource().getLocation().toOSString()));
            info.put("line", service.getLineNumber(declaring, method.getSourceRange().getOffset()));
        }

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

    /** A callers or callees block: the count of all of them and the first {@code max} listed. */
    private static Map<String, Object> calls(List<Map<String, Object>> all, int max) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", all.size());
        result.put("list", listed(all, max));
        return result;
    }

    /** The first {@code max} of {@code all}, {@code max} not negative. */
    private static List<Map<String, Object>> listed(List<Map<String, Object>> all, int max) {
        return all.subList(0, Math.min(max, all.size()));
    }

    /** Every distinct caller in {@code found}, every reference to the method, in search order. */
    private List<Map<String, Object>> findCallers(SearchResult found, IJdtService service) {
        List<Map<String, Object>> callers = new ArrayList<>();

        try {
            // Every reference: callers are the distinct enclosing methods, and each is
            // counted however many references it holds.
            List<SearchMatch> references = found.matches();

            Set<String> seenCallers = new HashSet<>();

            for (SearchMatch match : references) {
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

        return callers;
    }

    /**
     * The declaration of {@code method}, a method with source, in its own compilation unit,
     * parsed with bindings, or null when the unit does not parse.
     */
    private MethodDeclaration findDeclaration(IMethod method) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(method.getCompilationUnit());
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);

            CompilationUnit ast = (CompilationUnit) parser.createAST(Deadline.monitor());
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
     * the method. Each distinct callee once, in source order.
     */
    private List<Map<String, Object>> findCallees(MethodDeclaration methodDecl) {
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
                        addCallee(node.resolveMethodBinding(), "METHOD", node.getName().toString(),
                            ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        return true;
                    }

                    @Override
                    public boolean visit(SuperMethodInvocation node) {
                        addCallee(node.resolveMethodBinding(), "SUPER_METHOD", node.getName().toString(),
                            ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        return true;
                    }

                    @Override
                    public boolean visit(ClassInstanceCreation node) {
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
                        IMethodBinding binding = node.resolveConstructorBinding();
                        if (binding != null) {
                            addCallee(binding, "THIS_CONSTRUCTOR", ElementNames.typeExpression(binding.getDeclaringClass()),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(SuperConstructorInvocation node) {
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

        return callees;
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

    /**
     * The method {@code method} overrides and the methods overriding it, as the compiler binds
     * them over the hierarchy the Java model holds (SearchService.hierarchyOf): the nearest
     * declaration it overrides in its superclass chain, else in its interfaces, visible from its
     * package ({@code Bindings.findOverriddenMethod}, which JDT's own quick fixes use); and every
     * method of a subtype that overrides it, by file and position, in local and anonymous classes
     * in and after lambda bodies too. The binding knows an anonymous class's supertype and a type
     * parameter's argument ({@code compareTo(String)} overrides {@code Comparable<T>.compareTo(T)}).
     * A static or private method or a constructor overrides nothing and is overridden by nothing.
     * A lambda expression is no member of its own: its method answers for the nearest enclosing
     * method with a name.
     */
    private Map<String, Object> findOverrideInfo(IMethod method, IJdtService service) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            IMethod subject = ElementNames.enclosingMethod(method);
            ModelHierarchy hierarchy = service.getSearchService().hierarchyOf(subject.getDeclaringType());

            IMethod overridden = hierarchy.overridden(subject);
            if (overridden != null) {
                Map<String, Object> superInfo = new LinkedHashMap<>();
                superInfo.put("method", overridden.getElementName());
                superInfo.put("qualifiedType", ElementNames.qualifiedName(overridden.getDeclaringType()));
                result.put("overrides", superInfo);
            }

            List<IMethod> overriders = hierarchy.overriding(subject);
            if (!overriders.isEmpty()) {
                List<Map<String, Object>> overriderList = new ArrayList<>();
                for (IMethod overrider : overriders) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("method", overrider.getElementName());
                    info.put("qualifiedType", ElementNames.qualifiedName(overrider.getDeclaringType()));
                    overriderList.add(info);
                }
                result.put("overriddenBy", overriderList);
            }
            // A constructor, a static and a private method override nothing and are overridden by
            // nothing: a type that may be a subtype, or a supertype that does not resolve, cannot
            // change that.
            int flags = subject.getFlags();
            if (!subject.isConstructor() && !Flags.isStatic(flags) && !Flags.isPrivate(flags)) {
                Map<String, Object> potential = potentialSubtypes(hierarchy, Integer.MAX_VALUE, service);
                if (potential != null) {
                    result.put("potentialMatches", potential);
                }
                List<Map<String, Object>> unresolved = unresolvedSupertypes(hierarchy);
                if (unresolved != null) {
                    result.put("unresolvedSupertypes", unresolved);
                }
            }
        } catch (Exception e) {
            log.debug("Error finding override info: {}", e.getMessage());
        }

        return result;
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
