package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
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
    private final Instant startTime;

    public HealthCheckTool(Supplier<Boolean> projectLoadedSupplier,
                           Supplier<Integer> toolCountSupplier) {
        this.projectLoadedSupplier = projectLoadedSupplier;
        this.toolCountSupplier = toolCountSupplier;
        this.startTime = Instant.now();
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        Map<String, Object> status = new LinkedHashMap<>();

        // Basic status
        status.put("status", projectLoadedSupplier.get() ? "Ready" : "Waiting for project");
        status.put("message", "JavaLens MCP Server is operational");
        status.put("version", "2.0.0-SNAPSHOT");
        status.put("startedAt", startTime.toString());
        status.put("uptime", getUptimeString());

        // Project status
        if (projectLoadedSupplier.get()) {
            status.put("project", Map.of(
                "loaded", true,
                "message", "Project loaded successfully"
            ));
        } else {
            status.put("project", Map.of(
                "loaded", false,
                "message", "No project loaded. Use load_project to load a Java project."
            ));
        }

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
