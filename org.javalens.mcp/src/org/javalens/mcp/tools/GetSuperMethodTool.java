package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.core.MethodFormatter;
import org.javalens.core.ModifierFormatter;
import org.javalens.core.search.ModelHierarchy;
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

            The overridden method is the one the compiler binds: the nearest
            declaration in the superclass chain, else in the interfaces, visible
            from the method's package. unresolvedSupertypes, present only when
            there are any, names each supertype of the declaring type the compiler
            cannot resolve (a missing dependency), as the declaration writes it,
            with the type whose declaration names it: the method may override a
            method of one of them.

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

            // Find overridden method, over the hierarchy the Java model holds
            ModelHierarchy hierarchy = method.getDeclaringType() == null ? null
                : service.getSearchService().hierarchyOf(method.getDeclaringType());
            IMethod superMethod = hierarchy == null ? null : hierarchy.overridden(method);
            // A constructor, a static and a private method override nothing, whatever supertype
            // does not resolve.
            boolean overrides = !method.isConstructor() && !Flags.isStatic(method.getFlags())
                && !Flags.isPrivate(method.getFlags());
            List<Map<String, Object>> unresolved = hierarchy == null || !overrides ? null
                : unresolvedSupertypes(hierarchy);

            if (superMethod != null) {
                data.put("overrides", createMethodInfo(superMethod, service));

                IType superType = superMethod.getDeclaringType();
                if (superType != null) {
                    data.put("implementsInterface", superType.isInterface());
                }
            } else {
                data.put("overrides", null);
                data.put("message", unresolved == null
                    ? "This method does not override or implement any method"
                    : "This method does not override or implement a method of a supertype that resolves; "
                        + "the supertypes in unresolvedSupertypes do not resolve");
            }
            if (unresolved != null) {
                data.put("unresolvedSupertypes", unresolved);
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
