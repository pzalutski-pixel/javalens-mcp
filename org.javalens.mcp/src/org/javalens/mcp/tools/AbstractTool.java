package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
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
        return executeWithService(service, arguments);
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
            ICompilationUnit cu = null;

            // Try to get ICompilationUnit from the match element
            Object element = match.getElement();
            if (element instanceof org.eclipse.jdt.core.IType type) {
                // For IType, use getCompilationUnit() directly (handles source types properly)
                cu = type.getCompilationUnit();
            } else if (element instanceof org.eclipse.jdt.core.IMember member) {
                // For methods/fields, get the CU from the declaring type
                cu = member.getCompilationUnit();
            } else if (element instanceof IJavaElement javaElement) {
                // Fallback to ancestor traversal
                cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
            }

            // For TypeReferenceMatch, also check local element if main element didn't give us a CU
            if (cu == null && match instanceof org.eclipse.jdt.core.search.TypeReferenceMatch typeRefMatch) {
                IJavaElement localElement = typeRefMatch.getLocalElement();
                if (localElement != null) {
                    if (localElement instanceof org.eclipse.jdt.core.IMember member) {
                        cu = member.getCompilationUnit();
                    } else {
                        cu = (ICompilationUnit) localElement.getAncestor(IJavaElement.COMPILATION_UNIT);
                    }
                }
            }

            // Get file path - prefer from ICompilationUnit for accurate path
            if (cu != null && cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            } else if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Offset and length
            info.put("offset", match.getOffset());
            info.put("length", match.getLength());

            // Line, column, and context (requires ICompilationUnit)
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
