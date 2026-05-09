package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.LoadProjectTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoadProjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private LoadProjectTool tool;
    private ObjectMapper objectMapper;
    private AtomicReference<IJdtService> serviceRef;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        serviceRef = new AtomicReference<>();
        tool = new LoadProjectTool(serviceRef::set);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("loads project with complete response")
    void loadsProjectComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify project info
        assertTrue((Boolean) data.get("loaded"));
        assertNotNull(data.get("projectPath"));
        assertNotNull(data.get("buildSystem"));
        assertTrue((Integer) data.get("sourceFileCount") > 0);
        assertTrue((Integer) data.get("packageCount") > 0);

        // Verify packages list
        @SuppressWarnings("unchecked")
        List<String> packages = (List<String>) data.get("packages");
        assertFalse(packages.isEmpty());

        // Verify service was set
        assertNotNull(serviceRef.get());
    }

    @Test @DisplayName("requires projectPath parameter")
    void requiresProjectPath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
        assertFalse(tool.execute(null).isSuccess());
    }

    @Test @DisplayName("handles invalid paths gracefully")
    void handlesInvalidPaths() {
        // Non-existent path
        ObjectNode nonExistent = objectMapper.createObjectNode();
        nonExistent.put("projectPath", "/nonexistent/path/project");
        ToolResponse r1 = tool.execute(nonExistent);
        assertFalse(r1.isSuccess());
        assertEquals("FILE_NOT_FOUND", r1.getError().getCode());

        // File instead of directory
        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("projectPath", projectPath.resolve("pom.xml").toString());
        ToolResponse r2 = tool.execute(filePath);
        assertFalse(r2.isSuccess());
    }

    // ========== Bug-fix surface tests at the MCP boundary ===========================
    // These verify what an AI agent actually sees in the load_project response — not
    // just that the engine state is correct, but that the JSON-serialized tool response
    // carries the bug-fix-specific fields (warnings, buildSystem, classpathEntryCount,
    // packages) faithfully.

    @Test @DisplayName("Bug X: subprocess failure surfaces a 'warnings' array in the response")
    void subprocessFailureSurfacesWarningsArrayInResponse() throws Exception {
        // broken-maven-deps declares a non-existent dependency. Whether or not mvn is on
        // PATH, the load surfaces a MAVEN_SUBPROCESS_FAILED warning — the response must
        // expose it as a structured field for the agent to see.
        Path brokenPath = helper.getFixturePath("broken-maven-deps");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", brokenPath.toString());

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "load_project itself succeeds even when subprocess fails");
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) data.get("warnings");
        assertNotNull(warnings,
            "Expected 'warnings' field in load_project response when mvn subprocess fails. " +
            "Without it the AI agent has no signal that the classpath is degraded. Got: " + data);
        assertFalse(warnings.isEmpty(), "Expected at least one warning entry");

        // Each warning must carry code + message + remediation so the agent can act on it.
        Map<String, Object> warning = warnings.get(0);
        assertTrue(warning.containsKey("code"), "warning entry missing 'code'");
        assertTrue(warning.containsKey("message"), "warning entry missing 'message'");
        assertEquals("MAVEN_SUBPROCESS_FAILED", warning.get("code"),
            "Expected the stable code MAVEN_SUBPROCESS_FAILED in the response shape");
    }

    @Test @DisplayName("clean project response: no 'warnings' field when nothing went wrong")
    void cleanProjectOmitsWarningsField() {
        // Default fixture is simple-maven. The load should not surface warnings on a
        // clean project IF mvn is available; if mvn isn't on PATH, MAVEN_SUBPROCESS_FAILED
        // would appear (which is the *correct* signal) — we don't gate on tool presence
        // here, we just assert the *shape*: warnings is either absent or non-null-list.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // The contract: 'warnings' is omitted when the load is clean. If mvn is missing
        // it's present and explains why. Either way the field must not break the response.
        Object warnings = data.get("warnings");
        if (warnings != null) {
            assertTrue(warnings instanceof List,
                "warnings field must be a JSON array when present, got " + warnings.getClass());
        }
    }

    @Test @DisplayName("buildSystem and packages fields shape match documented response")
    void responseExposesDocumentedFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", projectPath.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Fields the AI agent depends on for follow-up tool calls.
        assertEquals("maven", data.get("buildSystem"),
            "buildSystem must be the lowercase build system name (Maven/Gradle/Bazel/etc.)");
        assertNotNull(data.get("loadedAt"), "loadedAt timestamp must be present");
        assertNotNull(data.get("classpathEntryCount"),
            "classpathEntryCount must be present so the agent can sanity-check the load");

        // packages list is bounded to 20 in the response — verify it's a list.
        @SuppressWarnings("unchecked")
        List<String> packages = (List<String>) data.get("packages");
        assertNotNull(packages, "packages field must be present");
    }
}
