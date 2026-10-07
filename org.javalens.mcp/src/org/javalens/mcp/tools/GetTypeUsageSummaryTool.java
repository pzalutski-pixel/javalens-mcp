package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.javalens.core.IJdtService;
import org.javalens.core.TypeKindResolver;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.core.search.SearchService.ReferenceKind;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Get comprehensive usage summary for a type across the codebase.
 * Aggregates all fine-grained reference searches.
 */
public class GetTypeUsageSummaryTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetTypeUsageSummaryTool.class);

    /** The documented range of {@code maxPerCategory}. */
    private static final int MIN_PER_CATEGORY = 1;
    private static final int MAX_PER_CATEGORY = 50;

    public GetTypeUsageSummaryTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_type_usage_summary";
    }

    @Override
    public String getDescription() {
        return """
            Get comprehensive usage summary for a type across the codebase.

            USAGE: get_type_usage_summary(typeName="com.example.Foo")
            OUTPUT: Instantiations, casts, instanceof checks, type arguments, annotations

            Aggregates all usage patterns in a single call:
            - Instantiations (new Foo())
            - Casts ((Foo) x)
            - Instanceof checks (x instanceof Foo)
            - Type arguments (List<Foo>)
            - Annotation usages (if annotation type)

            Each category's count is the exact number of its usages; its locations
            list holds the first maxPerCategory of them (1 to 50, default 10; any
            other value is rejected with INVALID_PARAMETER). totalUsages and
            meta.totalCount count every usage, meta.returnedCount the locations
            listed, and meta.truncated is true when a category lists fewer
            locations than its count. A category's potentialMatches stand beside
            its locations.

            Use this to understand how a type is used throughout the project.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("typeName", "string", "Fully qualified or simple type name")
            .optional("maxPerCategory", "integer", "Maximum locations listed per category, 1 to 50 (default 10)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }

        int maxPerCategory = getIntParam(arguments, "maxPerCategory", 10);
        if (maxPerCategory < MIN_PER_CATEGORY || maxPerCategory > MAX_PER_CATEGORY) {
            return ToolResponse.invalidParameter("maxPerCategory", "Must be between " + MIN_PER_CATEGORY
                + " and " + MAX_PER_CATEGORY + "; got: " + maxPerCategory);
        }

        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", type.getFullyQualifiedName());
            data.put("kind", TypeKindResolver.kindOf(type));

            SearchService search = service.getSearchService();
            SearchResult instantiations = search.findReferences(type, ReferenceKind.INSTANTIATION, maxPerCategory);
            SearchResult casts = search.findReferences(type, ReferenceKind.CAST, maxPerCategory);
            SearchResult instanceofChecks = search.findReferences(type, ReferenceKind.INSTANCEOF, maxPerCategory);
            SearchResult typeArguments = search.findReferences(type, ReferenceKind.TYPE_ARGUMENT, maxPerCategory);

            // Each category: the exact count of its usages and its first maxPerCategory locations
            Map<String, Map<String, Object>> usages = new LinkedHashMap<>();
            usages.put("instantiations", category(instantiations, service));
            usages.put("casts", category(casts, service));
            usages.put("instanceofChecks", category(instanceofChecks, service));
            usages.put("typeArguments", category(typeArguments, service));
            if (type.isAnnotation()) {
                usages.put("annotationUsages",
                    category(search.findReferences(type, ReferenceKind.ANNOTATION, maxPerCategory), service));
            }

            int totalUsages = 0;
            int returnedLocations = 0;
            for (Map<String, Object> category : usages.values()) {
                totalUsages += (int) category.get("count");
                returnedLocations += ((List<?>) category.get("locations")).size();
            }

            data.put("usages", usages);
            data.put("totalUsages", totalUsages);

            // Add insights
            List<String> insights = generateInsights(type, instantiations.totalEncountered(),
                casts.totalEncountered(), instanceofChecks.totalEncountered());
            if (!insights.isEmpty()) {
                data.put("insights", insights);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalUsages)
                .returnedCount(returnedLocations)
                .truncated(returnedLocations < totalUsages)
                .suggestedNextTools(List.of(
                    "analyze_type for complete type analysis",
                    "get_type_hierarchy to understand inheritance",
                    "find_references for all reference types"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting type usage summary: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * A usage category: the exact count of its usages and the locations of those listed, and the
     * category's potential matches beside them when it has any.
     */
    private Map<String, Object> category(SearchResult result, IJdtService service) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("count", result.totalEncountered());
        category.put("locations", formatMatches(result.matches(), service));
        putPotentialMatches(category, result, service);
        return category;
    }

    private List<String> generateInsights(IType type, int instantiations, int casts, int instanceofChecks) {
        List<String> insights = new java.util.ArrayList<>();

        try {
            // Check for excessive casts
            if (casts > 5) {
                insights.add("High cast count (" + casts + ") - consider using polymorphism or generics");
            }

            // Check for instanceof pattern
            if (instanceofChecks > 3) {
                insights.add("Multiple instanceof checks (" + instanceofChecks + ") - consider visitor pattern or sealed classes");
            }

            // Check if abstract/interface is being instantiated (shouldn't happen, but good to flag)
            if ((type.isInterface() || org.eclipse.jdt.core.Flags.isAbstract(type.getFlags())) && instantiations > 0) {
                insights.add("Interface/abstract type has instantiation references - likely anonymous class usage");
            }

            // Zero usage warning
            if (instantiations == 0 && casts == 0 && instanceofChecks == 0) {
                insights.add("Type has no direct usage in project source - may be used only via libraries or unused");
            }

        } catch (Exception e) {
            log.debug("Error generating insights: {}", e.getMessage());
        }

        return insights;
    }
}
