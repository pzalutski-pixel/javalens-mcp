package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeFileToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeFileTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeFileTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("analyzes file comprehensively")
    void analyzesFileComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File info
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        assertNotNull(file.get("path"));
        assertEquals("com.example", file.get("package"));
        assertTrue((Integer) file.get("lineCount") > 0);

        // Types
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        assertFalse(types.isEmpty());
        assertEquals("Calculator", types.get(0).get("name"));
        assertEquals("Class", types.get(0).get("kind"));

        // Diagnostics included by default
        assertNotNull(data.get("diagnostics"));
    }

    @Test @DisplayName("controls optional output")
    void controlsOptionalOutput() {
        // Include members
        ObjectNode withMembers = objectMapper.createObjectNode();
        withMembers.put("filePath", calculatorPath);
        withMembers.put("includeMembers", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) getData(tool.execute(withMembers)).get("types");
        assertNotNull(types.get(0).get("methods"));

        // Exclude diagnostics
        ObjectNode noDiag = objectMapper.createObjectNode();
        noDiag.put("filePath", calculatorPath);
        noDiag.put("includeDiagnostics", false);
        assertNull(getData(tool.execute(noDiag)).get("diagnostics"));
    }

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(badPath).isSuccess());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertFalse(tool.execute(emptyPath).isSuccess());
    }
}
