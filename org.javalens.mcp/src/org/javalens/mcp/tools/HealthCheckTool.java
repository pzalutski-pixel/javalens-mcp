package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.models.ToolResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Health check tool for verifying server status.
 * Adapted from src/main/java/dev/javalens/tools/HealthCheckTool.java
 *
 * USAGE: Call on startup to verify server is operational
 * OUTPUT: Server status, project info if loaded, capabilities
 */
public class HealthCheckTool implements Tool {

    private final Supplier<Boolean> projectLoadedSupplier;
    private final Supplier<Integer> toolCountSupplier;
    private final Supplier<ProjectLoadingState> loadingStateSupplier;
    private final Supplier<String> loadingErrorSupplier;
    private final Supplier<org.javalens.core.IJdtService> serviceSupplier;
    private final Instant startTime;

    public HealthCheckTool(Supplier<Boolean> projectLoadedSupplier,
                           Supplier<Integer> toolCountSupplier,
                           Supplier<ProjectLoadingState> loadingStateSupplier,
                           Supplier<String> loadingErrorSupplier,
                           Supplier<org.javalens.core.IJdtService> serviceSupplier) {
        this.projectLoadedSupplier = projectLoadedSupplier;
        this.toolCountSupplier = toolCountSupplier;
        this.loadingStateSupplier = loadingStateSupplier;
        this.loadingErrorSupplier = loadingErrorSupplier;
        this.serviceSupplier = serviceSupplier;
        this.startTime = Instant.now();
    }

    /** Convenience for tests: only the service supplier matters. */
    public HealthCheckTool(Supplier<org.javalens.core.IJdtService> serviceSupplier) {
        this(() -> serviceSupplier.get() != null, () -> 0,
            () -> serviceSupplier.get() != null ? ProjectLoadingState.LOADED : ProjectLoadingState.NOT_LOADED,
            () -> null, serviceSupplier);
    }

    @Override
    public String getName() {
        return "health_check";
    }

    @Override
    public String getDescription() {
        return """
            Check server status and project state.

            USAGE: Call on startup to verify server is operational.
            OUTPUT: Server status, project info if loaded, capabilities.

            WORKFLOW:
            1. Call health_check to verify server is running
            2. If no project loaded, call load_project next
            3. Use returned capabilities to understand available features
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object().build();
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        Map<String, Object> status = new LinkedHashMap<>();
        ProjectLoadingState loadingState = loadingStateSupplier.get();

        // Basic status based on loading state
        String statusMessage = switch (loadingState) {
            case NOT_LOADED -> "Waiting for project";
            case LOADING -> "Loading project...";
            case LOADED -> "Ready";
            case FAILED -> "Project load failed";
        };
        status.put("status", statusMessage);
        status.put("message", "JavaLens MCP Server is operational");
        status.put("version", org.javalens.mcp.protocol.McpProtocolHandler.serverVersion());
        status.put("startedAt", startTime.toString());
        status.put("uptime", getUptimeString());

        // Active disk-sync integrity contract (#26): strict = every answer
        // content-verified against disk; manual = caller drives sync.
        org.javalens.core.IJdtService service = serviceSupplier.get();
        status.put("diskSync", service != null
            ? service.getDiskSyncMode().toConfigValue()
            : org.javalens.core.sync.DiskSyncMode.fromEnvironment(
                System.getenv("JAVALENS_DISK_SYNC")).toConfigValue());

        // Project status with detailed loading state
        Map<String, Object> projectStatus = new LinkedHashMap<>();
        projectStatus.put("status", loadingState.name().toLowerCase());

        switch (loadingState) {
            case NOT_LOADED -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "No project loaded. Use load_project to load a Java project.");
            }
            case LOADING -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "Project is loading, please wait...");
            }
            case LOADED -> {
                projectStatus.put("loaded", true);
                projectStatus.put("message", "Project loaded successfully");
            }
            case FAILED -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "Project failed to load: " + loadingErrorSupplier.get());
            }
        }
        status.put("project", projectStatus);

        // Java/OS info
        status.put("java", Map.of(
            "version", System.getProperty("java.version"),
            "vendor", System.getProperty("java.vendor")
        ));
        status.put("os", Map.of(
            "name", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch")
        ));

        // Capabilities
        status.put("capabilities", Map.of(
            "findReferences", true,
            "findImplementations", true,
            "typeHierarchy", true,
            "refactoring", true,
            "diagnostics", true
        ));

        // Configuration
        status.put("configuration", Map.of(
            "timeoutSeconds", getTimeoutSeconds(),
            "absolutePaths", useAbsolutePaths()
        ));

        // Tool count
        status.put("toolCount", toolCountSupplier.get());

        return ToolResponse.success(status);
    }

    private String getUptimeString() {
        long seconds = Duration.between(startTime, Instant.now()).getSeconds();
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            return (seconds / 60) + " minutes";
        } else {
            return (seconds / 3600) + " hours";
        }
    }

    private int getTimeoutSeconds() {
        String timeout = System.getenv("JAVALENS_TIMEOUT_SECONDS");
        if (timeout == null) {
            return 30;
        }
        try {
            int value = Integer.parseInt(timeout);
            return Math.min(Math.max(value, 5), 300);  // Clamp 5-300
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private boolean useAbsolutePaths() {
        return "true".equalsIgnoreCase(System.getenv("JAVALENS_ABSOLUTE_PATHS"));
    }
}
