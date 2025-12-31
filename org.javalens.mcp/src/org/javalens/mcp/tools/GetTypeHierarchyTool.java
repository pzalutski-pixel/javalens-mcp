package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
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
 * Get the type hierarchy (supertypes and subtypes) for a Java type.
 * Uses JDT's ITypeHierarchy for fast, indexed hierarchy traversal.
 */
public class GetTypeHierarchyTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetTypeHierarchyTool.class);

    public GetTypeHierarchyTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_type_hierarchy";
    }

    @Override
    public String getDescription() {
        return """
            Get the type hierarchy (supertypes and subtypes) for a Java type.

            USAGE: Position on a type, returns full inheritance chain
            OUTPUT: Superclasses, interfaces, and all subtypes

            Can be called with either:
            - File position (filePath, line, column) - finds type at cursor
            - Type name (typeName) - looks up type by qualified name

            IMPORTANT: Uses ZERO-BASED coordinates when using file position.

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
                "description", "Path to source file (for position-based lookup)"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "typeName", Map.of(
                "type", "string",
                "description", "Fully qualified type name (alternative to position)"
            ),
            "maxDepth", Map.of(
                "type", "integer",
                "description", "Maximum depth of hierarchy to return (default 10)"
            )
        ));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String typeName = getStringParam(arguments, "typeName");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        int maxDepth = getIntParam(arguments, "maxDepth", 10);

        maxDepth = Math.min(Math.max(maxDepth, 1), 50);

        try {
            IType targetType = null;

            // Try to find type by position first
            if (filePath != null && !filePath.isBlank() && line >= 0 && column >= 0) {
                Path path = Path.of(filePath);
                targetType = service.getTypeAtPosition(path, line, column);

                if (targetType == null) {
                    IJavaElement element = service.getElementAtPosition(path, line, column);
                    if (element != null) {
                        targetType = (IType) element.getAncestor(IJavaElement.TYPE);
                    }
                }
            }

            // Fall back to type name lookup
            if (targetType == null && typeName != null && !typeName.isBlank()) {
                targetType = service.findType(typeName);
            }

            if (targetType == null) {
                return ToolResponse.symbolNotFound("No type found at position or by name");
            }

            // Get type hierarchy
            ITypeHierarchy hierarchy = service.getSearchService().getTypeHierarchy(targetType);

            // Build response data
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", createTypeInfo(targetType, service));

            // Get superclasses
            IType[] superclasses = hierarchy.getAllSuperclasses(targetType);
            List<Map<String, Object>> superclassList = new ArrayList<>();
            for (IType superclass : superclasses) {
                if (superclassList.size() >= maxDepth) break;
                superclassList.add(createTypeInfo(superclass, service));
            }
            data.put("superclasses", superclassList);

            // Get implemented interfaces
            IType[] interfaces = hierarchy.getAllSuperInterfaces(targetType);
            List<Map<String, Object>> interfaceList = new ArrayList<>();
            for (IType iface : interfaces) {
                if (interfaceList.size() >= maxDepth) break;
                interfaceList.add(createTypeInfo(iface, service));
            }
            data.put("interfaces", interfaceList);

            // Get subtypes
            IType[] subtypes = hierarchy.getAllSubtypes(targetType);
            List<Map<String, Object>> subtypeList = new ArrayList<>();
            for (IType subtype : subtypes) {
                if (subtypeList.size() >= maxDepth) break;
                subtypeList.add(createTypeInfo(subtype, service));
            }
            data.put("subtypes", subtypeList);

            data.put("totalSuperclasses", superclasses.length);
            data.put("totalInterfaces", interfaces.length);
            data.put("totalSubtypes", subtypes.length);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(superclasses.length + interfaces.length + subtypes.length)
                .returnedCount(superclassList.size() + interfaceList.size() + subtypeList.size())
                .truncated(superclassList.size() < superclasses.length ||
                          interfaceList.size() < interfaces.length ||
                          subtypeList.size() < subtypes.length)
                .suggestedNextTools(List.of(
                    "get_type_members to see members of a specific type",
                    "find_implementations to find all implementors",
                    "find_references to see where type is used"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting type hierarchy: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeInfo(IType type, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", type.getElementName());
        info.put("qualifiedName", type.getFullyQualifiedName());

        try {
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
        } catch (JavaModelException e) {
            info.put("kind", "Class");
        }

        try {
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu != null && cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }

                int offset = type.getSourceRange().getOffset();
                int lineNum = service.getLineNumber(cu, offset);
                info.put("line", lineNum);
            }
        } catch (Exception e) {
            info.put("external", true);
        }

        return info;
    }
}
