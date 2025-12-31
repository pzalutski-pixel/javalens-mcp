package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.search.SearchMatch;
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

            Combines:
            - Method info (signature, modifiers, return type)
            - Parameters with types
            - Declared exceptions
            - Incoming calls (who calls this method)
            - Outgoing calls (what this method calls)
            - Override information (super method, overriding methods)

            Use this instead of multiple calls to get_method_at_position + call hierarchy tools.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file"
        ));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number"
        ));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number"
        ));
        properties.put("maxCallers", Map.of(
            "type", "integer",
            "description", "Maximum callers to return (default 20)"
        ));
        properties.put("maxCallees", Map.of(
            "type", "integer",
            "description", "Maximum callees to return (default 50)"
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return schema;
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

            // Outgoing calls (callees)
            data.put("callees", findCallees(method, cu, service, maxCallees));

            // Override info
            data.put("overrides", findOverrideInfo(method, service));

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
        info.put("modifiers", getModifiers(method.getFlags()));

        // Declaring type
        IType declaringType = method.getDeclaringType();
        info.put("declaringType", declaringType.getFullyQualifiedName());

        // Signature
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
            if (i < paramNames.length) {
                sig.append(" ").append(paramNames[i]);
            }
        }
        sig.append(")");
        if (!method.isConstructor()) {
            sig.append(": ").append(Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }
        info.put("signature", sig.toString());

        if (!method.isConstructor()) {
            info.put("returnType", Signature.getSimpleName(Signature.toString(method.getReturnType())));
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
            List<SearchMatch> references = service.getSearchService()
                .findAllReferences(method, maxCallers * 2);

            Set<String> seenCallers = new HashSet<>();

            for (SearchMatch match : references) {
                if (callers.size() >= maxCallers) break;

                Object element = match.getElement();
                if (element instanceof IJavaElement javaElement) {
                    IMethod enclosingMethod = (IMethod) javaElement.getAncestor(IJavaElement.METHOD);
                    if (enclosingMethod != null) {
                        String key = enclosingMethod.getHandleIdentifier();
                        if (!seenCallers.contains(key)) {
                            seenCallers.add(key);

                            Map<String, Object> caller = new LinkedHashMap<>();
                            caller.put("method", enclosingMethod.getElementName());
                            caller.put("type", enclosingMethod.getDeclaringType().getElementName());
                            caller.put("qualifiedType", enclosingMethod.getDeclaringType().getFullyQualifiedName());

                            // Call site location
                            ICompilationUnit callerCu = enclosingMethod.getCompilationUnit();
                            if (callerCu != null && callerCu.getResource() != null) {
                                caller.put("file", service.getPathUtils().formatPath(
                                    callerCu.getResource().getLocation().toOSString()));
                                caller.put("callLine", service.getLineNumber(callerCu, match.getOffset()));
                            }

                            callers.add(caller);
                        }
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

    private Map<String, Object> findCallees(IMethod method, ICompilationUnit cu, IJdtService service, int maxCallees) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> callees = new ArrayList<>();

        try {
            // Parse AST to find callees
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);

            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            if (ast == null) {
                result.put("count", 0);
                result.put("list", callees);
                return result;
            }

            // Find method declaration
            int offset = method.getSourceRange().getOffset();
            NodeFinder finder = new NodeFinder(ast, offset, 0);
            ASTNode coveringNode = finder.getCoveringNode();
            MethodDeclaration methodDecl = findEnclosingMethod(coveringNode);

            if (methodDecl != null) {
                Set<String> seenCallees = new HashSet<>();

                methodDecl.accept(new ASTVisitor() {
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
                        IMethodBinding binding = node.resolveConstructorBinding();
                        if (binding != null) {
                            addCallee(binding, "CONSTRUCTOR", binding.getDeclaringClass().getName(),
                                ast.getLineNumber(node.getStartPosition()), seenCallees, callees);
                        }
                        return true;
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

        String key = binding.getKey();
        if (seen.contains(key)) return;
        seen.add(key);

        Map<String, Object> callee = new LinkedHashMap<>();
        callee.put("name", name);
        callee.put("kind", kind);

        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass != null) {
            callee.put("declaringType", declaringClass.getName());
            callee.put("qualifiedType", declaringClass.getQualifiedName());
        }

        // Build signature
        StringBuilder sig = new StringBuilder(name).append("(");
        ITypeBinding[] paramTypes = binding.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes[i].getName());
        }
        sig.append(")");
        callee.put("signature", sig.toString());

        callee.put("callLine", line - 1); // Convert to 0-based

        callees.add(callee);
    }

    private Map<String, Object> findOverrideInfo(IMethod method, IJdtService service) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Find super method
            IMethod superMethod = findSuperMethod(method);
            if (superMethod != null) {
                Map<String, Object> superInfo = new LinkedHashMap<>();
                superInfo.put("method", superMethod.getElementName());
                superInfo.put("type", superMethod.getDeclaringType().getElementName());
                superInfo.put("qualifiedType", superMethod.getDeclaringType().getFullyQualifiedName());
                result.put("overrides", superInfo);
            }

            // Find overriding methods
            List<SearchMatch> overriders = service.getSearchService()
                .findOverridingMethods(method, 20);
            if (!overriders.isEmpty()) {
                List<Map<String, Object>> overriderList = new ArrayList<>();
                for (SearchMatch match : overriders) {
                    if (match.getElement() instanceof IMethod overrider) {
                        if (!overrider.equals(method)) { // Exclude self
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("method", overrider.getElementName());
                            info.put("type", overrider.getDeclaringType().getElementName());
                            info.put("qualifiedType", overrider.getDeclaringType().getFullyQualifiedName());
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

    private IMethod findSuperMethod(IMethod method) {
        try {
            IType declaringType = method.getDeclaringType();
            String methodName = method.getElementName();
            String[] paramTypes = method.getParameterTypes();

            // Check superclass
            String superclassName = declaringType.getSuperclassName();
            if (superclassName != null) {
                String[][] resolvedSuper = declaringType.resolveType(superclassName);
                if (resolvedSuper != null && resolvedSuper.length > 0) {
                    IType superType = method.getJavaProject().findType(
                        resolvedSuper[0][0] + "." + resolvedSuper[0][1]);
                    if (superType != null) {
                        IMethod superMethod = superType.getMethod(methodName, paramTypes);
                        if (superMethod.exists()) {
                            return superMethod;
                        }
                    }
                }
            }

            // Check interfaces
            String[] interfaceNames = declaringType.getSuperInterfaceNames();
            for (String interfaceName : interfaceNames) {
                String[][] resolved = declaringType.resolveType(interfaceName);
                if (resolved != null && resolved.length > 0) {
                    IType interfaceType = method.getJavaProject().findType(
                        resolved[0][0] + "." + resolved[0][1]);
                    if (interfaceType != null) {
                        IMethod interfaceMethod = interfaceType.getMethod(methodName, paramTypes);
                        if (interfaceMethod.exists()) {
                            return interfaceMethod;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error finding super method: {}", e.getMessage());
        }

        return null;
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

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        return modifiers;
    }
}
