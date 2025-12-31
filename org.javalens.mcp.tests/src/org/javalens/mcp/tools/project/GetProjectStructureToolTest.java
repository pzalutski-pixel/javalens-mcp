package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetProjectStructureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetProjectStructureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetProjectStructureTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetProjectStructureTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete project structure")
    void returnsCompleteProjectStructure() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify project info
        assertNotNull(data.get("projectName"));
        assertNotNull(data.get("projectRoot"));
        assertTrue((Integer) data.get("totalPackages") > 0);
        assertTrue((Integer) data.get("totalFiles") > 0);

        // Verify source roots
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceRoots = (List<Map<String, Object>>) data.get("sourceRoots");
        assertFalse(sourceRoots.isEmpty());

        Map<String, Object> srcRoot = sourceRoots.get(0);
        assertNotNull(srcRoot.get("path"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packages = (List<Map<String, Object>>) srcRoot.get("packages");
        assertFalse(packages.isEmpty());
        assertNotNull(packages.get(0).get("name"));
        assertNotNull(packages.get(0).get("fileCount"));
    }

    @Test @DisplayName("supports optional parameters")
    void supportsOptionalParameters() {
        // Test includeFiles
        ObjectNode withFiles = objectMapper.createObjectNode();
        withFiles.put("includeFiles", true);
        ToolResponse r1 = tool.execute(withFiles);
        assertTrue(r1.isSuccess());

        // Test maxDepth
        ObjectNode withDepth = objectMapper.createObjectNode();
        withDepth.put("maxDepth", 2);
        assertTrue(tool.execute(withDepth).isSuccess());
    }

    @Test @DisplayName("works with no parameters")
    void worksWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }
}
