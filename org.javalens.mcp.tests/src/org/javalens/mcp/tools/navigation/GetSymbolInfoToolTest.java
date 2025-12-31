package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetSymbolInfoTool.
 * Tests symbol metadata extraction.
 */
class GetSymbolInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetSymbolInfoTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSymbolInfoTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type symbol returns name, kind, qualifiedName, typeKind, filePath, line, and column")
    void typeSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("Type", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
        assertEquals("class", data.get("typeKind"));
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("line"));
        assertNotNull(data.get("column"));
    }

    @Test
    @DisplayName("Method symbol returns name, kind, returnType, signature, parameters, modifiers, and declaringType")
    @SuppressWarnings("unchecked")
    void methodSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("name"));
        assertEquals("Method", data.get("kind"));
        assertEquals("int", data.get("returnType"));
        assertEquals("com.example.Calculator", data.get("declaringType"));

        assertNotNull(data.get("signature"));
        assertTrue(data.get("signature").toString().contains("add"));

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("parameters");
        assertNotNull(params);
        assertEquals(2, params.size());
        assertEquals("int", params.get(0).get("type"));
        assertEquals("a", params.get(0).get("name"));

        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));
    }

    @Test
    @DisplayName("Field symbol returns name, kind, type, and isEnumConstant")
    void fieldSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
        assertEquals("Field", data.get("kind"));
        assertEquals("int", data.get("type"));
        assertEquals(false, data.get("isEnumConstant"));
    }

    @Test
    @DisplayName("Constructor is marked with isConstructor flag")
    void constructor_markedCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 11);
        args.put("column", 11);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(true, data.get("isConstructor"));
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
        assertNotNull(tool.execute(args1).getError());

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
