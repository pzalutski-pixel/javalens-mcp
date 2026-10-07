package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
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

            // The method at the position or enclosing it. A lambda expression is no member of
            // its own: a position in it belongs to the nearest enclosing method with a name.
            IMethod method = ElementNames.enclosingMethod(element);

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

    /**
     * The method {@code method} overrides as the compiler binds it ({@code Bindings.findOverriddenMethod}
     * through SearchService.hierarchyOf): the nearest declaration in the superclass chain, else in
     * the interfaces, visible from its package; null for a static or private method, a constructor
     * and a method overriding none.
     */
    private IMethod findSuperMethod(IMethod method, IJdtService service) throws CoreException {
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) {
            return null;
        }
        return service.getSearchService().hierarchyOf(declaringType).overridden(method);
    }

    private Map<String, Object> createMethodInfo(IMethod method, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", method.getElementName());

        IType declaringType = method.getDeclaringType();
        if (declaringType != null) {
            info.put("declaringType", declaringType.getFullyQualifiedName());
        }

        info.put("signature", MethodFormatter.signature(method));

        info.put("modifiers", ModifierFormatter.format(method.getFlags()));

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

}
