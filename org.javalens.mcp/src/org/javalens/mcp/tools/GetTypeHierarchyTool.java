package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
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
 * Get the type hierarchy (supertypes and subtypes) for a Java type, as the Java model holds it
 * (SearchService.hierarchyOf): local and anonymous classes in and after lambda bodies included,
 * lambda expressions not.
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

            unresolvedSupertypes, present only when there are any, names each
            supertype the compiler cannot resolve (a missing dependency), as the
            declaration writes it, with the type whose declaration names it: this
            type or one of its supertypes. The superclasses then run to the last
            that resolves and end with java.lang.Object. potentialMatches are the
            places naming the type, or a subtype, as a supertype that JDT could not
            confirm.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("filePath", "string", "Path to source file (for position-based lookup)")
            .optional("line", "integer", "Zero-based line number")
            .optional("column", "integer", "Zero-based column number")
            .optional("typeName", "string", "Fully qualified type name (alternative to position)")
            .optional("maxDepth", "integer", "Maximum depth of hierarchy to return (default 10)")
            .build();
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
                        targetType = ElementNames.enclosingType(element);
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

            // The hierarchy as the Java model holds it, local and anonymous classes included
            ModelHierarchy hierarchy = service.getSearchService().hierarchyOf(targetType);

            // Build response data
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", createTypeInfo(targetType, service));

            // Superclasses, from the nearest
            List<IType> superclasses = hierarchy.allSuperclasses();
            List<Map<String, Object>> superclassList = new ArrayList<>();
            for (IType superclass : superclasses) {
                if (superclassList.size() >= maxDepth) break;
                superclassList.add(createTypeInfo(superclass, service));
            }
            data.put("superclasses", superclassList);

            // Interfaces of every supertype
            List<IType> interfaces = hierarchy.allSuperInterfaces();
            List<Map<String, Object>> interfaceList = new ArrayList<>();
            for (IType iface : interfaces) {
                if (interfaceList.size() >= maxDepth) break;
                interfaceList.add(createTypeInfo(iface, service));
            }
            data.put("interfaces", interfaceList);

            // Supertypes the compiler cannot resolve, by name, with the type naming each
            List<Map<String, Object>> unresolved = unresolvedSupertypes(hierarchy);
            if (unresolved != null) {
                data.put("unresolvedSupertypes", unresolved);
            }

            // Every subtype, by file and position
            List<IType> subtypes = hierarchy.allSubtypes();
            List<Map<String, Object>> subtypeList = new ArrayList<>();
            for (IType subtype : subtypes) {
                if (subtypeList.size() >= maxDepth) break;
                subtypeList.add(createTypeInfo(subtype, service));
            }
            data.put("subtypes", subtypeList);
            Map<String, Object> potential = potentialSubtypes(hierarchy, maxDepth, service);
            if (potential != null) {
                data.put("potentialMatches", potential);
            }

            data.put("totalSuperclasses", superclasses.size());
            data.put("totalInterfaces", interfaces.size());
            data.put("totalSubtypes", subtypes.size());

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(superclasses.size() + interfaces.size() + subtypes.size())
                .returnedCount(superclassList.size() + interfaceList.size() + subtypeList.size())
                .truncated(superclassList.size() < superclasses.size() ||
                          interfaceList.size() < interfaces.size() ||
                          subtypeList.size() < subtypes.size())
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

    private Map<String, Object> createTypeInfo(IType type, IJdtService service) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("name", ElementNames.typeName(type));
        info.put("qualifiedName", ElementNames.qualifiedName(type));

        info.put("kind", TypeKindResolver.kindOf(type));

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
