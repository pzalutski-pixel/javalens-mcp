package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
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
 * Get type information at a specific position.
 */
public class GetTypeAtPositionTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetTypeAtPositionTool.class);

    public GetTypeAtPositionTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_type_at_position";
    }

    @Override
    public String getDescription() {
        return """
            Get type information at a specific position.

            USAGE: Position on a type reference or declaration
            OUTPUT: Type details including kind, modifiers, superclass, interfaces

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
            IType type = service.getTypeAtPosition(path, line, column);

            if (type == null) {
                // Try getting element and finding enclosing type
                IJavaElement element = service.getElementAtPosition(path, line, column);
                if (element != null) {
                    type = (IType) element.getAncestor(IJavaElement.TYPE);
                }
            }

            if (type == null) {
                return ToolResponse.symbolNotFound("No type found at position");
            }

            Map<String, Object> data = createTypeInfo(type, service);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_type_hierarchy for inheritance chain",
                    "get_type_members for all members",
                    "find_implementations for implementors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting type at position: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());

        if (type.isInterface()) {
            info.put("kind", "Interface");
        } else if (type.isEnum()) {
            info.put("kind", "Enum");
        } else if (type.isAnnotation()) {
            info.put("kind", "Annotation");
        } else if (type.isRecord()) {
            info.put("kind", "Record");
        } else {
            info.put("kind", "Class");
        }

        int flags = type.getFlags();
        info.put("modifiers", getModifiers(flags));

        String superclass = type.getSuperclassName();
        if (superclass != null) {
            info.put("superclass", superclass);
        }

        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces.length > 0) {
            info.put("interfaces", List.of(interfaces));
        }

        ITypeParameter[] typeParams = type.getTypeParameters();
        if (typeParams.length > 0) {
            List<String> params = new ArrayList<>();
            for (ITypeParameter tp : typeParams) {
                params.add(tp.getElementName());
            }
            info.put("typeParameters", params);
        }

        IType declaringType = type.getDeclaringType();
        if (declaringType != null) {
            info.put("declaringType", declaringType.getFullyQualifiedName());
            info.put("isNested", true);
        }

        info.put("isAnonymous", type.isAnonymous());
        info.put("isLocal", type.isLocal());

        // Source location
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu != null && cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }

            if (type.getSourceRange() != null) {
                int offset = type.getSourceRange().getOffset();
                info.put("line", service.getLineNumber(cu, offset));
            }
        }

        // Member counts
        info.put("methodCount", type.getMethods().length);
        info.put("fieldCount", type.getFields().length);
        info.put("nestedTypeCount", type.getTypes().length);

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
        return modifiers;
    }
}
