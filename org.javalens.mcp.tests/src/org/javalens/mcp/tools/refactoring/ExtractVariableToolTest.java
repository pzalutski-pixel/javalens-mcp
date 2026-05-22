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

        // Verify variable info — exact name and Java-valid type
        assertEquals("calculated", data.get("variableName"));
        String variableType = (String) data.get("variableType");
        assertNotNull(variableType, "variableType missing");
        assertFalse(variableType.isBlank(), "variableType non-blank; got: " + data);

        // Verify edit structure — non-empty
        List<Map<String, Object>> edits = getEdits(data);
        assertFalse(edits.isEmpty(), "edits must be non-empty");

        Map<String, Object> firstEdit = edits.get(0);
        String newText = (String) firstEdit.get("newText");
        assertNotNull(newText, "newText missing on first edit");
        assertFalse(newText.isBlank(), "newText non-blank on first edit; got: " + firstEdit);
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
        String autoName = (String) data.get("variableName");
        assertNotNull(autoName, "variableName missing on auto-suggest");
        assertFalse(autoName.isBlank(),
            "auto-suggested variable name must be non-blank; got: " + data);
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

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("extract emits one edit that declares the variable with chosen name")
    void extract_declarationEditContainsVariableName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);
        args.put("variableName", "calculated");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));

        // At least one edit must introduce a declaration mentioning the variable name.
        boolean anyDeclEdit = edits.stream()
            .map(e -> (String) e.get("newText"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(text -> text.contains("calculated"));
        assertTrue(anyDeclEdit,
            "At least one edit's newText must reference the new variable name; got: " + edits);
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode resultExpressionArgs(String name) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);
        if (name != null) args.put("variableName", name);
        return args;
    }

    @Test
    @DisplayName("variableType is `int` for `input.length() * 2 + 10`")
    void variableType_isInt() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        assertEquals("int", getData(r).get("variableType"));
    }

    @Test
    @DisplayName("expressionText returns the selected expression text")
    void expressionText_matchesSelection() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        String expr = (String) getData(r).get("expressionText");
        assertNotNull(expr);
        // Order of normalisation may vary; assert key tokens are present.
        assertTrue(expr.contains("input.length()"),
            "expressionText must contain input.length(); got: " + expr);
        assertTrue(expr.contains("10"),
            "expressionText must contain the literal 10; got: " + expr);
    }

    @Test
    @DisplayName("declaration string is `int <name> = <expr>;`")
    void declaration_shape() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        String decl = (String) getData(r).get("declaration");
        assertNotNull(decl);
        assertTrue(decl.startsWith("int calculated = "),
            "declaration must start with `int calculated = `; got: " + decl);
        assertTrue(decl.endsWith(";"), "declaration must end with `;`; got: " + decl);
    }

    @Test
    @DisplayName("Exactly two edits emitted: one insert (declaration) + one replace (expression)")
    void edits_haveInsertAndReplace() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));
        assertEquals(2, edits.size(),
            "extract_variable must emit exactly 2 edits: insert + replace; got: " + edits);
        java.util.Set<String> types = new java.util.HashSet<>();
        for (Map<String, Object> e : edits) types.add((String) e.get("type"));
        assertEquals(java.util.Set.of("insert", "replace"), types);
    }

    @Test
    @DisplayName("Insert edit carries type, line, column, offset, newText")
    void insertEdit_shape() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        Map<String, Object> insert = getEdits(getData(r)).stream()
            .filter(e -> "insert".equals(e.get("type"))).findFirst().orElseThrow();
        for (String key : List.of("line", "column", "offset", "newText")) {
            assertNotNull(insert.get(key), key + " missing on insert edit: " + insert);
        }
        assertTrue(((String) insert.get("newText")).contains("calculated"),
            "Insert edit newText must include the variable name");
    }

    @Test
    @DisplayName("Replace edit carries start/end line/column, start/end offset, oldText, newText")
    void replaceEdit_shape() {
        ToolResponse r = tool.execute(resultExpressionArgs("calculated"));
        assertTrue(r.isSuccess());
        Map<String, Object> replace = getEdits(getData(r)).stream()
            .filter(e -> "replace".equals(e.get("type"))).findFirst().orElseThrow();
        for (String key : List.of("startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(replace.get(key), key + " missing on replace edit: " + replace);
        }
        assertEquals("calculated", replace.get("newText"),
            "Replace edit must substitute the variable name");
    }

    // ========== Evaluation-context safety ==========

    private String semanticsFixturePath() {
        return projectPath.resolve("src/main/java/com/example/ExtractVariableSemantics.java").toString();
    }

    @Test
    @DisplayName("Refuses extraction of a for-loop condition (would change re-evaluation semantics)")
    void refusesExtractionFromForLoopCondition() {
        // `for (int i = 0; i < 10; i++)` re-evaluates `i < 10` each iteration.
        // A declaration placed before the for runs once and captures only
        // the initial value, turning the loop into an infinite or no-op
        // loop. Also `i` is not declared before the for, so the lifted
        // declaration wouldn't even compile.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", semanticsFixturePath());
        args.put("startLine", 19);
        args.put("startColumn", 24);
        args.put("endLine", 19);
        args.put("endColumn", 30);
        args.put("variableName", "cond");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Extraction from a for-loop condition must be refused; got: " + r.getData());
    }

    @Test
    @DisplayName("Refuses extraction of a short-circuit `&&` right operand (would lose the guard)")
    void refusesExtractionFromShortCircuitRight() {
        // `s != null && s.length() > 0` runs `s.length() > 0` only when
        // `s != null`. Hoisting `s.length() > 0` to a declaration before
        // the if removes the guard and NPEs on null inputs.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", semanticsFixturePath());
        args.put("startLine", 31);
        args.put("startColumn", 25);
        args.put("endLine", 31);
        args.put("endColumn", 39);
        args.put("variableName", "hasContent");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Extraction from the right operand of `&&` must be refused; got: " + r.getData());
    }

    @Test
    @DisplayName("Refuses extraction from a while-loop condition")
    void refusesExtractionFromWhileCondition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", semanticsFixturePath());
        args.put("startLine", 52);
        args.put("startColumn", 15);
        args.put("endLine", 52);
        args.put("endColumn", 31);
        args.put("variableName", "shouldContinue");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Extraction from a while-loop condition must be refused; got: " + r.getData());
    }

    @Test
    @DisplayName("Inverted range (start >= end) is rejected")
    void rejectsInvertedRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);
        args.put("startColumn", 44);
        args.put("endLine", 31);
        args.put("endColumn", 21);
        args.put("variableName", "calculated");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "Inverted range must be rejected");
    }
}
