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
 * Find all instantiations of a type (new Foo() calls).
 *
 * JDT-unique capability: Uses CLASS_INSTANCE_CREATION_TYPE_REFERENCE to find only
 * instantiations, not other type references. LSP cannot distinguish these.
 */
public class FindTypeInstantiationsTool extends AbstractTool {

    public FindTypeInstantiationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_type_instantiations";
    }

    @Override
    public String getDescription() {
        return """
            Find all instantiations of a type (new Foo() calls).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where the type is instantiated with 'new'

            Useful for:
            - Understanding object creation patterns
            - Identifying factory method candidates
            - Finding coupling points

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
        typeName.put("description", "Fully qualified type name (e.g., 'java.util.ArrayList')");
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

            List<SearchMatch> matches = service.getSearchService().findTypeInstantiations(type, maxResults);
            List<Map<String, Object>> instantiations = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalInstantiations", instantiations.size());
            data.put("instantiations", instantiations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(instantiations.size())
                .returnedCount(instantiations.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "get_type_hierarchy to understand inheritance",
                    "find_references for all references"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
