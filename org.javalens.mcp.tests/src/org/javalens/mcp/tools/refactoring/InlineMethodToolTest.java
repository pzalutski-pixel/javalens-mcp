package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.InlineMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InlineMethodTool.
 * Tests method call inlining by replacing call with method body.
 */
class InlineMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineMethodTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InlineMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("inlines method call with complete response including edit details")
    void inlinesMethodCallWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);  // int doubled = doubleValue(x);
        args.put("column", 22);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify method info
        assertEquals("doubleValue", data.get("methodName"));
        assertNotNull(data.get("methodClass"));

        // Verify edit structure
        assertNotNull(data.get("edits"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertFalse(edits.isEmpty());
        Map<String, Object> edit = edits.get(0);
        assertNotNull(edit.get("newText"));

        // The inlined code should contain the multiplication
        String newText = (String) edit.get("newText");
        assertTrue(newText.contains("x") || newText.contains("*"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 64);
        args.put("column", 22);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        // Missing line
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("column", 22);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing column
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 64);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-method call position gracefully")
    void handlesNotAMethodCall() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 15);  // Field declaration
        args.put("column", 19);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
