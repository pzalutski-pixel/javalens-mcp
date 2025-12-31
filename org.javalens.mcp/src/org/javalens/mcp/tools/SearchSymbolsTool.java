package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Search for types, methods, fields by name pattern.
 * Uses JDT SearchEngine for fast indexed search.
 */
public class SearchSymbolsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SearchSymbolsTool.class);

    public SearchSymbolsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "search_symbols";
    }

    @Override
    public String getDescription() {
        return """
            Search for types, methods, fields by name pattern.
            Supports glob patterns: * (any chars), ? (single char)

            USAGE: search_symbols(query="*Service", kind="Class")
            OUTPUT: List of matching symbols with locations

            EXAMPLES:
            - search_symbols(query="Order*") - classes starting with Order
            - search_symbols(query="*Repository", kind="Interface")
            - search_symbols(query="get*", kind="Method")

            PAGINATION: Use offset parameter for large result sets

            IMPORTANT: Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "query", Map.of(
                "type", "string",
                "description", "Search pattern - supports * and ? wildcards"
            ),
            "kind", Map.of(
                "type", "string",
                "description", "Filter by kind: Class, Interface, Enum, Method, Field"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max results to return (default 50)"
            ),
            "offset", Map.of(
                "type", "integer",
                "description", "Skip first N results for pagination"
            )
        ));
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String query = getStringParam(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolResponse.invalidParameter("query", "Required parameter missing");
        }

        String kind = getStringParam(arguments, "kind");
        int maxResults = getIntParam(arguments, "maxResults", 50);
        int offset = getIntParam(arguments, "offset", 0);

        // Validate and cap values
        maxResults = Math.min(Math.max(maxResults, 1), 1000);
        offset = Math.max(offset, 0);

        try {
            // Convert kind to search type
            Integer searchFor = getSearchType(kind);

            // Use SearchService for indexed search
            List<SearchMatch> matches = service.getSearchService()
                .searchSymbols(query, searchFor, offset + maxResults + 10);

            // Convert matches to result format
            List<Map<String, Object>> results = new ArrayList<>();
            int skipped = 0;
            int added = 0;

            for (SearchMatch match : matches) {
                if (added >= maxResults) break;

                if (skipped < offset) {
                    skipped++;
                    continue;
                }

                Map<String, Object> symbolInfo = createSymbolInfo(match, service);
                if (symbolInfo != null) {
                    // Filter by kind if specified
                    if (kind != null && !matchesKind(symbolInfo, kind)) {
                        continue;
                    }
                    results.add(symbolInfo);
                    added++;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            if (kind != null) data.put("kind", kind);
            data.put("results", results);
            data.put("pagination", Map.of(
                "offset", offset,
                "returned", results.size(),
                "hasMore", matches.size() > offset + results.size()
            ));

            return ToolResponse.success(data, ResponseMeta.builder()
                .returnedCount(results.size())
                .truncated(results.size() == maxResults)
                .suggestedNextTools(List.of(
                    "get_symbol_info at a result location for detailed info",
                    "get_type_members for type results",
                    "find_references to see all usages"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error searching symbols: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Integer getSearchType(String kind) {
        if (kind == null) return null; // Search all types

        return switch (kind.toLowerCase()) {
            case "class" -> IJavaSearchConstants.CLASS;
            case "interface" -> IJavaSearchConstants.INTERFACE;
            case "enum" -> IJavaSearchConstants.ENUM;
            case "method" -> IJavaSearchConstants.METHOD;
            case "field" -> IJavaSearchConstants.FIELD;
            default -> IJavaSearchConstants.TYPE;
        };
    }

    private boolean matchesKind(Map<String, Object> symbolInfo, String kind) {
        String symbolKind = (String) symbolInfo.get("kind");
        if (symbolKind == null) return true;
        return symbolKind.equalsIgnoreCase(kind);
    }

    private Map<String, Object> createSymbolInfo(SearchMatch match, IJdtService service) {
        Object element = match.getElement();
        if (!(element instanceof IJavaElement javaElement)) {
            return null;
        }

        Map<String, Object> info = new LinkedHashMap<>();

        try {
            info.put("name", javaElement.getElementName());
            info.put("kind", getElementKind(javaElement));

            // Location info
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Add line/column from match offset
            if (javaElement.getOpenable() != null &&
                javaElement.getOpenable() instanceof org.eclipse.jdt.core.ICompilationUnit cu) {
                int line = service.getLineNumber(cu, match.getOffset());
                int column = service.getColumnNumber(cu, match.getOffset());
                info.put("line", line);
                info.put("column", column);
            }

            // Type-specific info
            if (javaElement instanceof IType type) {
                info.put("qualifiedName", type.getFullyQualifiedName());
                if (type.getPackageFragment() != null) {
                    info.put("package", type.getPackageFragment().getElementName());
                }
            } else if (javaElement instanceof IMethod method) {
                info.put("signature", getMethodSignature(method));
                if (method.getDeclaringType() != null) {
                    info.put("containingType", method.getDeclaringType().getElementName());
                }
            } else if (javaElement instanceof IField field) {
                info.put("type", field.getTypeSignature());
                if (field.getDeclaringType() != null) {
                    info.put("containingType", field.getDeclaringType().getElementName());
                }
            }

        } catch (JavaModelException e) {
            log.debug("Error getting symbol info: {}", e.getMessage());
        }

        return info;
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> {
                if (element instanceof IType type) {
                    try {
                        if (type.isInterface()) yield "Interface";
                        if (type.isEnum()) yield "Enum";
                        if (type.isAnnotation()) yield "Annotation";
                    } catch (JavaModelException e) {
                        // Fall through
                    }
                }
                yield "Class";
            }
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            default -> "Unknown";
        };
    }

    private String getMethodSignature(IMethod method) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");

        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(org.eclipse.jdt.core.Signature.toString(paramTypes[i]));
            if (i < paramNames.length) {
                sig.append(" ").append(paramNames[i]);
            }
        }

        sig.append(")");
        return sig.toString();
    }
}
