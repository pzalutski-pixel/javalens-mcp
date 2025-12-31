package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetClasspathInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetClasspathInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetClasspathInfoTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetClasspathInfoTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete classpath info")
    void returnsCompleteClasspathInfo() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify project info
        assertNotNull(data.get("projectName"));
        assertNotNull(data.get("projectRoot"));
        assertNotNull(data.get("totalEntries"));

        // Verify source folders
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceFolders = (List<Map<String, Object>>) data.get("sourceFolders");
        assertNotNull(sourceFolders);
        assertFalse(sourceFolders.isEmpty());
        assertEquals("source", sourceFolders.get(0).get("kind"));

        // Verify containers (JRE)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers = (List<Map<String, Object>>) data.get("containers");
        assertNotNull(containers);
    }

    @Test @DisplayName("supports filter parameters")
    void supportsFilterParameters() {
        // Exclude libraries
        ObjectNode noLibs = objectMapper.createObjectNode();
        noLibs.put("includeLibraries", false);
        ToolResponse r1 = tool.execute(noLibs);
        assertTrue(r1.isSuccess());
        assertNull(getData(r1).get("libraries"));

        // Exclude source
        ObjectNode noSrc = objectMapper.createObjectNode();
        noSrc.put("includeSource", false);
        ToolResponse r2 = tool.execute(noSrc);
        assertTrue(r2.isSuccess());
        assertNull(getData(r2).get("sourceFolders"));

        // Exclude containers
        ObjectNode noContainers = objectMapper.createObjectNode();
        noContainers.put("includeContainers", false);
        ToolResponse r3 = tool.execute(noContainers);
        assertTrue(r3.isSuccess());
        assertNull(getData(r3).get("containers"));
    }

    @Test @DisplayName("works with no parameters")
    void worksWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }
}
