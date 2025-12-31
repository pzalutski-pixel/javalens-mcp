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
 * Find all instanceof checks for a type (x instanceof Foo).
 *
 * JDT-unique capability: Uses INSTANCEOF_TYPE_REFERENCE to find only instanceof checks,
 * not other type references. LSP cannot distinguish these.
 */
public class FindInstanceofChecksTool extends AbstractTool {

    public FindInstanceofChecksTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_instanceof_checks";
    }

    @Override
    public String getDescription() {
        return """
            Find all instanceof checks for a type (x instanceof Foo).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified type name
            OUTPUT: All locations where instanceof checks against this type occur

            Useful for:
            - Identifying type checking patterns
            - Finding polymorphism opportunities (replace instanceof with virtual dispatch)
            - Understanding type discrimination logic

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
        typeName.put("description", "Fully qualified type name to find instanceof checks for");
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

            List<SearchMatch> matches = service.getSearchService().findInstanceofChecks(type, maxResults);
            List<Map<String, Object>> checks = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalChecks", checks.size());
            data.put("instanceofChecks", checks);

            if (!checks.isEmpty()) {
                data.put("suggestion", "Consider visitor pattern or polymorphism to replace instanceof chains");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(checks.size())
                .returnedCount(checks.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_casts to find related cast expressions",
                    "get_type_hierarchy to understand inheritance"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
