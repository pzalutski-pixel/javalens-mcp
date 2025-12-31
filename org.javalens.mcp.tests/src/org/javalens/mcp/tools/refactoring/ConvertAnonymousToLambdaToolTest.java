package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConvertAnonymousToLambdaTool.
 * Tests anonymous class to lambda expression conversion.
 */
class ConvertAnonymousToLambdaToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ConvertAnonymousToLambdaTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String anonymousExamplesPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ConvertAnonymousToLambdaTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        anonymousExamplesPath = projectPath.resolve("src/main/java/com/example/AnonymousClassExamples.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("converts simple Runnable with complete response including edit details")
    void convertsSimpleRunnableWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 19);  // new Runnable() in simpleRunnable()
        args.put("column", 28);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify interface info
        assertNotNull(data.get("interfaceType"));
        assertNotNull(data.get("methodName"));

        // Verify edit structure
        assertNotNull(data.get("edits"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        Map<String, Object> edit = edits.get(0);
        assertNotNull(edit.get("startLine"));

        // Verify lambda syntax
        String newText = (String) edit.get("newText");
        assertTrue(newText.contains("->"));
    }

    @Test
    @DisplayName("converts Comparator with two parameters")
    void convertsComparator() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 33);  // new Comparator<String>() in comparatorExample()
        args.put("column", 31);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("edits"));
    }

    @Test
    @DisplayName("handles single parameter Consumer")
    void handlesSingleParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 45);  // Consumer<String> in singleParam()
        args.put("column", 55);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        String newText = (String) edits.get(0).get("newText");
        assertTrue(newText.contains("->"));
    }

    @Test
    @DisplayName("generates block body for multiple statements")
    void generatesBlockBodyForMultipleStatements() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 98);  // blockBodyExample()
        args.put("column", 28);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        String newText = (String) edits.get(0).get("newText");
        // Block body should have braces
        assertTrue(newText.contains("{") && newText.contains("}"));
    }

    @Test
    @DisplayName("handles Supplier with return expression")
    void handlesSupplierWithReturn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 113);  // Supplier in withReturn()
        args.put("column", 55);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    // ========== Rejection Tests ==========

    @Test
    @DisplayName("refuses anonymous class with this reference")
    void refusesAnonymousClassWithThisReference() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 58);  // withThisReference() - uses this.toString()
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        // Should refuse because lambda 'this' has different semantics
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("refuses anonymous class with multiple methods")
    void refusesAnonymousClassWithMultipleMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 84);  // multipleMethodsExample()
        args.put("column", 21);

        ToolResponse response = tool.execute(args);

        // Should refuse because it has multiple methods
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("refuses non-functional interface")
    void refusesNonFunctionalInterface() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 126);  // nonFunctionalInterface() - extends ArrayList
        args.put("column", 31);

        ToolResponse response = tool.execute(args);

        // Should refuse because ArrayList is not a functional interface
        assertFalse(response.isSuccess());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 18);
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        // Missing line
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", anonymousExamplesPath);
        args1.put("column", 27);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing column
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", anonymousExamplesPath);
        args2.put("line", 18);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-anonymous class position gracefully")
    void handlesNotAnAnonymousClass() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", anonymousExamplesPath);
        args.put("line", 10);  // Class declaration
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
