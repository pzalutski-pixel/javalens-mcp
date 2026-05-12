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
    @DisplayName("add_import: Calculator (no existing imports) emits one insert edit with `import java.util.Date;`")
    void addImport_calculatorNoImports_emitsImportInsert() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_import:java.util.Date");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add_import:java.util.Date", data.get("fixId"));
        assertEquals("add_import", data.get("fixType"));

        // Calculator declares a package but no imports; the tool must produce exactly one
        // insert edit after the package declaration that introduces the import.
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "add_import on a file with no existing imports must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"),
            "Adding an import is an insertion; got: " + edit);
        assertNotNull(edit.get("offset"));
        assertNotNull(edit.get("line"));

        String newText = (String) edit.get("newText");
        assertNotNull(newText, "Insert edit must carry the text to insert");
        assertTrue(newText.contains("import java.util.Date;"),
            "Insert text must contain the full import statement; got: " + newText);
    }

    @Test
    @DisplayName("remove_import: SearchPatterns index 0 (java.io.IOException at 0-based line 2) emits one delete edit")
    void removeImport_searchPatternsIndexZero_emitsDeleteAtImportLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("fixId", "remove_import:0");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "remove_import:0 must succeed for a file with at least one import");
        Map<String, Object> data = getData(response);
        assertEquals("remove_import", data.get("fixType"));

        // SearchPatterns.java imports java.io.IOException at index 0 (0-based line 2).
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "remove_import must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("delete", edit.get("type"),
            "Removing an import is a deletion; got: " + edit);
        assertEquals(2, ((Number) edit.get("startLine")).intValue(),
            "java.io.IOException is at 0-based line 2 in SearchPatterns.java; got: " + edit);
        assertNotNull(edit.get("startOffset"));
        assertNotNull(edit.get("endOffset"));
        assertTrue(
            ((Number) edit.get("endOffset")).intValue() > ((Number) edit.get("startOffset")).intValue(),
            "delete edit must have endOffset > startOffset; got: " + edit);
    }

    @Test
    @DisplayName("add_throws: Calculator.add (no existing throws) inserts ` throws java.io.IOException`")
    void addThrows_calculatorAdd_insertsThrowsClause() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        // Calculator.add() declaration is at 0-based line 14 (1-based line 15).
        args.put("line", 14);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "add_throws at a real method position must succeed");
        Map<String, Object> data = getData(response);
        assertEquals("add_throws", data.get("fixType"));

        // Calculator.add has no existing throws clause. The tool must insert ` throws X`
        // right after the closing paren of the parameter list.
        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "add_throws must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("insert", edit.get("type"),
            "Adding a throws clause is an insertion; got: " + edit);
        assertEquals(" throws java.io.IOException", edit.get("newText"),
            "First-throws insertion must produce ` throws X` (with leading space); got: " + edit);
    }

    @Test
    @DisplayName("add_throws: line not on a method returns invalid_parameter error")
    void addThrows_noMethodAtLine_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "add_throws:java.io.IOException");
        // 0-based line 0 is the `package com.example;` line — not a method.
        args.put("line", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "add_throws on a non-method position must fail with an error response");
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("surround_try_catch: wraps `lastResult = a + b;` in Calculator.add body with try-catch")
    void surroundTryCatch_wrapsStatementInTryCatch() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "surround_try_catch:java.io.IOException");
        // `lastResult = a + b;` is at 0-based line 15 (1-based line 16) inside Calculator.add.
        args.put("line", 15);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "surround_try_catch on a real statement line must succeed");
        Map<String, Object> data = getData(response);
        assertEquals("surround_try_catch", data.get("fixType"));

        List<Map<String, Object>> edits = getEdits(data);
        assertEquals(1, edits.size(),
            "surround_try_catch must produce exactly 1 edit; got: " + edits);

        Map<String, Object> edit = edits.get(0);
        assertEquals("replace", edit.get("type"),
            "Wrapping is a replacement of the original statement; got: " + edit);

        String newText = (String) edit.get("newText");
        assertNotNull(newText);
        assertTrue(newText.startsWith("try {"),
            "Wrapped block must start with `try {`; got: " + newText);
        assertTrue(newText.contains("lastResult = a + b;"),
            "Wrapped block must preserve the original statement; got: " + newText);
        assertTrue(newText.contains("catch (java.io.IOException e)"),
            "Wrapped block must catch the requested exception type; got: " + newText);
    }

    @Test
    @DisplayName("surround_try_catch: line with no statement returns invalid_parameter error")
    void surroundTryCatch_noStatementAtLine_rejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("fixId", "surround_try_catch:java.io.IOException");
        // 0-based line 0 is the package declaration — not a Statement.
        args.put("line", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "surround_try_catch on a non-statement line must fail with an error response");
        assertNotNull(response.getError());
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
