package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.ElementKindResolver;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
import org.javalens.core.search.SearchResult;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Search the project's source declarations by name pattern, optionally narrowed to one kind.
 * Uses JDT SearchEngine for fast indexed search. The kind filter and the {@code ?} wildcard
 * run on every match before the requested page is cut, so the page, the total and hasMore
 * all describe the declarations that pass.
 */
public class SearchSymbolsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SearchSymbolsTool.class);

    /** The accepted {@code kind} values, compared case-insensitively. */
    private static final List<String> KINDS =
        List.of("class", "interface", "enum", "record", "annotation", "method", "field");

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
            Search the project's source declarations by name pattern.
            Supports glob patterns: * (any chars), ? (single char)

            USAGE: search_symbols(query="*Service", kind="class")
            OUTPUT: One page of matching declarations with locations

            KIND (optional, compared case-insensitively):
            - class, interface, enum, record, annotation: exactly the types of that kind
              (class excludes interfaces, enums, records and annotation types)
            - method: method declarations (constructors are not included, and lambda
              expressions are not method declarations)
            - field: every field declaration, including static final constants and enum
              constants; each result's kind is field, constant or enumConstant
              (record components are not field declarations)
            Without kind, every type is searched (no methods or fields).
            Any other kind is rejected with INVALID_PARAMETER.

            EXAMPLES:
            - search_symbols(query="Order*") - types starting with Order
            - search_symbols(query="*Repository", kind="interface")
            - search_symbols(query="get*", kind="method")
            - search_symbols(query="log", kind="field")

            PAGINATION: the kind and pattern filters apply first, then offset and
            maxResults select the page. meta.totalCount counts every matching declaration;
            pagination.hasMore (and meta.truncated) is true when more follow this page,
            so request offset + returned next.

            IMPORTANT: Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("query", "string", "Search pattern - supports * and ? wildcards")
            .optional("kind", "string", "Optional, case-insensitive: class, interface, enum, record, "
                + "annotation, method or field. Omit to search every type. field includes static "
                + "final constants and enum constants.")
            .optional("maxResults", "integer", "Max results to return (default 50, at most 1000)")
            .optional("offset", "integer", "Skip the first N matching declarations (default 0)")
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
        String normalizedKind = kind == null ? null : kind.toLowerCase(Locale.ROOT);
        if (normalizedKind != null && !KINDS.contains(normalizedKind)) {
            return ToolResponse.invalidParameter("kind",
                "Must be one of " + String.join(", ", KINDS) + " (case-insensitive); got: " + kind);
        }
        // Honor maxResults=0 literally; upper bound is a safety cap.
        maxResults = Math.min(maxResults, 1000);

        try {
            SearchResult searchResult = service.getSearchService().searchSymbols(
                query, searchFor(normalizedKind), accepts(normalizedKind), offset, maxResults);

            List<Map<String, Object>> results = new ArrayList<>();
            for (SearchMatch match : searchResult.matches()) {
                results.add(createSymbolInfo(match, (IJavaElement) match.getElement(), service));
            }

            int total = searchResult.totalEncountered();
            boolean hasMore = (long) offset + results.size() < total;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            if (kind != null) data.put("kind", kind);
            data.put("results", results);
            data.put("pagination", Map.of(
                "offset", offset,
                "returned", results.size(),
                "hasMore", hasMore
            ));

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(results.size())
                .truncated(hasMore)
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

    /**
     * The JDT declaration search for a kind. Every type kind searches all type declarations
     * and keeps its own kind: JDT's TYPE search reports every type, so the filter only
     * narrows (its CLASS search reports records too, and the narrower type searches resolve
     * each candidate). METHOD reports method declarations and never constructors, and it also
     * reports each lambda expression whose functional method matches, as a lambda method.
     * FIELD reports field declarations, enum constants included. Without a kind, SearchService
     * searches every type.
     */
    private static Integer searchFor(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "method" -> IJavaSearchConstants.METHOD;
            case "field" -> IJavaSearchConstants.FIELD;
            default -> IJavaSearchConstants.TYPE;
        };
    }

    /**
     * The declarations a kind keeps: a type kind keeps the types {@link TypeKindResolver}
     * names with it, method keeps declared methods (not lambda methods, which are lambda
     * expressions), and field keeps every field, whatever its finer kind (field, constant or
     * enumConstant).
     */
    private static Predicate<IJavaElement> accepts(String kind) {
        if (kind == null) return element -> true;
        return switch (kind) {
            case "method" -> element -> element instanceof IMethod method && !method.isLambdaMethod()
                && "method".equals(ElementKindResolver.kindOf(method));
            case "field" -> element -> element instanceof IField;
            default -> element -> element instanceof IType type && kind.equals(TypeKindResolver.kindOf(type));
        };
    }

    private Map<String, Object> createSymbolInfo(SearchMatch match, IJavaElement javaElement, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();

        try {
            info.put("name", javaElement.getElementName());
            info.put("kind", ElementKindResolver.kindOf(javaElement));

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
                // Render the readable type name (e.g. "int", "String"), consistent with
                // every other tool, not the raw JVM type signature ("I", "QString;").
                info.put("type", org.eclipse.jdt.core.Signature.toString(field.getTypeSignature()));
                if (field.getDeclaringType() != null) {
                    info.put("containingType", field.getDeclaringType().getElementName());
                }
            }

        } catch (JavaModelException e) {
            log.debug("Error getting symbol info: {}", e.getMessage());
        }

        return info;
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
