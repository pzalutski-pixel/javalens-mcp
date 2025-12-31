package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractVariableTool.
 * Tests expression extraction to local variable.
 */
class ExtractVariableToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractVariableTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractVariableTool(() -> service);
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
    @DisplayName("extracts expression to variable with complete response including edits")
    void extractsExpressionWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);  // input.length() * 2 + 10
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);
        args.put("variableName", "calculated");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify variable info
        assertEquals("calculated", data.get("variableName"));
        assertNotNull(data.get("variableType"));

        // Verify edit structure
        assertNotNull(data.get("edits"));
        List<Map<String, Object>> edits = getEdits(data);
        assertFalse(edits.isEmpty());

        Map<String, Object> firstEdit = edits.get(0);
        assertNotNull(firstEdit.get("newText"));
    }

    // ========== Optional Parameter Tests ==========

    @Test
    @DisplayName("auto-suggests variable name when not provided")
    void autoSuggestsVariableName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);
        // No variableName provided

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("variableName"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires start and end position parameters")
    void requiresPositionParameters() {
        // Missing start position
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("endLine", 31);
        args1.put("endColumn", 44);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing end position
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("startLine", 31);
        args2.put("startColumn", 21);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid variable names and reserved words")
    void rejectsInvalidVariableNames() {
        // Test invalid identifier (starts with number)
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("startLine", 31);
        args1.put("startColumn", 21);
        args1.put("endLine", 31);
        args1.put("endColumn", 44);
        args1.put("variableName", "123invalid");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Test reserved word
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("startLine", 31);
        args2.put("startColumn", 21);
        args2.put("endLine", 31);
        args2.put("endColumn", 44);
        args2.put("variableName", "for");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }
}
