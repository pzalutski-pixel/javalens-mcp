package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Signature;
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
 * Find the method that this method overrides or implements.
 */
public class GetSuperMethodTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetSuperMethodTool.class);

    public GetSuperMethodTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_super_method";
    }

    @Override
    public String getDescription() {
        return """
            Find the method that this method overrides or implements.

            USAGE: Position on a method that overrides/implements another
            OUTPUT: The superclass/interface method being overridden

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
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            )
        ));
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

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            IMethod method = null;
            if (element instanceof IMethod m) {
                method = m;
            } else {
                method = (IMethod) element.getAncestor(IJavaElement.METHOD);
            }

            if (method == null) {
                return ToolResponse.symbolNotFound("No method found at position");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", createMethodInfo(method, service));

            // Find overridden method
            IMethod superMethod = findSuperMethod(method, service);

            if (superMethod != null) {
                data.put("overrides", createMethodInfo(superMethod, service));

                IType superType = superMethod.getDeclaringType();
                if (superType != null) {
                    data.put("implementsInterface", superType.isInterface());
                }
            } else {
                data.put("overrides", null);
                data.put("message", "This method does not override or implement any method");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_implementations to find overriding methods",
                    "get_type_hierarchy for inheritance chain"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting super method: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private IMethod findSuperMethod(IMethod method, IJdtService service) throws JavaModelException, CoreException {
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) {
            return null;
        }

        String methodName = method.getElementName();
        String[] paramTypes = method.getParameterTypes();

        // Get type hierarchy
        ITypeHierarchy hierarchy = service.getSearchService().getTypeHierarchy(declaringType);

        // Check superclasses first
        IType[] superclasses = hierarchy.getAllSuperclasses(declaringType);
        for (IType superclass : superclasses) {
            IMethod superMethod = findMatchingMethod(superclass, methodName, paramTypes);
            if (superMethod != null) {
                return superMethod;
            }
        }

        // Check interfaces
        IType[] interfaces = hierarchy.getAllSuperInterfaces(declaringType);
        for (IType iface : interfaces) {
            IMethod superMethod = findMatchingMethod(iface, methodName, paramTypes);
            if (superMethod != null) {
                return superMethod;
            }
        }

        return null;
    }

    private IMethod findMatchingMethod(IType type, String methodName, String[] paramTypes) throws JavaModelException {
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(methodName)) {
                continue;
            }

            String[] mParamTypes = m.getParameterTypes();
            if (mParamTypes.length != paramTypes.length) {
                continue;
            }

            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                // Compare simplified type names
                String t1 = Signature.getSimpleName(Signature.toString(paramTypes[i]));
                String t2 = Signature.getSimpleName(Signature.toString(mParamTypes[i]));
                if (!t1.equals(t2)) {
                    match = false;
                    break;
                }
            }

            if (match) {
                return m;
            }
        }
        return null;
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());

        IType declaringType = method.getDeclaringType();
        if (declaringType != null) {
            info.put("declaringType", declaringType.getFullyQualifiedName());
        }

        // Build signature
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

        info.put("modifiers", getModifiers(method.getFlags()));

        // Source location
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu != null && cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }

            if (method.getSourceRange() != null) {
                info.put("line", service.getLineNumber(cu, method.getSourceRange().getOffset()));
            }
        }

        return info;
    }

    private List<String> getModifiers(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        if (Flags.isDefaultMethod(flags)) modifiers.add("default");
        return modifiers;
    }
}
