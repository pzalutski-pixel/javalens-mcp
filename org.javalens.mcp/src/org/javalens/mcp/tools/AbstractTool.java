package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.exceptions.ProjectNotLoadedException;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Abstract base class for JavaLens tools.
 * Provides common functionality to reduce boilerplate in tool implementations.
 *
 * <p>Subclasses should:
 * <ul>
 *   <li>Call super constructor with the service supplier</li>
 *   <li>Implement getName(), getDescription(), getInputSchema()</li>
 *   <li>Override executeWithService() instead of execute()</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyTool extends AbstractTool {
 *     public MyTool(Supplier<IJdtService> serviceSupplier) {
 *         super(serviceSupplier);
 *     }
 *
 *     @Override
 *     protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
 *         // Tool implementation - service is guaranteed non-null
 *         return ToolResponse.success(data);
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractTool implements Tool {

    protected final Supplier<IJdtService> serviceSupplier;

    protected AbstractTool(Supplier<IJdtService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    /**
     * Get the IJdtService, or null if no project is loaded.
     * Prefer using executeWithService() which handles the null check.
     */
    protected IJdtService getService() {
        return serviceSupplier.get();
    }

    /**
     * Get the IJdtService, throwing if no project is loaded.
     *
     * @throws ProjectNotLoadedException if no project has been loaded
     */
    protected IJdtService requireService() {
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            throw new ProjectNotLoadedException();
        }
        return service;
    }

    /**
     * Default execute implementation that checks for loaded project
     * then delegates to executeWithService.
     *
     * <p>Handles three cases:
     * <ul>
     *   <li>Project is loading - returns "loading" message with hint to check health_check</li>
     *   <li>Project load failed - returns error with the failure reason</li>
     *   <li>No project loaded - returns standard "project not loaded" error</li>
     * </ul>
     *
     * <p>When {@code JAVALENS_TIMEOUT_SECONDS} sets a time limit, a call that passes it answers
     * exactly TIMEOUT ({@link #executeWithinDeadline}); unset, no call is limited.
     *
     * <p>With a project loaded, every response carries {@code meta.indexIncomplete} naming the
     * files whose declarations the search index is verified to be missing, for as long as it
     * misses them, and {@code meta.libraryIndexIncomplete} naming the libraries whose index the
     * load could not build, so no answer drawn from the index omits them silently.
     */
    @Override
    public ToolResponse execute(JsonNode arguments) {
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            // Check loading state to provide more specific feedback
            ProjectLoadingState loadingState = JavaLensApplication.getLoadingState();
            return switch (loadingState) {
                case LOADING -> ToolResponse.projectLoading();
                case FAILED -> ToolResponse.projectLoadFailed(JavaLensApplication.getLoadingError());
                default -> ToolResponse.projectNotLoaded();
            };
        }

        ToolResponse response = executeFresh(service, arguments);
        List<String> indexIncomplete = service.getIndexIncompleteFiles();
        if (!indexIncomplete.isEmpty()) {
            response = response.withIndexIncomplete(indexIncomplete);
        }
        List<String> libraryIndexIncomplete = service.getLibraryIndexIncomplete();
        if (!libraryIndexIncomplete.isEmpty()) {
            response = response.withLibraryIndexIncomplete(libraryIndexIncomplete);
        }
        return response;
    }

    /**
     * Run executeWithService once the service has verified its model against disk and
     * repaired what changed, so the answer is true of disk at query time.
     */
    private ToolResponse executeFresh(IJdtService service, JsonNode arguments) {
        // #26: verify the model against disk and repair the delta before any
        // tool logic runs. Every answer from executeWithService is true of
        // disk at query time; an unverifiable model never answers.
        try {
            service.ensureFresh();
        } catch (org.javalens.core.exceptions.ReloadRequiredException e) {
            return ToolResponse.reloadRequired(e.getMessage());
        } catch (Exception e) {
            return ToolResponse.verificationFailed(e.getMessage());
        }

        return executeWithinDeadline(service, arguments);
    }

    /**
     * Run executeWithService within the call's deadline when a time limit is set. The deadline
     * starts after the disk sync, so only the tool's own work counts against it, and JDT stops
     * that work at its first cancellation check past the limit. A deadline that has expired
     * discards whatever the call answered, partial or failed, and the call answers TIMEOUT.
     * A tool called from within another tool's call runs within the outer call's deadline,
     * which the outer call enforces.
     */
    private ToolResponse executeWithinDeadline(IJdtService service, JsonNode arguments) {
        Deadline deadline = Deadline.current() == null ? service.newDeadline() : null;
        if (deadline == null) {
            return executeWithService(service, arguments);
        }
        ToolResponse response;
        try {
            response = deadline.within(() -> executeWithService(service, arguments));
        } catch (RuntimeException e) {
            if (!deadline.expired()) {
                throw e;
            }
            response = null;
        }
        return deadline.expired() ? ToolResponse.timeout(getName(), deadline.seconds()) : response;
    }

    /**
     * Execute the tool with a guaranteed non-null IJdtService.
     * Subclasses should override this instead of execute().
     *
     * @param service The IJdtService (guaranteed non-null)
     * @param arguments The tool arguments
     * @return The tool response
     */
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Default implementation for backwards compatibility
        // Subclasses should override this method
        throw new UnsupportedOperationException(
            "Subclass must override executeWithService() or execute()");
    }

    // Common helper methods for parameter extraction

    /**
     * Get a required string parameter.
     *
     * @param arguments The arguments node
     * @param name The parameter name
     * @return The parameter value, or null if missing
     */
    protected String getStringParam(JsonNode arguments, String name) {
        if (arguments == null || !arguments.has(name)) {
            return null;
        }
        return arguments.get(name).asText();
    }

    /**
     * Get an optional string parameter with default.
     */
    protected String getStringParam(JsonNode arguments, String name, String defaultValue) {
        String value = getStringParam(arguments, name);
        return value != null ? value : defaultValue;
    }

    /**
     * Get an optional int parameter with default.
     */
    protected int getIntParam(JsonNode arguments, String name, int defaultValue) {
        if (arguments == null || !arguments.has(name)) {
            return defaultValue;
        }
        return arguments.get(name).asInt(defaultValue);
    }

    /**
     * Get an optional boolean parameter with default.
     */
    protected boolean getBooleanParam(JsonNode arguments, String name, boolean defaultValue) {
        if (arguments == null || !arguments.has(name)) {
            return defaultValue;
        }
        return arguments.get(name).asBoolean(defaultValue);
    }

    /**
     * Check if a required parameter is present.
     */
    protected ToolResponse requireParam(JsonNode arguments, String name) {
        if (arguments == null || !arguments.has(name)) {
            return ToolResponse.invalidParameter(name, "Required parameter missing");
        }
        return null; // No error
    }

    // ========== SearchMatch formatting helpers ==========

    /**
     * The description every tool whose results come from JDT's search gives its potentialMatches
     * block ({@link #potentialMatches}).
     */
    protected static final String POTENTIAL_MATCHES = """
        potentialMatches, present only when there are any, holds the places JDT's
        search reports as possible matches but cannot confirm, because a type or
        name there does not resolve (a missing dependency or a compile error):
        count is their exact number, and locations lists them, as many as the
        results it stands beside may list. They are never counted or listed as
        results.

        """;

    /**
     * The potentialMatches block for the places a search reported but could not confirm: the
     * exact count of them, {@code total}, and the locations of {@code listed}, those kept; null
     * when there are none.
     */
    protected Map<String, Object> potentialMatches(List<SearchMatch> listed, int total, IJdtService service) {
        if (total == 0) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("count", total);
        block.put("locations", formatMatches(listed, service));
        return block;
    }

    /**
     * The potentialMatches block of {@code hierarchy}'s potential subtypes, the first {@code limit}
     * of them listed; null when there are none.
     */
    protected Map<String, Object> potentialSubtypes(org.javalens.core.search.ModelHierarchy hierarchy, int limit,
                                                    IJdtService service) throws org.eclipse.core.runtime.CoreException {
        List<SearchMatch> potential = hierarchy.potentialSubtypes();
        return potentialMatches(potential.subList(0, Math.max(0, Math.min(limit, potential.size()))),
            potential.size(), service);
    }

    /**
     * The supertypes the compiler cannot resolve in {@code hierarchy}, each its name as written and
     * the type whose declaration names it ({@code declaringType}, fully qualified); null when every
     * supertype resolves.
     */
    protected static List<Map<String, Object>> unresolvedSupertypes(org.javalens.core.search.ModelHierarchy hierarchy) {
        if (hierarchy.unresolvedSupertypes().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (org.javalens.core.search.ModelHierarchy.UnresolvedSupertype supertype : hierarchy.unresolvedSupertypes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", supertype.name());
            entry.put("declaringType", ElementNames.qualifiedName(supertype.declaringType()));
            unresolved.add(entry);
        }
        return unresolved;
    }

    /** Puts the potentialMatches block of {@code result} into {@code data} when it has any. */
    protected void putPotentialMatches(Map<String, Object> data, org.javalens.core.search.SearchResult result,
                                       IJdtService service) {
        Map<String, Object> block = potentialMatches(result.potentialMatches(), result.potentialTotal(), service);
        if (block != null) {
            data.put("potentialMatches", block);
        }
    }

    /**
     * Format a list of SearchMatch results into structured output.
     * Extracts file path, line, column, and context for each match.
     *
     * @param matches The search matches to format
     * @param service The JDT service for path and position resolution
     * @return List of formatted match info maps
     */
    protected List<Map<String, Object>> formatMatches(List<SearchMatch> matches, IJdtService service) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (SearchMatch match : matches) {
            Map<String, Object> info = formatMatch(match, service);
            if (info != null) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * Format a single SearchMatch into structured output.
     *
     * @param match The search match to format
     * @param service The JDT service for path and position resolution
     * @return Formatted match info map, or null if formatting fails
     */
    protected Map<String, Object> formatMatch(SearchMatch match, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            ICompilationUnit cu = MatchResolver.resolveCu(match);

            // Prefer the CU's resource path; fall back to the raw match resource when
            // the CU couldn't be resolved (binary JDK matches and similar edge cases).
            IPath location = null;
            if (cu != null && cu.getResource() != null) {
                location = cu.getResource().getLocation();
            } else if (match.getResource() != null) {
                location = match.getResource().getLocation();
            }
            if (location != null) {
                info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }

            info.put("offset", match.getOffset());
            info.put("length", match.getLength());

            if (cu != null && match.getOffset() >= 0) {
                info.put("line", service.getLineNumber(cu, match.getOffset()));
                info.put("column", service.getColumnNumber(cu, match.getOffset()));
                String context = service.getContextLine(cu, match.getOffset());
                if (context != null && !context.isEmpty()) {
                    info.put("context", context.trim());
                }
            }

            return info;
        } catch (Exception e) {
            return null;
        }
    }
}
