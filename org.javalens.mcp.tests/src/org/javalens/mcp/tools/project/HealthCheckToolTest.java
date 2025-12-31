package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolWithProject = new HealthCheckTool(() -> true, () -> 56);
        toolWithoutProject = new HealthCheckTool(() -> false, () -> 56);
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
        assertNotNull(data.get("message"));
        assertNotNull(data.get("version"));
        assertNotNull(data.get("uptime"));

        // Project info
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertTrue((Boolean) project.get("loaded"));

        // Java/OS info
        assertNotNull(data.get("java"));
        assertNotNull(data.get("os"));

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
}
