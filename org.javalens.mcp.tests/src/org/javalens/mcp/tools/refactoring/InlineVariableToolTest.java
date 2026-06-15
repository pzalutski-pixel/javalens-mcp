package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InlineVariableTool(() -> service);
        envelope = new EnvelopeHarness(service);
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

        assertEquals("trimmed", data.get("variableName"));
        assertEquals("input.trim()", data.get("initializerText"));
        assertEquals(2, ((Number) data.get("usageCount")).intValue());
        assertFalse(getEdits(data).isEmpty());
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
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'variable': Variable is modified after initialization, "
            + "cannot safely inline", response.getError().getMessage());
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
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'variable': Variable has no initializer, cannot inline",
            response.getError().getMessage());
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
        assertEquals("input.trim()", data.get("initializerText"));

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
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("column", 15);
        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response1.getError().getCode());
        assertEquals("Invalid parameter 'line/column': Must be >= 0", response1.getError().getMessage());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 26);
        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response2.getError().getCode());
        assertEquals("Invalid parameter 'line/column': Must be >= 0", response2.getError().getMessage());
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
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND, response.getError().getCode());
        assertEquals("Symbol not found: No variable at position", response.getError().getMessage());
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
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, r.getError().getCode());
        assertEquals("File not found: /nonexistent/Path.java", r.getError().getMessage());
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
        assertEquals("Invalid parameter 'variable': Can only inline local variables, not fields",
            r.getError().getMessage());
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
        assertEquals("Invalid parameter 'variable': Cannot inline method parameters",
            r.getError().getMessage());
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

    @Test
    @DisplayName("Method-reference initializer inlines into the usage site")
    @SuppressWarnings("unchecked")
    void methodReferenceInitializer_inlined() {
        // MethodRefUser.use declares `IntFunction<String> formatter = MethodRefTarget::formatId;`
        // and calls `formatter.apply(id)`. Inlining `formatter` must produce a replacement
        // that includes the method-reference expression. The tool's needsParentheses logic
        // is responsible for safe substitution.
        String path = projectPath.resolve("src/main/java/com/example/MethodRefUser.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", path);
        args.put("line", 15);
        args.put("column", 28);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Inlining a method-reference-initialized variable must succeed; got: "
                + (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("formatter", data.get("variableName"));
        assertEquals("MethodRefTarget::formatId", data.get("initializerText"));

        // At least one replace edit must substitute the method-reference text.
        boolean substituted = getEdits(data).stream()
            .filter(e -> "replace".equals(e.get("type")))
            .map(e -> (String) e.get("newText"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(t -> t.contains("MethodRefTarget::formatId"));
        assertTrue(substituted,
            "At least one replace edit must substitute the method-reference text; got: "
                + getEdits(data));
    }

    @Test
    @DisplayName("Refuses inlining a variable declared inside a for-loop init clause")
    void forInitDeclaration_isRefused() {
        // `for (int i = 0; i < n; i++)` — the variable i is declared inside
        // the for-init clause, not as a standalone VariableDeclarationStatement.
        // Inlining replaces every usage of i with 0; but the declaration
        // delete-edit is silently skipped (the parent is a
        // VariableDeclarationExpression, not a Statement). Resulting code
        // `for (int 0 = 0; 0 < n; 0++)` is uncompilable. Tool must refuse.
        String cfp = projectPath.resolve("src/main/java/com/example/ControlFlowPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", cfp);
        args.put("line", 49);
        args.put("column", 17);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Inlining a for-init-declared variable must be refused; got success: " + r.getData());
        // `i` is modified by `i++`, so it's refused as modified-after-init.
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'variable': Variable is modified after initialization, "
            + "cannot safely inline", r.getError().getMessage());
    }

    // ========== Exact edit lines ==========

    @Test
    @DisplayName("Inline edits sit at exact 0-based lines: delete at 26, replaces at usages 27 and 28")
    void inline_trimmed_exactEditLines() {
        ToolResponse r = tool.execute(trimmedArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));

        Map<String, Object> del = edits.stream()
            .filter(e -> "delete".equals(e.get("type"))).findFirst().orElseThrow();
        // The delete removes the declaration together with its leading
        // `// Variable to inline` comment, so the range begins on 0-based line 25.
        assertEquals(25, ((Number) del.get("line")).intValue(),
            "delete edit starts at the declaration's leading comment (0-based line 25); got: " + del);

        java.util.Set<Integer> replaceLines = new java.util.TreeSet<>();
        for (Map<String, Object> e : edits) {
            if ("replace".equals(e.get("type"))) {
                replaceLines.add(((Number) e.get("line")).intValue());
            }
        }
        assertEquals(java.util.Set.of(27, 28), replaceLines,
            "the two usages of `trimmed` are on 0-based lines 27 and 28; got: " + edits);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: inlining trimmed deletes line 26 and replaces lines 27, 28")
    void envelope_inline_exactEditLines() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 26);
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("inline_variable", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "inline_variable failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(2, data.get("usageCount").asInt());
        java.util.Set<Integer> replaceLines = new java.util.TreeSet<>();
        int deleteLine = -1;
        for (JsonNode e : data.get("edits")) {
            String type = e.get("type").asText();
            if ("delete".equals(type)) deleteLine = e.get("line").asInt();
            else if ("replace".equals(type)) replaceLines.add(e.get("line").asInt());
        }
        assertEquals(25, deleteLine, "delete-edit line must survive the envelope");
        assertEquals(java.util.Set.of(27, 28), replaceLines,
            "replace-edit lines must survive the envelope");
    }
}
