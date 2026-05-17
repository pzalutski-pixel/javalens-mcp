package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that tools tolerate Java 21 modern syntax: pattern-matching
 * {@code instanceof}, switch expressions with type and record-deconstruction
 * patterns, guarded patterns. The {@link com.example.Java21Modern} fixture
 * exercises all four forms.
 */
class Java21SyntaxToleranceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectPath;
    private ObjectMapper objectMapper;
    private String fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        projectPath = helper.getFixturePath("simple-maven");
        objectMapper = new ObjectMapper();
        fixturePath = projectPath.resolve("src/main/java/com/example/Java21Modern.java").toString();
    }

    // ========== validate_syntax: fixture parses cleanly ==========

    @Test
    @DisplayName("validate_syntax reports zero errors on Java 21 modern syntax fixture")
    void validateSyntax_modernSyntax_noErrors() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);
        ValidateSyntaxTool tool = new ValidateSyntaxTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "validate_syntax must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) data.get("errors");
        assertNotNull(errors, "validate_syntax must include errors list");
        assertTrue(errors.isEmpty(),
            "Java 21 modern syntax must parse without errors; got: " + errors);
    }

    // ========== get_diagnostics: zero errors on the fixture file ==========

    @Test
    @DisplayName("get_diagnostics finds zero errors on the Java 21 fixture")
    void getDiagnostics_modernSyntax_noErrors() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);
        GetDiagnosticsTool tool = new GetDiagnosticsTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "get_diagnostics must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        assertNotNull(diagnostics);
        long errorCount = diagnostics.stream()
            .filter(d -> "ERROR".equals(d.get("severity")))
            .count();
        assertEquals(0, errorCount,
            "Modern Java 21 syntax must not produce compiler errors; got: " + diagnostics);
    }

    // ========== analyze_method: pattern-matching instanceof method analyzes ==========

    @Test
    @DisplayName("analyze_method on a pattern-instanceof method succeeds")
    void analyzeMethod_patternInstanceof_succeeds() {
        // `legacyVsPatternInstanceof` declaration is on line 23 (0-based 22),
        // method-name column 15 ("    public int " = 15 chars).
        ObjectNode args = argsAt(fixturePath, 22, 15);
        AnalyzeMethodTool tool = new AnalyzeMethodTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_method must succeed for method with pattern-matching instanceof; got: "
                + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> method = (Map<String, Object>) data.get("method");
        assertNotNull(method);
        assertEquals("legacyVsPatternInstanceof", method.get("name"),
            "Method name must resolve correctly despite pattern syntax; got: " + method);
    }

    // ========== analyze_method on a record-deconstruction switch method ==========

    @Test
    @DisplayName("analyze_method on a record-deconstruction switch succeeds")
    void analyzeMethod_recordDeconstructionSwitch_succeeds() {
        // `area(Shape shape)` declaration is on line 51 (0-based 50),
        // method-name column 18 ("    public double " = 18 chars).
        ObjectNode args = argsAt(fixturePath, 50, 18);
        AnalyzeMethodTool tool = new AnalyzeMethodTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_method must succeed for a switch with record-deconstruction patterns; got: "
                + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> method = (Map<String, Object>) data.get("method");
        assertEquals("area", method.get("name"));
    }

    // ========== find_instanceof_checks finds the pattern-instanceof check ==========

    @Test
    @DisplayName("find_instanceof_checks finds the Java21Modern.String pattern-instanceof site")
    void findInstanceofChecks_patternInstanceof_found() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.String");
        args.put("maxResults", 100);
        FindInstanceofChecksTool tool = new FindInstanceofChecksTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "find_instanceof_checks must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = (List<Map<String, Object>>) data.get("instanceofChecks");
        assertNotNull(locations);
        // Java21Modern.legacyVsPatternInstanceof has TWO String instanceof sites:
        // one legacy ("instanceof String") and one pattern ("instanceof String s").
        long java21Hits = locations.stream()
            .filter(loc -> {
                Object p = loc.get("filePath");
                return p != null && p.toString().endsWith("Java21Modern.java");
            })
            .count();
        assertTrue(java21Hits >= 2,
            "find_instanceof_checks must find BOTH the legacy and the pattern instanceof. " +
            "If pattern form is missed, JDT's INSTANCEOF_TYPE_REFERENCE doesn't index pattern-instanceof. " +
            "Got hits in Java21Modern.java: " + java21Hits + ", full results: " + locations);
    }

    // ========== get_complexity_metrics on the switch-with-patterns method ==========

    @Test
    @DisplayName("get_complexity_metrics computes non-zero CC on switch-with-patterns")
    @SuppressWarnings("unchecked")
    void getComplexityMetrics_switchPatterns_nonZeroCC() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);
        args.put("granularity", "method");
        args.put("includeDetails", true);
        GetComplexityMetricsTool tool = new GetComplexityMetricsTool(() -> service);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "get_complexity_metrics must succeed on Java 21 syntax; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        assertNotNull(methods, "get_complexity_metrics must surface a methods list");
        Map<String, Object> describe = methods.stream()
            .filter(m -> "describe".equals(m.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("`describe` method must appear in metrics; got: " + methods));
        Number cc = (Number) describe.get("cyclomaticComplexity");
        assertNotNull(cc, "cyclomaticComplexity must be reported for describe; got: " + describe);
        // describe has 5 switch arms — each is a decision point. CC should be >= 2.
        assertTrue(cc.intValue() >= 2,
            "describe has 5 switch-with-patterns arms; cyclomaticComplexity must reflect that. " +
            "Got cc=" + cc + ", method=" + describe);
    }

    // ========== Helpers ==========

    private ObjectNode argsAt(String filePath, int line, int column) {
        return objectMapper.createObjectNode()
            .put("filePath", filePath)
            .put("line", line)
            .put("column", column);
    }
}
