package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find all casts to a type ((Foo) x expressions).
 *
 * JDT-unique capability: Uses CAST_TYPE_REFERENCE to find only cast expressions,
 * not other type references. LSP cannot distinguish these.
 */
public class FindCastsTool extends AbstractTool {

    public FindCastsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_casts";
    }

    @Override
    public String getDescription() {
        return """
            Find all casts to a type ((Foo) x expressions).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where casting to this type occurs

            Useful for:
            - Identifying unsafe downcasts
            - Finding refactoring opportunities (replace cast with polymorphism)
            - Understanding type conversion patterns

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> typeName = new LinkedHashMap<>();
        typeName.put("type", "string");
        typeName.put("description", "Fully qualified type name to find casts to");
        properties.put("typeName", typeName);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("typeName"));

        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Type name is required");
        }

        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            List<SearchMatch> matches = service.getSearchService().findCasts(type, maxResults);
            List<Map<String, Object>> casts = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalCasts", casts.size());
            data.put("casts", casts);

            if (!casts.isEmpty()) {
                data.put("warning", "Casts may indicate design issues - consider polymorphism");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(casts.size())
                .returnedCount(casts.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_instanceof_checks to find related type checks",
                    "get_type_hierarchy to understand inheritance"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
