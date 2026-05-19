package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
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
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the method")
            .required("line", "integer", "Zero-based line number of the method")
            .required("column", "integer", "Zero-based column number")
            .optional("maxResults", "integer", "Maximum results to return (default 100)")
            .build();
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

            SearchResult result = service.getSearchService().findMethodReferences(method, maxResults);
            List<Map<String, Object>> methodRefs = formatMatches(result.matches(), service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("methodName", method.getElementName());
            data.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
            data.put("totalCount", result.totalEncountered());
            data.put("locations", methodRefs);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(result.totalEncountered())
                .returnedCount(methodRefs.size())
                .truncated(result.truncated())
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
