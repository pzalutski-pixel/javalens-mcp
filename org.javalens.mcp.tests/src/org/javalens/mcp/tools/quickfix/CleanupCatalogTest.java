package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins each apply_cleanup catalog id against its trigger in CleanupCatalogDemo:
 * the rewritten source carries the transformed construct and no longer carries
 * the original. FlexibleCtorDemo (no triggers for any id) pins the no-op side:
 * changed=false and the source returned unchanged.
 */
class CleanupCatalogTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private String catalogPath;
    private String noopPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new ApplyCleanupTool(() -> service);
        catalogPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/CleanupCatalogDemo.java").toString();
        noopPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/FlexibleCtorDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private String applyChanged(String cleanupId) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", catalogPath);
        args.put("cleanupId", cleanupId);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), cleanupId + ": expected success; got: " + r.getData());
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("changed"),
            cleanupId + " must transform its trigger in CleanupCatalogDemo");
        return (String) data.get("source");
    }

    private void assertNoop(String cleanupId) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", noopPath);
        args.put("cleanupId", cleanupId);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), cleanupId + ": expected success on no-op file; got: " + r.getData());
        assertEquals(Boolean.FALSE, getData(r).get("changed"),
            cleanupId + " has no trigger in FlexibleCtorDemo; must report changed=false");
    }

    @Test
    @DisplayName("convert_to_lambda: anonymous Runnable becomes a lambda")
    void convertToLambda() {
        String source = applyChanged("convert_to_lambda");
        assertTrue(source.contains("->"), "lambda arrow expected; got:\n" + source);
        assertFalse(source.contains("new Runnable()"),
            "anonymous Runnable must be gone; got:\n" + source);
        assertNoop("convert_to_lambda");
    }

    @Test
    @DisplayName("pattern_matching_instanceof: instanceof+cast becomes a type pattern")
    void patternMatchingInstanceof() {
        String source = applyChanged("pattern_matching_instanceof");
        assertTrue(source.contains("instanceof String s"),
            "type pattern expected; got:\n" + source);
        assertFalse(source.contains("(String) o"),
            "explicit cast must be gone; got:\n" + source);
        assertNoop("pattern_matching_instanceof");
    }

    @Test
    @DisplayName("convert_to_switch_expression: assignment switch becomes a switch expression")
    void convertToSwitchExpression() {
        String source = applyChanged("convert_to_switch_expression");
        assertTrue(source.contains("switch (k)") && source.contains("->"),
            "switch expression with arrow cases expected; got:\n" + source);
        assertFalse(source.contains("v = 10;"),
            "per-case assignment must be gone; got:\n" + source);
        assertNoop("convert_to_switch_expression");
    }

    @Test
    @DisplayName("string_concat_to_text_block: multi-line concatenation becomes a text block")
    void stringConcatToTextBlock() {
        String source = applyChanged("string_concat_to_text_block");
        assertTrue(source.contains("\"\"\""), "text block expected; got:\n" + source);
        assertFalse(source.contains("\"<html>\\n\" +"),
            "concatenation must be gone; got:\n" + source);
        assertNoop("string_concat_to_text_block");
    }

    @Test
    @DisplayName("do_while_rather_than_while: while(true) becomes do-while")
    void doWhileRatherThanWhile() {
        String source = applyChanged("do_while_rather_than_while");
        assertTrue(source.contains("do {"), "do-while expected; got:\n" + source);
        assertNoop("do_while_rather_than_while");
    }

    @Test
    @DisplayName("invert_equals: literal becomes the equals() receiver")
    void invertEquals() {
        String source = applyChanged("invert_equals");
        assertTrue(source.contains("\"expected\".equals(input)"),
            "inverted equals expected; got:\n" + source);
        assertFalse(source.contains("input.equals(\"expected\")"),
            "original receiver order must be gone; got:\n" + source);
        assertNoop("invert_equals");
    }

    @Test
    @DisplayName("boolean_value_rather_than_comparison: flag == true becomes flag")
    void booleanValueRatherThanComparison() {
        String source = applyChanged("boolean_value_rather_than_comparison");
        assertTrue(source.contains("if (flag)"),
            "bare boolean condition expected; got:\n" + source);
        assertFalse(source.contains("flag == true"),
            "literal comparison must be gone; got:\n" + source);
        assertNoop("boolean_value_rather_than_comparison");
    }

    @Test
    @DisplayName("else_if: else { if } collapses to else if")
    void elseIf() {
        String source = applyChanged("else_if");
        assertTrue(source.contains("else if (b)"),
            "else-if chain expected; got:\n" + source);
        assertNoop("else_if");
    }

    @Test
    @DisplayName("overridden_assignment: dead initializer is folded into the assignment")
    void overriddenAssignment() {
        String source = applyChanged("overridden_assignment");
        assertFalse(source.contains("int x = 0;"),
            "dead initializer must be gone; got:\n" + source);
        assertTrue(source.contains("int x = compute();") || source.contains("x = compute();"),
            "the surviving assignment must remain; got:\n" + source);
        assertNoop("overridden_assignment");
    }

    @Test
    @DisplayName("catalog ids and the tool description agree")
    void catalogMatchesDescription() {
        String description = tool.getDescription();
        for (String id : org.javalens.mcp.cleanup.CleanUpInvoker.supportedCleanUps()) {
            assertTrue(description.contains(id),
                "getDescription must document cleanupId '" + id + "'");
        }
    }
}
