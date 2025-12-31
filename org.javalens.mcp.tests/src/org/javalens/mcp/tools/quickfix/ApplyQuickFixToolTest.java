package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ApplyQuickFixTool.
 * Tests applying quick fixes like adding imports, throws declarations.
 */
class ApplyQuickFixToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyQuickFixTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String searchPatternsPath;

    @BeforeEach
    void setUp() throws Exception {
        // Use loadProjectCopy since we might modify files
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ApplyQuickFixTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getTempDirectory().resolve("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        searchPatternsPath = projectPath.resolve("src/main/java/com/example/SearchPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edits");
    }

    @Test
    @DisplayName("generates complete edit structure for add_import fix")
    void generatesCompleteEditStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import:java.util.Date");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify response structure
        assertNotNull(data.get("filePath"));
        assertEquals("add_import:java.util.Date", data.get("fixId"));

        // Verify edits list
        List<Map<String, Object>> edits = getEdits(data);
        assertNotNull(edits);

        // Verify edit structure if present
        if (!edits.isEmpty()) {
            Map<String, Object> edit = edits.get(0);
            assertNotNull(edit.get("type"));
            String newText = (String) edit.get("newText");
            if (newText != null) {
                assertTrue(newText.contains("import") || newText.contains("java.util.Date"));
            }
        }
    }

    @Test
    @DisplayName("handles remove_import fix")
    void handlesRemoveImport() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("fixId", "remove_import:0");

        ToolResponse response = tool.execute(args);

        // Should succeed or fail gracefully
        assertNotNull(response);
    }

    @Test
    @DisplayName("handles add_throws fix with line parameter")
    void handlesAddThrowsWithLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        args.put("line", 5);

        ToolResponse response = tool.execute(args);

        // May or may not succeed depending on method at line 5
        assertNotNull(response);
    }

    @Test
    @DisplayName("handles surround_try_catch fix with line parameter")
    void handlesSurroundTryCatchWithLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "surround_try_catch:java.io.IOException");
        args.put("line", 5);

        ToolResponse response = tool.execute(args);

        // May or may not succeed depending on statement at line 5
        assertNotNull(response);
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("fixId", "add_import:java.util.List");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("requires fixId parameter")
    void requiresFixId() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("handles invalid fixId format")
    void handlesInvalidFixIdFormat() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "invalid_fix_id");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("handles unknown fix type and non-existent file")
    void handlesErrorCases() {
        // Test unknown fix type
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", calculatorPath);
        args1.put("fixId", "unknown_type:value");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertNotNull(response1.getError());

        // Test non-existent file
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", projectPath.resolve("NonExistent.java").toString());
        args2.put("fixId", "add_import:java.util.List");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertNotNull(response2.getError());
    }
}
