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
        String methodClass = (String) data.get("methodClass");
        assertNotNull(methodClass, "methodClass missing");
        assertFalse(methodClass.isBlank(), "methodClass non-blank; got: " + data);

        // Verify edit structure
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) data.get("edits");
        assertNotNull(edits, "edits list missing");
        assertFalse(edits.isEmpty());
        Map<String, Object> edit = edits.get(0);
        String newText = (String) edit.get("newText");
        assertNotNull(newText, "newText missing on first edit");
        assertFalse(newText.isBlank(), "newText non-blank; got: " + edit);

        // The inlined code should contain the multiplication
        assertTrue(newText.contains("x") || newText.contains("*"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("inlining doubleValue at processValue: edit text contains body `x * 2`")
    void inline_doubleValue_emitsExpectedExpression() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);   // `int doubled = doubleValue(x);`
        args.put("column", 22);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) getData(r).get("edits");
        assertFalse(edits.isEmpty());

        String newText = (String) edits.get(0).get("newText");
        // doubleValue body is `return value * 2;` — with arg `x` substituted: `x * 2`.
        assertTrue(newText.contains("x"), "Inlined text must reference the call argument `x`; got: " + newText);
        assertTrue(newText.contains("*"), "Inlined text must contain the `*` operator from body; got: " + newText);
        assertTrue(newText.contains("2"), "Inlined text must contain literal `2` from body; got: " + newText);
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

    // ========== Behavior-matrix coverage ==========

    private ObjectNode doubleValueArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 64);
        args.put("column", 22);
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> editsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("edits");
    }

    @Test
    @DisplayName("methodClass is RefactoringTarget (the declaring type of doubleValue)")
    void methodClass_reported() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals("RefactoringTarget", getData(r).get("methodClass"));
    }

    @Test
    @DisplayName("parameterCount=1 for doubleValue(int value)")
    void parameterCount_reported() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals(1, ((Number) getData(r).get("parameterCount")).intValue());
    }

    @Test
    @DisplayName("isExpressionContext=true when call is part of a larger expression (assignment RHS)")
    void isExpressionContext_trueForAssignmentRHS() {
        // `int doubled = doubleValue(x);` — the call is the initializer of the variable
        // declaration, an expression context.
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        assertEquals(Boolean.TRUE, getData(r).get("isExpressionContext"));
    }

    @Test
    @DisplayName("inlinedCode field equals the edit's newText")
    void inlinedCode_matchesEditNewText() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        String inlined = (String) getData(r).get("inlinedCode");
        String editText = (String) editsOf(r).get(0).get("newText");
        assertEquals(inlined, editText,
            "inlinedCode top-level field must equal the produced edit's newText");
    }

    @Test
    @DisplayName("Single replace edit carries start/end line/column, startOffset/endOffset, oldText, newText")
    void replaceEdit_shape() {
        ToolResponse r = tool.execute(doubleValueArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = editsOf(r);
        assertEquals(1, edits.size(), "inline_method emits exactly one edit");
        Map<String, Object> e = edits.get(0);
        for (String key : List.of("type", "startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(e.get(key), key + " missing on inline edit: " + e);
        }
        assertEquals("replace", e.get("type"));
    }

    @Test
    @DisplayName("Position on a non-existent file is rejected (file not found)")
    void nonExistentFile_isRejected() {
        ObjectNode args = doubleValueArgs();
        args.put("filePath", "/nonexistent/Path.java");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("Position on whitespace (no method invocation) is rejected")
    void positionOnWhitespace_isRejected() {
        // Line 0 col 0 — package keyword, definitely not a method call.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 0);
        args.put("column", 0);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
