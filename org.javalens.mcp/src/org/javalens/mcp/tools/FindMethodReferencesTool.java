package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find all method reference expressions (Foo::bar lambdas).
 *
 * JDT-unique capability: Uses METHOD_REFERENCE_EXPRESSION to find only method references,
 * not regular method calls. LSP cannot distinguish these.
 */
public class FindMethodReferencesTool extends AbstractTool {

    public FindMethodReferencesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_method_references";
    }

    @Override
    public String getDescription() {
        return """
            Find all method reference expressions (Foo::bar lambda syntax).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Position on a method, or provide method details
            OUTPUT: All locations where the method is used as a method reference

            Useful for:
            - Understanding functional programming patterns
            - Finding lambda-style usages of methods
            - Refactoring analysis

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Path to source file containing the method");
        properties.put("filePath", filePath);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "integer");
        line.put("description", "Zero-based line number of the method");
        properties.put("line", line);

        Map<String, Object> column = new LinkedHashMap<>();
        column.put("type", "integer");
        column.put("description", "Zero-based column number");
        properties.put("column", column);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));

        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "File path is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("position", "Line and column are required (zero-based)");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No element at position");
            }

            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position", "Element at position is not a method");
            }

            List<SearchMatch> matches = service.getSearchService().findMethodReferences(method, maxResults);
            List<Map<String, Object>> methodRefs = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("methodName", method.getElementName());
            data.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
            data.put("totalMethodReferences", methodRefs.size());
            data.put("methodReferences", methodRefs);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodRefs.size())
                .returnedCount(methodRefs.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_references for all references including regular calls",
                    "get_call_hierarchy_incoming to see all callers"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
