package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.SearchMatch;
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
 * Find implementations of an interface or extensions of a class.
 * Uses JDT SearchEngine IMPLEMENTORS search for fast indexed results.
 */
public class FindImplementationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindImplementationsTool.class);

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
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max implementations to return (default 100)"
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
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
        }

        maxResults = Math.min(Math.max(maxResults, 1), 1000);

        try {
            Path path = Path.of(filePath);

            // Get element at position
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            // Must be a type or method
            IType targetType = null;
            IMethod targetMethod = null;

            if (element instanceof IType type) {
                targetType = type;
            } else if (element instanceof IMethod method) {
                targetMethod = method;
                targetType = method.getDeclaringType();
            } else {
                // Try to get the type from the element's ancestor
                targetType = (IType) element.getAncestor(IJavaElement.TYPE);
                if (targetType == null) {
                    return ToolResponse.invalidParameter("position",
                        "Symbol at position is not a type or method");
                }
            }

            // Use SearchService for indexed implementor search
            List<SearchMatch> matches = service.getSearchService()
                .findImplementations(targetMethod != null ? targetMethod : targetType, maxResults);

            // Convert matches to implementation info
            List<Map<String, Object>> implementations = new ArrayList<>();
            for (SearchMatch match : matches) {
                Map<String, Object> implInfo = createImplementationInfo(match, service);
                if (implInfo != null) {
                    implementations.add(implInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", targetType.getElementName());

            try {
                data.put("isInterface", targetType.isInterface());
            } catch (JavaModelException e) {
                // Ignore
            }

            if (targetMethod != null) {
                data.put("method", targetMethod.getElementName());
            }

            data.put("totalImplementations", implementations.size());
            data.put("implementations", implementations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(implementations.size())
                .returnedCount(implementations.size())
                .truncated(implementations.size() >= maxResults)
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

    private Map<String, Object> createImplementationInfo(SearchMatch match, IJdtService service) {
        try {
            Object element = match.getElement();
            if (!(element instanceof IJavaElement javaElement)) {
                return null;
            }

            Map<String, Object> info = new LinkedHashMap<>();

            // Get the type information
            IType type = null;
            if (javaElement instanceof IType t) {
                type = t;
            } else if (javaElement instanceof IMethod m) {
                type = m.getDeclaringType();
                info.put("method", m.getElementName());
            }

            if (type != null) {
                info.put("name", type.getElementName());
                info.put("qualifiedName", type.getFullyQualifiedName());

                try {
                    if (type.isInterface()) {
                        info.put("kind", "Interface");
                    } else if (type.isEnum()) {
                        info.put("kind", "Enum");
                    } else {
                        info.put("kind", "Class");
                    }
                } catch (JavaModelException e) {
                    info.put("kind", "Class");
                }
            } else {
                info.put("name", javaElement.getElementName());
            }

            // File path
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Line and column
            ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu != null) {
                int implLine = service.getLineNumber(cu, match.getOffset());
                int implColumn = service.getColumnNumber(cu, match.getOffset());
                info.put("line", implLine);
                info.put("column", implColumn);
            }

            return info;

        } catch (Exception e) {
            log.debug("Error creating implementation info: {}", e.getMessage());
            return null;
        }
    }
}
