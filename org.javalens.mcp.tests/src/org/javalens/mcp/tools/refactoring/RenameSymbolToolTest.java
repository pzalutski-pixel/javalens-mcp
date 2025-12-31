package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RenameSymbolTool.
 * Tests cross-file rename and identifier validation.
 */
class RenameSymbolToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RenameSymbolTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new RenameSymbolTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> getEditsByFile(Map<String, Object> data) {
        return (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("rename local variable returns complete response with all edit details")
    void renameLocalVariable_returnsCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 88);  // int oldName = 42;
        args.put("column", 12);
        args.put("newName", "newName");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify symbol info
        assertEquals("oldName", data.get("oldName"));
        assertEquals("newName", data.get("newName"));
        assertEquals("LocalVariable", data.get("symbolKind"));

        // Verify edit counts
        assertTrue((int) data.get("totalEdits") > 0);
        assertNotNull(data.get("filesAffected"));
        assertTrue((int) data.get("filesAffected") >= 1);

        // Verify edit structure
        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(data);
        assertFalse(editsByFile.isEmpty());
        List<Map<String, Object>> edits = editsByFile.values().iterator().next();
        assertFalse(edits.isEmpty());

        Map<String, Object> edit = edits.get(0);
        assertNotNull(edit.get("line"));
        assertNotNull(edit.get("column"));
        assertNotNull(edit.get("endColumn"));
        assertEquals("oldName", edit.get("oldText"));
        assertEquals("newName", edit.get("newText"));
    }

    @Test
    @DisplayName("rename field finds all usages and returns field kind")
    void renameField_findsAllUsagesAndReturnsFieldKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 15);  // private String userName;
        args.put("column", 19);
        args.put("newName", "userFullName");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("userName", data.get("oldName"));
        assertEquals("userFullName", data.get("newName"));
        assertEquals("Field", data.get("symbolKind"));
        // Field is used in multiple places
        assertTrue((int) data.get("totalEdits") >= 3);
    }

    @Test
    @DisplayName("rename method returns method kind")
    void renameMethod_returnsMethodKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // public int add(int a, int b)
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("oldName"));
        assertEquals("sum", data.get("newName"));
        assertEquals("Method", data.get("symbolKind"));
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("rejects invalid Java identifiers, reserved words, and same name")
    void rejectsInvalidNames() {
        // Test invalid identifier (starts with number)
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("line", 88);
        args1.put("column", 12);
        args1.put("newName", "123invalid");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertTrue(response1.getError().getMessage().contains("identifier"));

        // Test reserved word
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 88);
        args2.put("column", 12);
        args2.put("newName", "class");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());

        // Test same name
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", refactoringTargetPath);
        args3.put("line", 88);
        args3.put("column", 12);
        args3.put("newName", "oldName");

        ToolResponse response3 = tool.execute(args3);
        assertFalse(response3.isSuccess());
        assertTrue(response3.getError().getMessage().contains("Same as current"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    @Test
    @DisplayName("requires newName parameter")
    void requiresNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 10);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid line/column gracefully")
    void handlesInvalidLineColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", -1);
        args.put("column", -1);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles no symbol at position")
    void handlesNoSymbolAtPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 1);  // Empty line after package
        args.put("column", 0);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
