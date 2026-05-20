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
        String initializerText = (String) data.get("initializerText");
        assertNotNull(initializerText, "initializerText missing");
        assertFalse(initializerText.isBlank(), "initializerText non-blank; got: " + data);
        int usageCount = ((Number) data.get("usageCount")).intValue();
        assertTrue(usageCount > 0, "trimmed variable is used; usageCount > 0; got: " + data);

        // Verify edit structure
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

    @Test
    @DisplayName("Field position is rejected as INVALID_PARAMETER (`Can only inline local variables, not fields`)")
    void fieldPosition_rejectedAsField() {
        // RefactoringTarget.userName is a field at 1-based line 16 -> 0-based 15.
        // "userName" identifier starts at column 19 (4 indent + "private String " = 19).
        // Source path: variableBinding.isField()==true → invalidParameter("variable", ...).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 15);
        args.put("column", 19);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Field position must be rejected; got success: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("field"),
            "Error message must mention the field-vs-local distinction; got: "
                + r.getError().getMessage());
    }

    @Test
    @DisplayName("Method parameter position is rejected (`Cannot inline method parameters`)")
    void parameterPosition_rejectedAsParameter() throws Exception {
        // RefactoringTarget.processData(String input) — `input` parameter declaration.
        // Locate exactly via source-text scan so column drift in the fixture doesn't break
        // the test. Source: variableBinding.isParameter()==true → invalidParameter.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(refactoringTargetPath));
        int idx = source.indexOf("processData(String input)");
        idx = source.indexOf("input", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int col = idx - (source.lastIndexOf('\n', idx) + 1);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", line);
        args.put("column", col);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Parameter position must be rejected; got success: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("parameter"),
            "Error message must mention `parameter`; got: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("INFIX initializer (`input.length() * 2 + 10`): replace edits paren-wrap when used inside another expression")
    @SuppressWarnings("unchecked")
    void infixInitializer_parenWrapsInComplexContext() throws Exception {
        // RefactoringTarget.processData declares `int result = input.length() * 2 + 10;`.
        // The initializer is an INFIX_EXPRESSION → needsParentheses returns true for
        // ANY non-literal, non-name, non-method-invocation initializer. The replace
        // edit must wrap the inlined text in `(...)` so operator precedence is preserved.
        // Locate via source-text scan so column drift in the fixture doesn't break this.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(refactoringTargetPath));
        int idx = source.indexOf("int result = input.length()");
        idx = source.indexOf("result", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int col = idx - (source.lastIndexOf('\n', idx) + 1);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", line);
        args.put("column", col);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on `result` must inline successfully; got: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        Map<String, Object> data = getData(r);
        assertEquals("result", data.get("variableName"));

        List<Map<String, Object>> edits = getEdits(data);
        Map<String, Object> replace = edits.stream()
            .filter(e -> "replace".equals(e.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected at least one replace edit; got: " + edits));
        String newText = (String) replace.get("newText");
        assertTrue(newText.startsWith("("),
            "Complex (INFIX) initializer must be paren-wrapped in replace.newText; got: " + newText);
        assertTrue(newText.endsWith(")"),
            "Paren-wrap must close; got: " + newText);
        assertTrue(newText.contains("input.length()"),
            "Wrapped text must include the initializer body; got: " + newText);
        assertTrue(newText.contains("* 2"),
            "Wrapped text must include the multiplication; got: " + newText);
        assertTrue(newText.contains("+ 10"),
            "Wrapped text must include the addition; got: " + newText);
    }
}
