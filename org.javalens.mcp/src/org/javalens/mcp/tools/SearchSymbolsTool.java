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
import org.javalens.core.TypeKindResolver;
import org.javalens.core.search.SearchResult;
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

            USAGE: search_symbols(query="*Service", kind="class")
            OUTPUT: List of matching symbols with locations

            EXAMPLES:
            - search_symbols(query="Order*") - classes starting with Order
            - search_symbols(query="*Repository", kind="interface")
            - search_symbols(query="get*", kind="Method")

            PAGINATION: Use offset parameter for large result sets

            IMPORTANT: Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("query", "string", "Search pattern - supports * and ? wildcards")
            .optional("kind", "string", "Filter by kind: class, interface, enum, method, field")
            .optional("maxResults", "integer", "Max results to return (default 50)")
            .optional("offset", "integer", "Skip first N results for pagination")
            .build();
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

        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }
        if (offset < 0) {
            return ToolResponse.invalidParameter("offset",
                "Must be >= 0; got: " + offset);
        }
        // Honor maxResults=0 literally; upper bound is a safety cap.
        maxResults = Math.min(maxResults, 1000);

        try {
            // Convert kind to search type
            Integer searchFor = getSearchType(kind);

            // Use SearchService for indexed search. Fetch offset + maxResults extra
            // headroom so the kind filter has candidates to draw from.
            SearchResult searchResult = service.getSearchService()
                .searchSymbols(query, searchFor, offset + maxResults + 10);
            List<SearchMatch> matches = searchResult.matches();

            // Count post-offset, post-kind-filter passing candidates separately from
            // the displayed list so truncated is accurate. Comparing results.size() to
            // maxResults misreports the case where actual matches == maxResults exactly.
            List<Map<String, Object>> results = new ArrayList<>();
            int skipped = 0;
            int totalPassing = 0;

            for (SearchMatch match : matches) {
                Map<String, Object> symbolInfo = createSymbolInfo(match, service);
                if (symbolInfo == null) continue;
                if (kind != null && !matchesKind(symbolInfo, kind)) continue;

                if (skipped < offset) {
                    skipped++;
                    continue;
                }

                totalPassing++;
                if (results.size() < maxResults) {
                    results.add(symbolInfo);
                }
            }

            // If the underlying search itself was clipped, more matches may exist that
            // we didn't see — fold that into truncated.
            boolean truncated = totalPassing > maxResults || searchResult.truncated();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            if (kind != null) data.put("kind", kind);
            data.put("results", results);
            data.put("pagination", Map.of(
                "offset", offset,
                "returned", results.size(),
                "hasMore", truncated
            ));

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalPassing)
                .returnedCount(results.size())
                .truncated(truncated)
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
            case IJavaElement.TYPE -> element instanceof IType type
                ? TypeKindResolver.kindOf(type)
                : "class";
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
