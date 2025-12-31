package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetDocumentSymbolsTool.
 * Tests file symbol extraction.
 */
class GetDocumentSymbolsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDocumentSymbolsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDocumentSymbolsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSymbols(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("symbols");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getChildren(Map<String, Object> symbol) {
        return (List<Map<String, Object>>) symbol.get("children");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Document symbols returns class with methods, fields, line numbers, modifiers, signatures, and types")
    @SuppressWarnings("unchecked")
    void documentSymbols_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("symbols"));
        assertNotNull(data.get("totalSymbols"));

        List<Map<String, Object>> symbols = getSymbols(data);
        assertFalse(symbols.isEmpty());

        // Find Calculator class and verify all fields
        Map<String, Object> calcSymbol = symbols.stream()
            .filter(s -> "Calculator".equals(s.get("name")))
            .findFirst()
            .orElse(null);

        assertNotNull(calcSymbol);
        assertEquals("Class", calcSymbol.get("kind"));
        assertNotNull(calcSymbol.get("line"));

        List<String> modifiers = (List<String>) calcSymbol.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        List<Map<String, Object>> children = getChildren(calcSymbol);
        assertNotNull(children);

        // Methods with signatures
        assertTrue(children.stream().anyMatch(c -> "add".equals(c.get("name"))));
        assertTrue(children.stream().anyMatch(c -> "subtract".equals(c.get("name"))));
        assertTrue(children.stream().anyMatch(c -> "multiply".equals(c.get("name"))));

        Map<String, Object> addMethod = children.stream()
            .filter(c -> "add".equals(c.get("name")))
            .findFirst()
            .orElse(null);
        assertNotNull(addMethod);
        assertNotNull(addMethod.get("signature"));

        // Fields with types
        Map<String, Object> lastResultField = children.stream()
            .filter(c -> "lastResult".equals(c.get("name")))
            .findFirst()
            .orElse(null);
        assertNotNull(lastResultField);
        assertEquals("int", lastResultField.get("type"));
    }

    @Test
    @DisplayName("Constructors are included in symbols")
    void constructors_includedInSymbols() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> symbols = getSymbols(data);
        Map<String, Object> helloSymbol = symbols.get(0);
        List<Map<String, Object>> children = getChildren(helloSymbol);

        assertTrue(children.stream().anyMatch(c ->
            "HelloWorld".equals(c.get("name")) && "Constructor".equals(c.get("kind"))));
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("includePrivate filter controls private member visibility")
    void includePrivate_controlsVisibility() {
        // Default includes private
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", calculatorPath);
        ToolResponse response1 = tool.execute(args1);
        Map<String, Object> data1 = getData(response1);
        List<Map<String, Object>> symbols1 = getSymbols(data1);
        List<Map<String, Object>> children1 = getChildren(symbols1.get(0));
        assertTrue(children1.stream().anyMatch(c -> "lastResult".equals(c.get("name"))));

        // includePrivate=false excludes private
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("includePrivate", false);
        ToolResponse response2 = tool.execute(args2);
        Map<String, Object> data2 = getData(response2);
        List<Map<String, Object>> symbols2 = getSymbols(data2);
        List<Map<String, Object>> children2 = getChildren(symbols2.get(0));
        assertFalse(children2.stream().anyMatch(c -> "lastResult".equals(c.get("name"))));
    }

    @Test
    @DisplayName("maxResults limits total symbols returned")
    void maxResults_limitsSymbols() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("maxResults", 3);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        Integer totalSymbols = (Integer) data.get("totalSymbols");
        assertTrue(totalSymbols <= 3);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid filePath returns error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Nonexistent file
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", "/nonexistent/path/File.java");
        assertFalse(tool.execute(args2).isSuccess());
    }
}
