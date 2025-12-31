package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeMethodTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMethod(Map<String, Object> d) { return (Map<String, Object>) d.get("method"); }

    @Test @DisplayName("analyzes method comprehensively")
    void analyzesMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, Object> method = getMethod(data);

        // Method info
        assertEquals("add", method.get("name"));
        assertNotNull(method.get("signature"));
        assertEquals("com.example.Calculator", method.get("declaringType"));
        assertEquals("int", method.get("returnType"));

        // Parameters
        assertNotNull(data.get("parameters"));

        // Call hierarchy
        assertNotNull(data.get("callers"));
        assertNotNull(data.get("callees"));
        assertNotNull(data.get("overrides"));
    }

    @Test @DisplayName("respects max limits")
    void respectsMaxLimits() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 1);
        args.put("maxCallees", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<?> callerList = (List<?>) callers.get("list");
        assertTrue(callerList.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column")
    void requiresParameters() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());

        ObjectNode noColumn = objectMapper.createObjectNode();
        noColumn.put("filePath", calculatorPath);
        noColumn.put("line", 14);
        assertFalse(tool.execute(noColumn).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        // Non-existent file
        ObjectNode badFile = objectMapper.createObjectNode();
        badFile.put("filePath", "/nonexistent/File.java");
        badFile.put("line", 14);
        badFile.put("column", 15);
        assertFalse(tool.execute(badFile).isSuccess());

        // Position not on method
        ObjectNode notMethod = objectMapper.createObjectNode();
        notMethod.put("filePath", calculatorPath);
        notMethod.put("line", 0);
        notMethod.put("column", 0);
        assertFalse(tool.execute(notMethod).isSuccess());
    }
}
