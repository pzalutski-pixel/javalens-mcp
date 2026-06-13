package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ValidateSyntaxTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidateSyntaxToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private ValidateSyntaxTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ValidateSyntaxTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("validates file successfully")
    void validatesFileSuccessfully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("valid"));
        @SuppressWarnings("unchecked")
        List<?> errors = (List<?>) data.get("errors");
        assertTrue(errors == null || errors.isEmpty());
    }

    @Test @DisplayName("validates inline content")
    void validatesInlineContent() {
        ObjectNode valid = objectMapper.createObjectNode();
        valid.put("content", "public class Test { public void test() {} }");
        assertEquals(true, getData(tool.execute(valid)).get("valid"));

        ObjectNode withFileName = objectMapper.createObjectNode();
        withFileName.put("content", "public class MyClass {}");
        withFileName.put("fileName", "MyClass.java");
        assertEquals(true, getData(tool.execute(withFileName)).get("valid"));
    }

    @Test @DisplayName("detects syntax errors")
    void detectsSyntaxErrors() {
        ObjectNode missingBrace = objectMapper.createObjectNode();
        missingBrace.put("content", "public class Test { public void test() {");
        assertEquals(false, getData(tool.execute(missingBrace)).get("valid"));

        ObjectNode missingValue = objectMapper.createObjectNode();
        missingValue.put("content", "public class Test { int x = ; }");
        Map<String, Object> data = getData(tool.execute(missingValue));
        assertEquals(false, data.get("valid"));
        @SuppressWarnings("unchecked")
        List<?> errors = (List<?>) data.get("errors");
        assertFalse(errors.isEmpty());
    }

    @Test @DisplayName("requires filePath or content")
    void requiresFilePathOrContent() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid file path")
    void handlesInvalidFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> errorsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("errors");
    }

    @Test
    @DisplayName("File-path validation: fileName reports the CU element name")
    void filePath_reportsFileNameFromCu() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("Calculator.java", getData(r).get("fileName"));
    }

    @Test
    @DisplayName("Inline validation: fileName defaults to Untitled.java when no fileName parameter is provided")
    void inline_defaultsFileName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "public class X {}");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("Untitled.java", getData(r).get("fileName"));
    }

    @Test
    @DisplayName("Inline validation: fileName parameter overrides default")
    void inline_explicitFileName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "public class MyClass {}");
        args.put("fileName", "MyClass.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("MyClass.java", getData(r).get("fileName"));
    }

    @Test
    @DisplayName("Each syntax-error entry has line, column, startOffset, endOffset, message, problemId")
    void errorEntry_includesFullShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "public class T { int x = ; }");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> errors = errorsOf(r);
        assertFalse(errors.isEmpty(), "Syntax error must be reported for `int x = ;`");
        for (Map<String, Object> e : errors) {
            for (String key : List.of("line", "column", "startOffset", "endOffset", "message", "problemId")) {
                assertNotNull(e.get(key), key + " missing on error: " + e);
            }
        }
    }

    @Test
    @DisplayName("errorCount equals errors.size()")
    void errorCount_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "public class T { int x = ;");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int count = ((Number) data.get("errorCount")).intValue();
        assertEquals(count, errorsOf(r).size());
    }

    @Test
    @DisplayName("Valid file returns valid=true, errorCount=0, errors=[]")
    void validFile_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("valid"));
        assertEquals(0, ((Number) data.get("errorCount")).intValue());
        assertTrue(errorsOf(r).isEmpty());
    }

    @Test
    @DisplayName("filePath takes precedence when BOTH filePath and content are supplied")
    void filePath_winsOverContent_whenBothProvided() {
        // Source: `if (filePath != null && !filePath.isBlank())` runs FIRST. content
        // is only consulted in the else branch. Pin that precedence by providing a
        // valid filePath plus deliberately broken inline content — the result must
        // reflect the FILE (valid=true), not the content.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("content", "this is not java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("valid"),
            "filePath takes precedence — Calculator is valid; got: " + data);
        assertEquals("Calculator.java", data.get("fileName"),
            "fileName must come from the CU (filePath path), not the content path; got: " + data);
    }

    @Test
    @DisplayName("Blank filePath falls back to content")
    void blankFilePath_fallsBackToContent() {
        // The dual-source check is `(filePath null OR blank) AND (content null OR blank)`.
        // Blank filePath ("") with valid content must NOT error — must use the content.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "");
        args.put("content", "public class X {}");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Blank filePath must fall through to content path; got: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("valid"));
        // Without a fileName parameter the default is Untitled.java.
        assertEquals("Untitled.java", data.get("fileName"));
    }

    @Test
    @DisplayName("Warnings (e.g., unused-import) are NOT counted as syntax errors")
    void warnings_notReportedBySyntaxValidator() {
        // RefactoringTarget.java has 4 unused-import WARNINGS but no syntax errors.
        String refactoringPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("valid"),
            "validate_syntax must report valid=true when there are only warnings; got: " + data);
        assertEquals(0, ((Number) data.get("errorCount")).intValue());
    }

    // ========== Exact error line (0-based) ==========

    @Test
    @DisplayName("Inline syntax error reports its exact 0-based line")
    void inlineError_exactZeroBasedLine() {
        ObjectNode args = objectMapper.createObjectNode();
        // The malformed initializer `int x = ;` sits on 0-based line 1.
        args.put("content", "class T {\n    int x = ;\n}");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.FALSE, data.get("valid"));

        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (Map<String, Object> e : errorsOf(r)) {
            lines.add(((Number) e.get("line")).intValue());
        }
        // The only broken line is 0-based line 1; a dropped zero-based conversion
        // would shift the reported error to line 2.
        assertEquals(java.util.Set.of(1), lines,
            "the syntax error is on exactly 0-based line 1; got: " + errorsOf(r));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: inline syntax error reports 0-based line 1")
    void envelope_inlineError_exactLine() {
        ObjectNode args = envelope.args();
        args.put("content", "class T {\n    int x = ;\n}");
        JsonNode payload = envelope.payload("validate_syntax", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "validate_syntax failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertFalse(data.get("valid").asBoolean());
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (JsonNode e : data.get("errors")) {
            lines.add(e.get("line").asInt());
        }
        assertEquals(java.util.Set.of(1), lines,
            "the 0-based error line must survive the JSON-RPC envelope; got: " + data.get("errors"));
    }
}
