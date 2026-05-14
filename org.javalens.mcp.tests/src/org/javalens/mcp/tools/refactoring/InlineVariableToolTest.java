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

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("inline trimmed: usageCount=2, initializer is input.trim(), edits include the call")
    void inline_trimmed_exactUsageAndInitializer() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        // `String trimmed = input.trim();` is 1-based line 27 -> 0-based 26; "trimmed" name at column 15.
        args.put("line", 26);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("trimmed", data.get("variableName"));

        // Initializer text must literally be the trim() call.
        String init = (String) data.get("initializerText");
        assertNotNull(init);
        assertTrue(init.contains("input.trim()"),
            "initializerText must be `input.trim()`; got: " + init);

        // `trimmed` is read twice: println(trimmed), println(trimmed.length()).
        assertEquals(2, ((Number) data.get("usageCount")).intValue(),
            "trimmed has exactly 2 usages; got: " + data.get("usageCount"));

        // At least one replacement edit must contain `input.trim()` text (inlined value).
        List<Map<String, Object>> edits = getEdits(data);
        boolean anyHasInline = edits.stream()
            .map(e -> (String) e.get("newText"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(t -> t.contains("input.trim()"));
        assertTrue(anyHasInline,
            "At least one edit must replace a usage with `input.trim()`; got: " + edits);
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

    // ========== Behavior-matrix coverage ==========

    private ObjectNode trimmedArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 26);
        args.put("column", 15);
        return args;
    }

    @Test
    @DisplayName("Edits sorted offset-descending so they can be applied in order")
    void edits_sortedDescendingByOffset() {
        ToolResponse r = tool.execute(trimmedArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));
        for (int i = 1; i < edits.size(); i++) {
            int prev = ((Number) edits.get(i - 1).get("startOffset")).intValue();
            int curr = ((Number) edits.get(i).get("startOffset")).intValue();
            assertTrue(prev >= curr,
                "Edits must be sorted by startOffset descending; got prev=" + prev + " curr=" + curr);
        }
    }

    @Test
    @DisplayName("Exactly one delete edit (the declaration statement) + N replace edits (one per usage)")
    void edits_haveOneDeleteAndPerUsageReplaces() {
        ToolResponse r = tool.execute(trimmedArgs());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int usageCount = ((Number) data.get("usageCount")).intValue();
        List<Map<String, Object>> edits = getEdits(data);

        long deletes = edits.stream().filter(e -> "delete".equals(e.get("type"))).count();
        long replaces = edits.stream().filter(e -> "replace".equals(e.get("type"))).count();
        assertEquals(1L, deletes, "Exactly one delete edit (the declaration); got: " + edits);
        assertEquals(usageCount, replaces,
            "One replace edit per usage; got " + replaces + " for usageCount=" + usageCount);
    }

    @Test
    @DisplayName("Delete edit carries oldText, startOffset, endOffset, line")
    void deleteEdit_shape() {
        ToolResponse r = tool.execute(trimmedArgs());
        assertTrue(r.isSuccess());
        Map<String, Object> del = getEdits(getData(r)).stream()
            .filter(e -> "delete".equals(e.get("type"))).findFirst().orElseThrow();
        for (String key : List.of("oldText", "startOffset", "endOffset", "line")) {
            assertNotNull(del.get(key), key + " missing on delete edit: " + del);
        }
        String oldText = (String) del.get("oldText");
        assertTrue(oldText.contains("trimmed"),
            "Delete edit's oldText must include the variable name; got: " + oldText);
    }

    @Test
    @DisplayName("Each replace edit substitutes the variable name with the initializer")
    void replaceEdits_substituteInitializer() {
        ToolResponse r = tool.execute(trimmedArgs());
        assertTrue(r.isSuccess());
        for (Map<String, Object> e : getEdits(getData(r))) {
            if (!"replace".equals(e.get("type"))) continue;
            assertEquals("trimmed", e.get("oldText"),
                "Replace edit oldText must be the variable name; got: " + e);
            String newText = (String) e.get("newText");
            assertTrue(newText != null && newText.contains("input.trim()"),
                "Replace edit newText must contain the initializer expression; got: " + e);
        }
    }

    @Test
    @DisplayName("Position on a non-existent file is rejected")
    void nonExistentFile_isRejected() {
        ObjectNode args = trimmedArgs();
        args.put("filePath", "/nonexistent/Path.java");
        assertFalse(tool.execute(args).isSuccess());
    }
}
