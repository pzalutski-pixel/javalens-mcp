package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
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
 * Get method information at a specific position.
 */
public class GetMethodAtPositionTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetMethodAtPositionTool.class);

    public GetMethodAtPositionTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_method_at_position";
    }

    @Override
    public String getDescription() {
        return """
            Get method information at a specific position.

            USAGE: Position on a method reference or declaration
            OUTPUT: Method signature, parameters, return type, modifiers, exceptions

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

            Map<String, Object> data = createMethodInfo(method, service);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to find all callers",
                    "get_call_hierarchy_incoming for call hierarchy",
                    "get_super_method for overridden method"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting method at position: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", method.getElementName());
        info.put("isConstructor", method.isConstructor());

        int flags = method.getFlags();
        info.put("modifiers", getModifiers(flags));

        // Return type
        if (!method.isConstructor()) {
            info.put("returnType", Signature.getSimpleName(Signature.toString(method.getReturnType())));
        }

        // Parameters
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        List<Map<String, String>> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.length; i++) {
            Map<String, String> param = new LinkedHashMap<>();
            param.put("type", Signature.getSimpleName(Signature.toString(paramTypes[i])));
            if (i < paramNames.length) {
                param.put("name", paramNames[i]);
            }
            params.add(param);
        }
        info.put("parameters", params);
        info.put("parameterCount", params.size());

        // Type parameters
        ITypeParameter[] typeParams = method.getTypeParameters();
        if (typeParams.length > 0) {
            List<String> tParams = new ArrayList<>();
            for (ITypeParameter tp : typeParams) {
                tParams.add(tp.getElementName());
            }
            info.put("typeParameters", tParams);
        }

        // Exceptions
        String[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0) {
            List<String> exList = new ArrayList<>();
            for (String ex : exceptions) {
                exList.add(Signature.getSimpleName(Signature.toString(ex)));
            }
            info.put("exceptions", exList);
        }

        // Declaring type
        IType declaringType = method.getDeclaringType();
        if (declaringType != null) {
            info.put("declaringType", declaringType.getFullyQualifiedName());
        }

        // Build full signature
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");
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

        // Additional flags
        info.put("isMainMethod", method.isMainMethod());

        // Source location
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu != null && cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }

            if (method.getSourceRange() != null) {
                int offset = method.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
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
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags)) modifiers.add("native");
        if (Flags.isDefaultMethod(flags)) modifiers.add("default");
        return modifiers;
    }
}
