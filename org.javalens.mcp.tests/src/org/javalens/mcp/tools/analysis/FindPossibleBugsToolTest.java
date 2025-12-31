package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindPossibleBugsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindPossibleBugsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindPossibleBugsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds bugs comprehensively")
    void findsBugsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("issues"));
        assertNotNull(data.get("totalIssues"));
        assertNotNull(data.get("highCount"));
        assertNotNull(data.get("mediumCount"));
        assertNotNull(data.get("lowCount"));
    }

    @Test @DisplayName("supports severity filter")
    void supportsSeverityFilter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "high");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) getData(r).get("issues");
        for (Map<String, Object> issue : issues) {
            assertEquals("high", issue.get("severity"));
        }
    }

    @Test @DisplayName("returns no issues for clean file")
    void returnsNoIssuesForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals(0, getData(r).get("totalIssues"));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalIssues"));
    }
}
