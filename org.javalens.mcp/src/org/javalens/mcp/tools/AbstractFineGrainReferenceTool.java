package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Base for the seven type-keyed fine-grain reference tools
 * ({@code find_casts}, {@code find_instanceof_checks},
 * {@code find_type_instantiations}, {@code find_throws_declarations},
 * {@code find_catch_blocks}, {@code find_type_arguments},
 * {@code find_annotation_usages}). All seven share an identical structure:
 *
 * <ol>
 *   <li>Accept {@code typeName} (required) and {@code maxResults} (optional).</li>
 *   <li>Resolve the type via {@link IJdtService#findType(String)}.</li>
 *   <li>Call {@link SearchService#findReferences(IType, SearchService.ReferenceKind, int)}
 *       with the subclass's declared {@link SearchService.ReferenceKind}.</li>
 *   <li>Format each match into a {@code locations} list and report
 *       {@code totalCount} alongside.</li>
 * </ol>
 *
 * <p>Subclass responsibilities are five concrete protected methods returning
 * the tool's name, description, reference kind, the {@code typeName} description
 * text, and the per-tool advice + suggested next tools. The boilerplate
 * (schema building, type resolution, error handling, response shaping)
 * lives here. This collapses ~115 LOC per tool to ~25 LOC.
 *
 * <p>Output shape is unified per {@code feedback_no_backwards_compat_for_ai_consumers}:
 * every subclass emits {@code locations} (was previously {@code casts}, {@code
 * instanceofChecks}, etc.) and {@code totalCount} (was {@code totalCasts},
 * {@code totalChecks}, etc.).
 */
public abstract class AbstractFineGrainReferenceTool extends AbstractTool {

    protected AbstractFineGrainReferenceTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    /** The category of fine-grain reference search this subclass performs. */
    protected abstract SearchService.ReferenceKind getReferenceKind();

    /** Description text for the {@code typeName} input parameter. */
    protected abstract String getTypeNameParamDescription();

    /** Advice string surfaced in the response when results are non-empty; null = no advice. */
    protected abstract String getAdvice();

    /** Suggested follow-up tools the AI consumer might want to call next. */
    protected abstract List<String> getSuggestedNextTools();

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("typeName", "string", getTypeNameParamDescription())
            .optional("maxResults", "integer", "Maximum results to return (default 100)")
            .build();
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

            SearchResult result = service.getSearchService()
                .findReferences(type, getReferenceKind(), maxResults);
            List<Map<String, Object>> locations = formatMatches(result.matches(), service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalCount", result.totalEncountered());
            data.put("locations", locations);

            String advice = getAdvice();
            if (advice != null && !locations.isEmpty()) {
                data.put("advice", advice);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(result.totalEncountered())
                .returnedCount(locations.size())
                .truncated(result.truncated())
                .suggestedNextTools(getSuggestedNextTools())
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
