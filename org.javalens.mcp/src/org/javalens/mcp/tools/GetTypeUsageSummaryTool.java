package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
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

            Aggregates all usage patterns in a single call:
            - Instantiations (new Foo())
            - Casts ((Foo) x)
            - Instanceof checks (x instanceof Foo)
            - Type arguments (List<Foo>)
            - Annotation usages (if annotation type)

            Use this to understand how a type is used throughout the project.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("typeName", Map.of(
            "type", "string",
            "description", "Fully qualified or simple type name"
        ));
        properties.put("maxPerCategory", Map.of(
            "type", "integer",
            "description", "Maximum results per category (default 10)"
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("typeName"));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }

        int maxPerCategory = getIntParam(arguments, "maxPerCategory", 10);
        maxPerCategory = Math.min(Math.max(maxPerCategory, 1), 50);

        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", type.getFullyQualifiedName());
            data.put("kind", getTypeKind(type));

            Map<String, Object> summary = new LinkedHashMap<>();
            int totalUsages = 0;

            // Instantiations (new Foo())
            List<SearchMatch> instantiations = service.getSearchService()
                .findTypeInstantiations(type, maxPerCategory);
            List<Map<String, Object>> instantiationResults = formatMatches(instantiations, service);
            summary.put("instantiations", Map.of(
                "count", instantiationResults.size(),
                "locations", instantiationResults
            ));
            totalUsages += instantiationResults.size();

            // Casts ((Foo) x)
            List<SearchMatch> casts = service.getSearchService()
                .findCasts(type, maxPerCategory);
            List<Map<String, Object>> castResults = formatMatches(casts, service);
            summary.put("casts", Map.of(
                "count", castResults.size(),
                "locations", castResults
            ));
            totalUsages += castResults.size();

            // Instanceof checks (x instanceof Foo)
            List<SearchMatch> instanceofChecks = service.getSearchService()
                .findInstanceofChecks(type, maxPerCategory);
            List<Map<String, Object>> instanceofResults = formatMatches(instanceofChecks, service);
            summary.put("instanceofChecks", Map.of(
                "count", instanceofResults.size(),
                "locations", instanceofResults
            ));
            totalUsages += instanceofResults.size();

            // Type arguments (List<Foo>)
            List<SearchMatch> typeArgs = service.getSearchService()
                .findTypeArguments(type, maxPerCategory);
            List<Map<String, Object>> typeArgResults = formatMatches(typeArgs, service);
            summary.put("typeArguments", Map.of(
                "count", typeArgResults.size(),
                "locations", typeArgResults
            ));
            totalUsages += typeArgResults.size();

            // Annotation usages (if annotation type)
            if (type.isAnnotation()) {
                List<SearchMatch> annotations = service.getSearchService()
                    .findAnnotationUsages(type, maxPerCategory);
                List<Map<String, Object>> annotationResults = formatMatches(annotations, service);
                summary.put("annotationUsages", Map.of(
                    "count", annotationResults.size(),
                    "locations", annotationResults
                ));
                totalUsages += annotationResults.size();
            }

            data.put("usages", summary);
            data.put("totalUsages", totalUsages);

            // Add insights
            List<String> insights = generateInsights(type, instantiationResults, castResults, instanceofResults);
            if (!insights.isEmpty()) {
                data.put("insights", insights);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalUsages)
                .returnedCount(totalUsages)
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

    private String getTypeKind(IType type) {
        try {
            if (type.isInterface()) return "Interface";
            if (type.isEnum()) return "Enum";
            if (type.isAnnotation()) return "Annotation";
            if (type.isRecord()) return "Record";
            return "Class";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private List<String> generateInsights(IType type,
                                          List<Map<String, Object>> instantiations,
                                          List<Map<String, Object>> casts,
                                          List<Map<String, Object>> instanceofChecks) {
        List<String> insights = new java.util.ArrayList<>();

        try {
            // Check for excessive casts
            if (casts.size() > 5) {
                insights.add("High cast count (" + casts.size() + ") - consider using polymorphism or generics");
            }

            // Check for instanceof pattern
            if (instanceofChecks.size() > 3) {
                insights.add("Multiple instanceof checks (" + instanceofChecks.size() + ") - consider visitor pattern or sealed classes");
            }

            // Check if abstract/interface is being instantiated (shouldn't happen, but good to flag)
            if ((type.isInterface() || org.eclipse.jdt.core.Flags.isAbstract(type.getFlags())) && !instantiations.isEmpty()) {
                insights.add("Interface/abstract type has instantiation references - likely anonymous class usage");
            }

            // Zero usage warning
            if (instantiations.isEmpty() && casts.isEmpty() && instanceofChecks.isEmpty()) {
                insights.add("Type has no direct usage in project source - may be used only via libraries or unused");
            }

        } catch (Exception e) {
            log.debug("Error generating insights: {}", e.getMessage());
        }

        return insights;
    }
}
