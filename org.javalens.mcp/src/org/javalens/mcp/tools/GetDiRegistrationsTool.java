package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IType;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService.ReferenceKind;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;

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

            maxResults caps the matches listed for each annotation type (default
            200). Each summary count is the exact number of registrations in its
            category; meta.totalCount counts every registration, meta.returnedCount
            those listed, and meta.truncated is true when an annotation type has
            more matches than maxResults. potentialMatches lists, the first
            maxResults of each annotation type, the annotations JDT could not
            confirm, each with the annotation it names; none is a registration.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .optional("maxResults", "integer", "Maximum results per annotation type (default 200)")
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);

        if (maxResults < 0) {
            return ToolResponse.invalidParameter("maxResults",
                "Must be >= 0; got: " + maxResults);
        }

        try {
            Category components = scanAnnotations(service, COMPONENT_ANNOTATIONS, maxResults);
            Category configurations = scanAnnotations(service, CONFIG_ANNOTATIONS, maxResults);
            Category beans = scanAnnotations(service, BEAN_ANNOTATIONS, maxResults);
            Category injectionPoints = scanAnnotations(service, INJECTION_ANNOTATIONS, maxResults);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("components", components.total());
            summary.put("configurations", configurations.total());
            summary.put("beans", beans.total());
            summary.put("injectionPoints", injectionPoints.total());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", summary);
            data.put("components", components.matches());
            data.put("configurations", configurations.matches());
            data.put("beans", beans.matches());
            data.put("injectionPoints", injectionPoints.matches());
            int potentialTotal = components.potentialTotal() + configurations.potentialTotal()
                + beans.potentialTotal() + injectionPoints.potentialTotal();
            if (potentialTotal > 0) {
                List<Map<String, Object>> potential = new ArrayList<>(components.potential());
                potential.addAll(configurations.potential());
                potential.addAll(beans.potential());
                potential.addAll(injectionPoints.potential());
                Map<String, Object> potentialMatches = new LinkedHashMap<>();
                potentialMatches.put("count", potentialTotal);
                potentialMatches.put("locations", potential);
                data.put("potentialMatches", potentialMatches);
            }

            int total = components.total() + configurations.total() + beans.total() + injectionPoints.total();
            int returned = components.matches().size() + configurations.matches().size()
                + beans.matches().size() + injectionPoints.matches().size();

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(returned)
                .truncated(returned < total)
                .suggestedNextTools(List.of(
                    "find_references to trace a specific dependency",
                    "analyze_type for details on a registered component"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * A category's registrations: the matches listed, and the exact count of all of them; and the
     * places the search could not confirm, those listed and the exact count of them.
     */
    private record Category(List<Map<String, Object>> matches, int total,
                            List<Map<String, Object>> potential, int potentialTotal) {
    }

    /**
     * The registrations of {@code annotations}: for each annotation type its first
     * {@code maxResults} matches, and the count of every match of every one of them; and as many
     * of the places the search could not confirm, and the count of them. An annotation type the
     * project's classpath does not hold has no registrations; a failed lookup or search fails the
     * answer, never leaves its registrations out.
     */
    private Category scanAnnotations(IJdtService service, String[][] annotations, int maxResults)
            throws CoreException {
        List<Map<String, Object>> results = new ArrayList<>();
        int total = 0;
        List<Map<String, Object>> potential = new ArrayList<>();
        int potentialTotal = 0;

        for (String[] annotation : annotations) {
            String fqn = annotation[0];
            String label = annotation[1];

            IType annotationType = service.findType(fqn);
            if (annotationType == null) continue;

            SearchResult result = service.getSearchService().findReferences(
                annotationType, ReferenceKind.ANNOTATION, maxResults);
            total += result.totalEncountered();
            for (Map<String, Object> match : formatMatches(result.matches(), service)) {
                match.put("annotation", "@" + label);
                results.add(match);
            }
            potentialTotal += result.potentialTotal();
            for (Map<String, Object> match : formatMatches(result.potentialMatches(), service)) {
                match.put("annotation", "@" + label);
                potential.add(match);
            }
        }

        return new Category(results, total, potential, potentialTotal);
    }
}
