package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetFieldAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetFieldAtPositionTool.
 * Tests field info extraction.
 */
class GetFieldAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetFieldAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetFieldAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Field declaration returns name, type, modifiers, declaringType, filePath, line, and flags")
    @SuppressWarnings("unchecked")
    void fieldDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("lastResult", data.get("name"));
        assertEquals("int", data.get("type"));
        assertEquals("com.example.Calculator", data.get("declaringType"));

        // Location
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("line"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("private"));

        // Flags
        assertEquals(false, data.get("isConstant"));
        assertEquals(false, data.get("isEnumConstant"));
    }

    @Test
    @DisplayName("Object type field returns correct type")
    void objectTypeField_returnsCorrectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 6);
        args.put("column", 19);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("greeting", data.get("name"));
        assertEquals("String", data.get("type"));
    }

    @Test
    @DisplayName("Field reference in method body returns field info")
    void fieldReferenceInMethod_returnsFieldInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 15);
        args.put("column", 8);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 6);
        args1.put("column", 16);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 16);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 6);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position on method returns error (not a field)")
    void positionOnMethod_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
