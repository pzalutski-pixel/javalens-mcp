package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
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
 * Get project classpath information.
 * Returns source folders, libraries, and classpath containers.
 */
public class GetClasspathInfoTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetClasspathInfoTool.class);

    public GetClasspathInfoTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_classpath_info";
    }

    @Override
    public String getDescription() {
        return """
            Get project classpath information.

            USAGE: Call to get all classpath entries for the loaded project
            OUTPUT: Source folders, libraries, and classpath containers

            Useful for understanding project structure and dependencies.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "includeLibraries", Map.of(
                "type", "boolean",
                "description", "Include library entries (default true)"
            ),
            "includeSource", Map.of(
                "type", "boolean",
                "description", "Include source folder entries (default true)"
            ),
            "includeContainers", Map.of(
                "type", "boolean",
                "description", "Include container entries like JRE (default true)"
            )
        ));
        return schema;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        boolean includeLibraries = getBooleanParam(arguments, "includeLibraries", true);
        boolean includeSource = getBooleanParam(arguments, "includeSource", true);
        boolean includeContainers = getBooleanParam(arguments, "includeContainers", true);

        try {
            IJavaProject project = service.getJavaProject();
            IClasspathEntry[] entries = project.getRawClasspath();

            List<Map<String, Object>> sourceFolders = new ArrayList<>();
            List<Map<String, Object>> libraries = new ArrayList<>();
            List<Map<String, Object>> containers = new ArrayList<>();
            List<Map<String, Object>> projects = new ArrayList<>();
            List<Map<String, Object>> variables = new ArrayList<>();

            for (IClasspathEntry entry : entries) {
                Map<String, Object> entryInfo = createEntryInfo(entry, service);

                switch (entry.getEntryKind()) {
                    case IClasspathEntry.CPE_SOURCE:
                        if (includeSource) {
                            sourceFolders.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_LIBRARY:
                        if (includeLibraries) {
                            libraries.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_CONTAINER:
                        if (includeContainers) {
                            containers.add(entryInfo);
                        }
                        break;
                    case IClasspathEntry.CPE_PROJECT:
                        projects.add(entryInfo);
                        break;
                    case IClasspathEntry.CPE_VARIABLE:
                        variables.add(entryInfo);
                        break;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("projectName", project.getElementName());
            data.put("projectRoot", service.getProjectRoot().toString());

            if (includeSource) {
                data.put("sourceFolders", sourceFolders);
            }
            if (includeLibraries) {
                data.put("libraries", libraries);
            }
            if (includeContainers) {
                data.put("containers", containers);
            }
            if (!projects.isEmpty()) {
                data.put("projectDependencies", projects);
            }
            if (!variables.isEmpty()) {
                data.put("variables", variables);
            }

            // Output folder
            try {
                data.put("outputLocation", project.getOutputLocation().toString());
            } catch (JavaModelException e) {
                log.debug("Could not get output location: {}", e.getMessage());
            }

            int totalEntries = sourceFolders.size() + libraries.size() + containers.size() +
                              projects.size() + variables.size();
            data.put("totalEntries", totalEntries);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(entries.length)
                .returnedCount(totalEntries)
                .suggestedNextTools(List.of(
                    "get_project_structure to see package hierarchy",
                    "search_symbols to find types in the project",
                    "get_document_symbols to explore a source file"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting classpath info: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createEntryInfo(IClasspathEntry entry, IJdtService service) {
        Map<String, Object> info = new LinkedHashMap<>();

        String path = entry.getPath().toString();
        info.put("path", path);

        String kind = switch (entry.getEntryKind()) {
            case IClasspathEntry.CPE_SOURCE -> "source";
            case IClasspathEntry.CPE_LIBRARY -> "library";
            case IClasspathEntry.CPE_CONTAINER -> "container";
            case IClasspathEntry.CPE_PROJECT -> "project";
            case IClasspathEntry.CPE_VARIABLE -> "variable";
            default -> "unknown";
        };
        info.put("kind", kind);

        // Add additional info for different types
        if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
            // Output location for source folder
            if (entry.getOutputLocation() != null) {
                info.put("outputLocation", entry.getOutputLocation().toString());
            }

            // Exclusion patterns
            if (entry.getExclusionPatterns().length > 0) {
                List<String> exclusions = new ArrayList<>();
                for (var pattern : entry.getExclusionPatterns()) {
                    exclusions.add(pattern.toString());
                }
                info.put("exclusions", exclusions);
            }

            // Inclusion patterns
            if (entry.getInclusionPatterns().length > 0) {
                List<String> inclusions = new ArrayList<>();
                for (var pattern : entry.getInclusionPatterns()) {
                    inclusions.add(pattern.toString());
                }
                info.put("inclusions", inclusions);
            }
        }

        if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
            // Source attachment
            if (entry.getSourceAttachmentPath() != null) {
                info.put("sourceAttachment", entry.getSourceAttachmentPath().toString());
            }
        }

        // Export flag
        if (entry.isExported()) {
            info.put("exported", true);
        }

        return info;
    }
}
