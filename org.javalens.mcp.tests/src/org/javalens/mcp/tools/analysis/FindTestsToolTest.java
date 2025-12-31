package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTestsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds tests comprehensively")
    void findsTestsComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Counts
        assertNotNull(data.get("testClassCount"));
        assertNotNull(data.get("testMethodCount"));

        // Test classes structure
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> testClasses = (List<Map<String, Object>>) data.get("testClasses");
        assertNotNull(testClasses);

        if (!testClasses.isEmpty()) {
            Map<String, Object> tc = testClasses.get(0);
            assertNotNull(tc.get("className"));
            assertNotNull(tc.get("filePath"));
            assertNotNull(tc.get("testMethods"));
        }
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        // Pattern filter
        ObjectNode withPattern = objectMapper.createObjectNode();
        withPattern.put("pattern", "*Test");
        assertTrue(tool.execute(withPattern).isSuccess());

        // Include disabled
        ObjectNode withDisabled = objectMapper.createObjectNode();
        withDisabled.put("includeDisabled", true);
        assertTrue(tool.execute(withDisabled).isSuccess());
    }

    @Test @DisplayName("returns success with no parameters")
    void returnsSuccessWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }
}
