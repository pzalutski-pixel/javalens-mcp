package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.HealthCheckTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckToolTest {

    private HealthCheckTool toolWithProject;
    private HealthCheckTool toolWithoutProject;
    private HealthCheckTool toolLoading;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolWithProject = new HealthCheckTool(() -> true, () -> 56,
            () -> ProjectLoadingState.LOADED, () -> null, () -> null);
        toolWithoutProject = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.NOT_LOADED, () -> null, () -> null);
        toolLoading = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.LOADING, () -> null, () -> null);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete health status when project loaded")
    void returnsCompleteHealthStatusWithProject() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Status
        assertEquals("Ready", data.get("status"));
        String message = (String) data.get("message");
        assertNotNull(message, "message missing");
        assertFalse(message.isBlank(), "message non-blank; got: " + data);
        String version = (String) data.get("version");
        assertNotNull(version, "version missing");
        assertFalse(version.isBlank(), "version non-blank; got: " + data);
        assertNotNull(data.get("uptime"), "uptime missing");

        // Project info
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertTrue((Boolean) project.get("loaded"));

        // Java/OS info — both maps must be present
        @SuppressWarnings("unchecked")
        Map<String, Object> java = (Map<String, Object>) data.get("java");
        assertNotNull(java, "java block missing");
        assertFalse(java.isEmpty(), "java block non-empty; got: " + data);
        @SuppressWarnings("unchecked")
        Map<String, Object> os = (Map<String, Object>) data.get("os");
        assertNotNull(os, "os block missing");
        assertFalse(os.isEmpty(), "os block non-empty; got: " + data);

        // Capabilities
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) data.get("capabilities");
        assertTrue((Boolean) capabilities.get("findReferences"));
        assertTrue((Boolean) capabilities.get("refactoring"));

        // Tool count
        assertEquals(56, data.get("toolCount"));
    }

    @Test @DisplayName("returns waiting status when no project loaded")
    void returnsWaitingStatusWithoutProject() {
        ToolResponse r = toolWithoutProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Waiting for project", data.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertFalse((Boolean) project.get("loaded"));
    }

    @Test @DisplayName("always succeeds with no parameters")
    void alwaysSucceedsWithNoParameters() {
        assertTrue(toolWithProject.execute(null).isSuccess());
        assertTrue(toolWithoutProject.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("LOADING state reports status='Loading project...' and project.loaded=false")
    @SuppressWarnings("unchecked")
    void loadingState_reportsLoadingStatus() {
        ToolResponse r = toolLoading.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertEquals("Loading project...", data.get("status"));
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertEquals(false, project.get("loaded"),
            "During LOADING, project.loaded must be false; got: " + project);
        assertEquals("loading", project.get("status"),
            "Inner project.status must be lowercase enum name; got: " + project);
        assertNotNull(project.get("message"),
            "Loading state must carry an explanatory message");
    }

    @Test
    @DisplayName("FAILED state reports status='Project load failed' and embeds error message")
    @SuppressWarnings("unchecked")
    void failedState_embedsLoadErrorInProjectMessage() {
        // Construct a tool fixed to FAILED with a known error message; verify the error
        // text is surfaced verbatim in project.message.
        HealthCheckTool failedTool = new HealthCheckTool(
            () -> false, () -> 56,
            () -> ProjectLoadingState.FAILED,
            () -> "Maven subprocess exited with code 1", () -> null);

        ToolResponse r = failedTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertEquals("Project load failed", data.get("status"));
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertEquals(false, project.get("loaded"));
        assertEquals("failed", project.get("status"));
        assertTrue(project.get("message").toString().contains("Maven subprocess exited with code 1"),
            "FAILED message must include the error text from the supplier; got: " + project);
    }

    @Test
    @DisplayName("project.status field reports the lowercase enum name for each loading state")
    @SuppressWarnings("unchecked")
    void projectStatusField_isLowercaseEnumName() {
        // NOT_LOADED → "not_loaded"
        Map<String, Object> notLoadedProject = (Map<String, Object>) getData(
            toolWithoutProject.execute(objectMapper.createObjectNode())).get("project");
        assertEquals("not_loaded", notLoadedProject.get("status"));

        // LOADED → "loaded"
        Map<String, Object> loadedProject = (Map<String, Object>) getData(
            toolWithProject.execute(objectMapper.createObjectNode())).get("project");
        assertEquals("loaded", loadedProject.get("status"));
    }

    @Test
    @DisplayName("capabilities map carries all five documented feature flags set to true")
    @SuppressWarnings("unchecked")
    void capabilities_carriesAllFiveDocumentedFlags() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> capabilities =
            (Map<String, Object>) getData(r).get("capabilities");
        assertNotNull(capabilities);

        // All five must be present AND true. A regression that flipped any to false
        // or dropped a key would mislead the AI agent about available features.
        assertEquals(Boolean.TRUE, capabilities.get("findReferences"));
        assertEquals(Boolean.TRUE, capabilities.get("findImplementations"));
        assertEquals(Boolean.TRUE, capabilities.get("typeHierarchy"));
        assertEquals(Boolean.TRUE, capabilities.get("refactoring"));
        assertEquals(Boolean.TRUE, capabilities.get("diagnostics"));
    }

    @Test
    @DisplayName("configuration map carries timeoutSeconds (default 30) and absolutePaths boolean")
    @SuppressWarnings("unchecked")
    void configuration_carriesTimeoutAndAbsolutePathsFlag() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> configuration =
            (Map<String, Object>) getData(r).get("configuration");
        assertNotNull(configuration);

        // timeoutSeconds defaults to 30 when JAVALENS_TIMEOUT_SECONDS env var is unset.
        // It's clamped to [5, 300] when set. The default-30 contract is what
        // documentation-facing AI agents rely on.
        Object timeout = configuration.get("timeoutSeconds");
        assertNotNull(timeout, "timeoutSeconds must be present");
        int t = ((Number) timeout).intValue();
        assertTrue(t >= 5 && t <= 300,
            "timeoutSeconds must be in [5, 300] (clamped); got: " + t);

        // absolutePaths is a boolean flag from JAVALENS_ABSOLUTE_PATHS env var.
        Object absolutePaths = configuration.get("absolutePaths");
        assertNotNull(absolutePaths, "absolutePaths must be present");
        assertTrue(absolutePaths instanceof Boolean,
            "absolutePaths must be a boolean; got " + absolutePaths.getClass());
    }

    @Test
    @DisplayName("response includes startedAt timestamp and java/os info maps")
    @SuppressWarnings("unchecked")
    void response_includesStartedAtAndJavaOsInfo() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertNotNull(data.get("startedAt"),
            "startedAt timestamp must be present");

        Map<String, Object> java = (Map<String, Object>) data.get("java");
        assertNotNull(java.get("version"),
            "java.version must be reported; got: " + java);
        assertNotNull(java.get("vendor"),
            "java.vendor must be reported; got: " + java);

        Map<String, Object> os = (Map<String, Object>) data.get("os");
        assertNotNull(os.get("name"),
            "os.name must be reported; got: " + os);
        assertNotNull(os.get("arch"),
            "os.arch must be reported; got: " + os);
    }

    @Test
    @DisplayName("uptime is a non-empty string in seconds/minutes/hours format")
    void uptime_isFormattedString() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        String uptime = (String) getData(r).get("uptime");
        assertNotNull(uptime);
        assertTrue(uptime.endsWith("seconds") || uptime.endsWith("minutes") || uptime.endsWith("hours"),
            "uptime must end with the bucket unit (seconds/minutes/hours); got: " + uptime);
    }
}
