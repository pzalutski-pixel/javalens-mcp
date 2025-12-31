package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetHoverInfoTool.
 * Tests hover documentation extraction.
 */
class GetHoverInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetHoverInfoTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetHoverInfoTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class hover returns name, kind, and signature with class keyword")
    void classHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("Type", data.get("kind"));

        assertNotNull(data.get("signature"));
        String sig = data.get("signature").toString();
        assertTrue(sig.contains("class"));
        assertTrue(sig.contains("Calculator"));
    }

    @Test
    @DisplayName("Method hover returns name, kind, signature, modifiers, declaringType, filePath, and docComment")
    @SuppressWarnings("unchecked")
    void methodHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("name"));
        assertEquals("Method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        assertNotNull(data.get("filePath"));

        assertNotNull(data.get("signature"));
        String sig = data.get("signature").toString();
        assertTrue(sig.contains("add"));
        assertTrue(sig.contains("int"));

        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        String docComment = (String) data.get("docComment");
        if (docComment != null) {
            assertTrue(docComment.contains("Add") || docComment.contains("sum"));
        }
    }

    @Test
    @DisplayName("Field hover returns name, kind, and signature with type")
    void fieldHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
        assertEquals("Field", data.get("kind"));

        assertNotNull(data.get("signature"));
        String sig = data.get("signature").toString();
        assertTrue(sig.contains("int"));
        assertTrue(sig.contains("lastResult"));
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

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 14);
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
