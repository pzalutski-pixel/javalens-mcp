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
 * Find all usages of an annotation type.
 *
 * JDT-unique capability: Uses ANNOTATION_TYPE_REFERENCE to find only annotation usages,
 * not other type references. LSP cannot distinguish these.
 */
public class FindAnnotationUsagesTool extends AbstractTool {

    public FindAnnotationUsagesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_annotation_usages";
    }

    @Override
    public String getDescription() {
        return """
            Find all usages of an annotation type in the project.

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            USAGE: Provide fully qualified annotation name
            OUTPUT: All locations where the annotation is applied

            Examples:
            - find_annotation_usages(annotation="org.springframework.beans.factory.annotation.Autowired")
            - find_annotation_usages(annotation="org.junit.jupiter.api.Test")
            - find_annotation_usages(annotation="javax.persistence.Entity")

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> annotation = new LinkedHashMap<>();
        annotation.put("type", "string");
        annotation.put("description", "Fully qualified annotation type name (e.g., 'org.springframework.beans.factory.annotation.Autowired')");
        properties.put("annotation", annotation);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("annotation"));

        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String annotationName = getStringParam(arguments, "annotation");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (annotationName == null || annotationName.isBlank()) {
            return ToolResponse.invalidParameter("annotation", "Annotation name is required");
        }

        try {
            IType annotationType = service.findType(annotationName);
            if (annotationType == null) {
                return ToolResponse.symbolNotFound("Annotation type not found: " + annotationName);
            }

            List<SearchMatch> matches = service.getSearchService().findAnnotationUsages(annotationType, maxResults);
            List<Map<String, Object>> usages = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("annotation", annotationName);
            data.put("totalUsages", usages.size());
            data.put("usages", usages);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(usages.size())
                .returnedCount(usages.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "get_symbol_info at a usage location for details",
                    "find_references for all references (not just annotations)"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
