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
 * Find all throws declarations of an exception type (throws Foo in method signatures).
 *
 * JDT-unique capability: Uses THROWS_CLAUSE_TYPE_REFERENCE to find only throws declarations,
 * not other type references. LSP cannot distinguish these.
 */
public class FindThrowsDeclarationsTool extends AbstractTool {

    public FindThrowsDeclarationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_throws_declarations";
    }

    @Override
    public String getDescription() {
        return """
            Find all throws declarations of an exception type in method signatures.

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified exception type name
            OUTPUT: All methods that declare 'throws ExceptionType'

            Useful for:
            - Understanding exception flow in the codebase
            - Finding all methods that can throw a specific exception
            - Exception handling analysis

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

            List<SearchMatch> matches = service.getSearchService().findThrowsDeclarations(type, maxResults);
            List<Map<String, Object>> declarations = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exceptionType", exceptionTypeName);
            data.put("totalDeclarations", declarations.size());
            data.put("throwsDeclarations", declarations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(declarations.size())
                .returnedCount(declarations.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_catch_blocks to find handlers for this exception",
                    "get_type_hierarchy to see exception hierarchy"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
