package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetMethodAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetMethodAtPositionTool.
 * Tests method signature extraction.
 */
class GetMethodAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetMethodAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetMethodAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Method declaration returns name, returnType, parameters, parameterCount, modifiers, signature, declaringType, filePath, and line")
    @SuppressWarnings("unchecked")
    void methodDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("add", data.get("name"));
        assertEquals(false, data.get("isConstructor"));
        assertEquals("int", data.get("returnType"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        assertEquals(2, data.get("parameterCount"));

        // Parameters
        List<Map<String, String>> params = (List<Map<String, String>>) data.get("parameters");
        assertNotNull(params);
        assertEquals(2, params.size());
        assertEquals("int", params.get(0).get("type"));
        assertEquals("a", params.get(0).get("name"));
        assertEquals("int", params.get(1).get("type"));
        assertEquals("b", params.get(1).get("name"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        // Signature
        assertNotNull(data.get("signature"));
        String sig = data.get("signature").toString();
        assertTrue(sig.contains("add"));
        assertTrue(sig.contains("int"));

        // Location
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("line"));
    }

    @Test
    @DisplayName("Constructor returns isConstructor=true, name, and no returnType")
    void constructor_returnsCorrectFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 11);
        args.put("column", 11);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(true, data.get("isConstructor"));
        assertEquals("HelloWorld", data.get("name"));
        assertNull(data.get("returnType"));
    }

    @Test
    @DisplayName("Main method is identified with isMainMethod flag")
    void mainMethod_identifiedCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 50);
        args.put("column", 23);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("main", data.get("name"));
        assertEquals(true, data.get("isMainMethod"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 14);
        args1.put("column", 15);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 15);
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position on field returns error (not a method)")
    void positionOnField_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
