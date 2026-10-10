package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
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
 * Find implementations of an interface or extensions of a class: every subtype, direct or
 * not, as the Java model holds it (SearchService.hierarchyOf), local and anonymous classes in
 * and after lambda bodies included; for a method, every method of a subtype that overrides it
 * as the compiler binds it, overrides of a method declared with a type parameter included.
 */
public class FindImplementationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindImplementationsTool.class);

    /** The largest {@code maxResults} accepted. */
    private static final int MAX_RESULTS = 1000;

    public FindImplementationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_implementations";
    }

    @Override
    public String getDescription() {
        return """
            Find implementations of an interface or extensions of a class.

            USAGE: Position on a type (interface or class), find all implementors/subclasses
            OUTPUT: List of implementing/extending types with locations

            IMPORTANT: Uses ZERO-BASED coordinates.

            potentialMatches are the places naming the type, or one of its subtypes,
            as a supertype that JDT could not confirm: the class declaring each may
            be an implementation, and, for a method, may override it.

            maxResults outside 0 to 1000 is INVALID_PARAMETER.

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
            .optional("maxResults", "integer", "Max implementations to return, 0 to 1000 (default 100)")
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
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }
        // maxResults=0 is honored literally (zero results); the documented maximum bounds the list
        if (maxResults < 0 || maxResults > MAX_RESULTS) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be between 0 and " + MAX_RESULTS + "; got: " + maxResults);
        }

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Must be a type or method. A lambda expression is no member of its own: the
            // position belongs to the nearest enclosing member that has a name.
            IType targetType = null;
            IMethod targetMethod = null;
            IJavaElement named = ElementNames.namedMember(element);

            if (named instanceof IType type) {
                targetType = type;
            } else if (named instanceof IMethod method) {
                targetMethod = method;
                targetType = method.getDeclaringType();
            } else {
                // Try to get the type from the element's ancestor
                targetType = ElementNames.enclosingType(element);
                if (targetType == null) {
                    return ToolResponse.invalidParameter("position",
                        "Symbol at position is not a type or method");
                }
            }

            // Every subtype, direct or not, as the Java model holds it; for a method, every
            // method of a subtype that overrides it as the compiler binds it.
            ModelHierarchy hierarchy = service.getSearchService().hierarchyOf(targetType);

            // Count eligible candidates separately from the displayed list so truncated
            // reports accurately. Comparing implementations.size() to maxResults would
            // fire a false-positive when actual eligible == maxResults exactly.
            List<Map<String, Object>> implementations = new ArrayList<>();
            int eligibleCount;
            if (targetMethod != null) {
                List<IMethod> overriders = hierarchy.overriding(targetMethod);
                eligibleCount = overriders.size();
                for (IMethod overrider : overriders) {
                    if (implementations.size() >= maxResults) break;
                    implementations.add(createMethodImplementationInfo(overrider.getDeclaringType(), overrider, service));
                }
            } else {
                List<IType> subtypes = hierarchy.allSubtypes();
                eligibleCount = subtypes.size();
                for (IType subtype : subtypes) {
                    if (implementations.size() >= maxResults) break;
                    implementations.add(createTypeImplementationInfo(subtype, service));
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", ElementNames.typeName(targetType));

            data.put("isInterface", targetType.isInterface());

            if (targetMethod != null) {
                data.put("method", targetMethod.getElementName());
            }

            data.put("totalImplementations", eligibleCount);
            data.put("implementations", implementations);
            Map<String, Object> potential = potentialSubtypes(hierarchy, maxResults, service);
            if (potential != null) {
                data.put("potentialMatches", potential);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(eligibleCount)
                .returnedCount(implementations.size())
                .truncated(eligibleCount > maxResults)
                .suggestedNextTools(List.of(
                    "get_type_hierarchy for full inheritance chain",
                    "find_references to see all usages",
                    "get_type_members to see members of an implementation"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding implementations: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createTypeImplementationInfo(IType type, IJdtService service)
            throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", ElementNames.typeName(type));
        info.put("qualifiedName", ElementNames.qualifiedName(type));
        info.put("kind", TypeKindResolver.kindOf(type));
        populateLocation(type, info, service, type.getNameRange());
        return info;
    }

    private Map<String, Object> createMethodImplementationInfo(IType type, IMethod method, IJdtService service)
            throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", ElementNames.typeName(type));
        info.put("qualifiedName", ElementNames.qualifiedName(type));
        info.put("kind", TypeKindResolver.kindOf(type));
        info.put("method", method.getElementName());
        populateLocation(method, info, service, method.getNameRange());
        return info;
    }

    private void populateLocation(IJavaElement element, Map<String, Object> info,
                                   IJdtService service, ISourceRange nameRange) throws JavaModelException {
        IResource resource = element.getResource();
        if (resource != null) {
            IPath location = resource.getLocation();
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }
        }
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu != null && nameRange != null && nameRange.getOffset() >= 0) {
            int line = service.getLineNumber(cu, nameRange.getOffset());
            int column = service.getColumnNumber(cu, nameRange.getOffset());
            info.put("line", line);
            info.put("column", column);
        }
    }
}
