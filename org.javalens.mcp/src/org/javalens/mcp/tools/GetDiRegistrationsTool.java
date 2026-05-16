package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find all dependency injection registrations in the project.
 * Scans for Spring component annotations, bean definitions, and injection points.
 * Also supports Jakarta/javax.inject annotations.
 */
public class GetDiRegistrationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetDiRegistrationsTool.class);

    private static final String[][] COMPONENT_ANNOTATIONS = {
        {"org.springframework.stereotype.Component", "Component"},
        {"org.springframework.stereotype.Service", "Service"},
        {"org.springframework.stereotype.Repository", "Repository"},
        {"org.springframework.stereotype.Controller", "Controller"},
        {"org.springframework.web.bind.annotation.RestController", "RestController"},
    };

    private static final String[][] CONFIG_ANNOTATIONS = {
        {"org.springframework.context.annotation.Configuration", "Configuration"},
    };

    private static final String[][] BEAN_ANNOTATIONS = {
        {"org.springframework.context.annotation.Bean", "Bean"},
    };

    private static final String[][] INJECTION_ANNOTATIONS = {
        {"org.springframework.beans.factory.annotation.Autowired", "Autowired"},
        {"javax.inject.Inject", "Inject"},
        {"jakarta.inject.Inject", "Inject"},
    };

    public GetDiRegistrationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_di_registrations";
    }

    @Override
    public String getDescription() {
        return """
            Find all dependency injection registrations in the project.

            USAGE: get_di_registrations()
            OUTPUT: Components, configurations, beans, and injection points

            Scans for:
            - Spring components: @Component, @Service, @Repository, @Controller, @RestController
            - Configuration: @Configuration
            - Bean definitions: @Bean
            - Injection points: @Autowired, @Inject (javax and jakarta)

            Returns empty categories for non-Spring projects (does not error).

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results per annotation type (default 200)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);

        try {
            List<Map<String, Object>> components = scanAnnotations(service, COMPONENT_ANNOTATIONS, maxResults);
            List<Map<String, Object>> configurations = scanAnnotations(service, CONFIG_ANNOTATIONS, maxResults);
            List<Map<String, Object>> beans = scanAnnotations(service, BEAN_ANNOTATIONS, maxResults);
            List<Map<String, Object>> injectionPoints = scanAnnotations(service, INJECTION_ANNOTATIONS, maxResults);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("components", components.size());
            summary.put("configurations", configurations.size());
            summary.put("beans", beans.size());
            summary.put("injectionPoints", injectionPoints.size());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", summary);
            data.put("components", components);
            data.put("configurations", configurations);
            data.put("beans", beans);
            data.put("injectionPoints", injectionPoints);

            int total = components.size() + configurations.size() + beans.size() + injectionPoints.size();

            // `maxResults` is the per-category cap (see scanAnnotations) — if any
            // category list hit the cap, the aggregate output is potentially missing
            // entries from that category.
            boolean truncated = components.size() >= maxResults
                || configurations.size() >= maxResults
                || beans.size() >= maxResults
                || injectionPoints.size() >= maxResults;

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(total)
                .truncated(truncated)
                .suggestedNextTools(List.of(
                    "find_references to trace a specific dependency",
                    "analyze_type for details on a registered component"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private List<Map<String, Object>> scanAnnotations(IJdtService service, String[][] annotations, int maxResults) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (String[] annotation : annotations) {
            String fqn = annotation[0];
            String label = annotation[1];

            try {
                IType annotationType = service.findType(fqn);
                if (annotationType == null) continue;

                List<SearchMatch> matches = service.getSearchService().findAnnotationUsages(annotationType, maxResults);
                for (Map<String, Object> match : formatMatches(matches, service)) {
                    match.put("annotation", "@" + label);
                    results.add(match);
                }
            } catch (Exception e) {
                log.debug("Could not scan for {}: {}", fqn, e.getMessage());
            }
        }

        return results;
    }
}
