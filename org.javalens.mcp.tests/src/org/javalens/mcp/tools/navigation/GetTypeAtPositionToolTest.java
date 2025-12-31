package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetTypeAtPositionTool.
 * Tests type resolution at positions.
 */
class GetTypeAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetTypeAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type declaration returns name, qualifiedName, kind, modifiers, counts, location, and flags")
    @SuppressWarnings("unchecked")
    void typeDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("Calculator", data.get("name"));
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
        assertEquals("Class", data.get("kind"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        // Member counts
        assertNotNull(data.get("methodCount"));
        assertTrue((Integer) data.get("methodCount") >= 4);
        assertEquals(1, data.get("fieldCount"));
        assertEquals(0, data.get("nestedTypeCount"));

        // Location
        assertNotNull(data.get("filePath"));
        assertTrue(data.get("filePath").toString().contains("Calculator.java"));
        assertNotNull(data.get("line"));

        // Flags
        assertEquals(false, data.get("isAnonymous"));
        assertEquals(false, data.get("isLocal"));
    }

    @Test
    @DisplayName("Type reference resolves to qualified type")
    void typeReference_resolvesToQualifiedType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 12);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
    }

    @Test
    @DisplayName("Method position finds enclosing type")
    void methodPosition_findsEnclosingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
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
    @DisplayName("Position with no type handles gracefully")
    void positionWithNoType_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }
}
