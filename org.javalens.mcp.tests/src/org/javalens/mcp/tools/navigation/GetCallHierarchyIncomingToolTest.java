package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetCallHierarchyIncomingToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetCallHierarchyIncomingTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyIncomingTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds callers with complete response")
    void findCallersComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("method"));
        assertNotNull(data.get("declaringClass"));
        assertNotNull(data.get("signature"));
        assertNotNull(data.get("totalCallers"));
        assertNotNull(data.get("callers"));
    }

    @Test @DisplayName("supports maxResults parameter")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxResults", 5);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> callers = (List<?>) getData(r).get("callers");
        assertTrue(callers.size() <= 5);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);  // Class declaration, not method
        args.put("column", 13);

        assertFalse(tool.execute(args).isSuccess());
    }
}
