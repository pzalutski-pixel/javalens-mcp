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
 * Find all catch blocks for an exception type (catch(Foo e)).
 *
 * JDT-unique capability: Uses CATCH_TYPE_REFERENCE to find only catch blocks,
 * not other type references. LSP cannot distinguish these.
 */
public class FindCatchBlocksTool extends AbstractTool {

    public FindCatchBlocksTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_catch_blocks";
    }

    @Override
    public String getDescription() {
        return """
            Find all catch blocks for an exception type (catch(ExceptionType e)).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified exception type name
            OUTPUT: All catch blocks that handle this exception type

            Useful for:
            - Understanding exception handling patterns
            - Finding all handlers for a specific exception
            - Exception handling analysis and refactoring

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> exceptionType = new LinkedHashMap<>();
        exceptionType.put("type", "string");
        exceptionType.put("description", "Fully qualified exception type name (e.g., 'java.io.IOException')");
        properties.put("exceptionType", exceptionType);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("exceptionType"));

        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String exceptionTypeName = getStringParam(arguments, "exceptionType");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (exceptionTypeName == null || exceptionTypeName.isBlank()) {
            return ToolResponse.invalidParameter("exceptionType", "Exception type name is required");
        }

        try {
            IType type = service.findType(exceptionTypeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Exception type not found: " + exceptionTypeName);
            }

            List<SearchMatch> matches = service.getSearchService().findCatchBlocks(type, maxResults);
            List<Map<String, Object>> catchBlocks = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exceptionType", exceptionTypeName);
            data.put("totalCatchBlocks", catchBlocks.size());
            data.put("catchBlocks", catchBlocks);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(catchBlocks.size())
                .returnedCount(catchBlocks.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_throws_declarations to find sources of this exception",
                    "get_type_hierarchy to see exception hierarchy"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
