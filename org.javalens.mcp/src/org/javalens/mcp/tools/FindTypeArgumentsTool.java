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
 * Find all type argument usages (List<Foo>, Map<K, Foo>).
 *
 * JDT-unique capability: Uses TYPE_ARGUMENT_TYPE_REFERENCE to find only generic type arguments,
 * not other type references. LSP cannot distinguish these.
 */
public class FindTypeArgumentsTool extends AbstractTool {

    public FindTypeArgumentsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_type_arguments";
    }

    @Override
    public String getDescription() {
        return """
            Find all usages of a type as a generic type argument (List<Foo>, Map<K, Foo>).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where the type is used as a generic argument

            Useful for:
            - Understanding generic usage patterns
            - Finding all collections/containers of a type
            - API design analysis

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
        typeName.put("description", "Fully qualified type name to find in generic arguments");
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

            List<SearchMatch> matches = service.getSearchService().findTypeArguments(type, maxResults);
            List<Map<String, Object>> usages = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalUsages", usages.size());
            data.put("typeArgumentUsages", usages);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(usages.size())
                .returnedCount(usages.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_references for all type references",
                    "find_type_instantiations to find object creation"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
