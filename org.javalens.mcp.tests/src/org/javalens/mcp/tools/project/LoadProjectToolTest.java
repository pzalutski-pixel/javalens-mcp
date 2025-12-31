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
}
