package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.InlineVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InlineVariableTool.
 * Tests variable inlining by replacing usages with initializer.
 */
class InlineVariableToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineVariableTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InlineVariableTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edits");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("inlines variable with complete response including edits")
    void inlinesVariableWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 26);  // String trimmed = input.trim();
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify variable info
        assertEquals("trimmed", data.get("variableName"));
        assertNotNull(data.get("initializerText"));
        assertNotNull(data.get("usageCount"));

        // Verify edit structure
        assertNotNull(data.get("edits"));
        List<Map<String, Object>> edits = getEdits(data);
        assertFalse(edits.isEmpty());
    }

    // ========== Safety Check Tests ==========

    @Test
    @DisplayName("refuses to inline modified variable")
    void refusesToInlineModifiedVariable() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 121);  // modifiedVariable method - value = 10; then value = value + 5;
        args.put("column", 12);

        ToolResponse response = tool.execute(args);

        // Should refuse because variable is modified after initialization
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("refuses variable without initializer")
    void refusesVariableWithoutInitializer() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 130);  // noInitializer method - int value; (no initializer)
        args.put("column", 12);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 26);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        // Missing line
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("column", 15);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing column
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 26);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-variable position gracefully")
    void handlesNotAVariable() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 24);  // Method declaration
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
