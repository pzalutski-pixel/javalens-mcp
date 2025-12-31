package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GoToDefinitionTool.
 * Tests navigation to class, method, and field definitions.
 */
class GoToDefinitionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GoToDefinitionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GoToDefinitionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class definition returns symbol, kind, package, and location")
    @SuppressWarnings("unchecked")
    void classDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertEquals("Class", data.get("kind"));
        assertEquals("com.example", data.get("package"));

        Map<String, Object> location = (Map<String, Object>) data.get("location");
        assertNotNull(location);
        assertNotNull(location.get("filePath"));
        assertNotNull(location.get("line"));
        assertNotNull(location.get("column"));
    }

    @Test
    @DisplayName("Method definition returns symbol, kind, and containingType")
    void methodDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("Method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("containingType"));
    }

    @Test
    @DisplayName("Field definition returns symbol and kind")
    void fieldDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("symbol"));
        assertEquals("Field", data.get("kind"));
    }

    // ========== Cross-File Navigation Tests ==========

    @Test
    @DisplayName("Type reference navigates to definition in another file")
    void typeReference_navigatesToDefinition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 12);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertNotNull(data.get("location"));
    }

    @Test
    @DisplayName("Method call navigates to method definition")
    void methodCall_navigatesToDefinition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 58);
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("Method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("containingType"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 5);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 5);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position with no symbol handles gracefully")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }
}
