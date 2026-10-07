package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_complexity_metrics on simple-maven's Calculator, ComplexityBoundaries and Java21Modern.
 * Every expectation is the whole payload, derived from the fixture source and asserted through
 * execute() and through the JSON-RPC envelope.
 *
 * <p>A line is blank when it holds only whitespace and a comment line when it starts a // or
 * /* comment or lies inside a block comment. A method's line is where its declaration starts,
 * its Javadoc included, zero-based; its physicalLOC runs from there to its closing brace.
 * Cyclomatic complexity is 1 plus each if, loop, non-default case, catch, ternary, && and ||;
 * cognitive complexity charges each if, loop, try, catch and ternary 1 plus its nesting.
 */
class GetComplexityMetricsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetComplexityMetricsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @Test @DisplayName("Calculator: exact LOC, summary, and risk profile (all methods trivial)")
    void calculatesMetricsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        assertPayload(args, calculator(true));
    }

    @Test @DisplayName("includeDetails=false leaves out the per-method list and keeps every other field")
    void respectsIncludeDetailsOption() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeDetails", false);
        assertPayload(args, calculator(false));
    }

    @Test @DisplayName("missing filePath -> exact INVALID_PARAMETER")
    void requiresFilePath() {
        assertPayload(objectMapper.createObjectNode(), refusal("Invalid parameter 'filePath': Required"));
    }

    @Test @DisplayName("non-existent file -> FILE_NOT_FOUND; empty filePath -> INVALID_PARAMETER")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertAll(
            () -> assertPayload(badPath, Map.of("success", false, "error", Map.of(
                "code", "FILE_NOT_FOUND",
                "message", "File not found: /nonexistent/File.java",
                "hint", "Verify the file path is correct and the file exists"))),
            () -> assertPayload(emptyPath, refusal("Invalid parameter 'filePath': Required")));
    }

    // ========== Semantic-grade tests (CC boundaries from ComplexityBoundaries) ==========

    @Test
    @DisplayName("ComplexityBoundaries: cc01=1, cc05=5, cc06=6, cc10=10, cc11=11 cyclomatic complexity")
    void complexityBoundaries_cyclomaticAtKnownValues() {
        assertPayload(boundariesArgs(), complexityBoundaries());
    }

    @Test
    @DisplayName("ComplexityBoundaries: cognitive complexity is 0/4/5/9/10 for cc01/cc05/cc06/cc10/cc11")
    void complexityBoundaries_cognitiveAtKnownValues() {
        // Every if is at nesting level 0, so each adds exactly 1 to cognitive complexity.
        assertPayload(boundariesArgs(), complexityBoundaries());
    }

    @Test
    @DisplayName("Java21Modern: switch-expression cases each contribute +1 to CC (describe has 4 non-default cases)")
    void java21Modern_switchExpressionCases_contributeToCC() {
        // describe: case null, case String s, case Integer i, case int[] arr and default; the
        // four non-default cases give CC 5. area's two record patterns give CC 3. classify's
        // `case null, default` lists its labels, so it is not the bare default: four cases,
        // CC 5. legacyVsPatternInstanceof has three ifs: CC 4, cognitive 3.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven").resolve(JAVA21_MODERN).toString());
        args.put("includeDetails", true);
        String type = "com.example.Java21Modern";
        assertPayload(args, metrics(JAVA21_MODERN, 67, 6, 20, 17, 3, 4.25, 5, List.of(
            method("legacyVsPatternInstanceof", type, 21, 4, 3, 16, 1, "low"),
            method("describe", type, 38, 5, 0, 10, 1, "low"),
            method("area", type, 49, 3, 0, 7, 1, "low"),
            method("classify", type, 57, 5, 0, 9, 1, "low")), true));
    }

    @Test
    @DisplayName("ComplexityBoundaries: risk classification (low <=5, medium 6-10, high >10)")
    void complexityBoundaries_riskClassification() {
        assertPayload(boundariesArgs(), complexityBoundaries());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("file block: Calculator's 49 lines are 5 blank, 25 comment and 19 code")
    void fileBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeDetails", false);
        assertPayload(args, calculator(false));
    }

    @Test
    @DisplayName("summary: Calculator's four methods total CC 4 and cognitive 0, average and maximum CC 1")
    void summary_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        assertPayload(args, calculator(true));
    }

    @Test
    @DisplayName("Per-method entry has name, type, line, cyclomaticComplexity, cognitiveComplexity, physicalLOC, parameterCount, risk")
    void methodEntry_shape() {
        assertPayload(boundariesArgs(), complexityBoundaries());
    }

    @Test
    @DisplayName("methodCount in summary equals methods.size() when includeDetails=true")
    void methodCount_matchesListSize() {
        assertPayload(boundariesArgs(), complexityBoundaries());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: ComplexityBoundaries cyclomatic and risk counts are exact")
    void envelope_complexityBoundaries_exact() {
        ObjectNode args = envelope.args();
        args.put("filePath", helper.getFixturePath("simple-maven").resolve(BOUNDARIES).toString());
        args.put("includeDetails", true);
        assertPayload(args, complexityBoundaries());
    }

    // ========== Input schema ==========

    @Test
    @DisplayName("The input schema offers exactly filePath (required) and includeDetails: every payload holds the file, summary and per-method levels")
    void inputSchema_exactParameters() {
        Map<String, Object> filePath = new java.util.LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Path to source file");
        Map<String, Object> includeDetails = new java.util.LinkedHashMap<>();
        includeDetails.put("type", "boolean");
        includeDetails.put("description", "Include per-method breakdown (default: true)");
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("filePath", filePath);
        properties.put("includeDetails", includeDetails);
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("filePath"));
        assertEquals(objectMapper.valueToTree(schema), objectMapper.valueToTree(tool.getInputSchema()));
    }

    // ========== exact payload helpers ==========

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String BOUNDARIES = "src/main/java/com/example/ComplexityBoundaries.java";
    private static final String JAVA21_MODERN = "src/main/java/com/example/Java21Modern.java";

    private ObjectNode boundariesArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven").resolve(BOUNDARIES).toString());
        args.put("includeDetails", true);
        return args;
    }

    /**
     * Calculator: 49 lines, 5 blank and 25 in Javadoc. add, subtract and multiply each span
     * their Javadoc and body, 10 lines; getLastResult 7.
     */
    private static Map<String, Object> calculator(boolean includeDetails) {
        String type = "com.example.Calculator";
        return metrics(CALCULATOR, 49, 5, 25, 4, 0, 1.0, 1, List.of(
            method("add", type, 8, 1, 0, 10, 2, "low"),
            method("subtract", type, 19, 1, 0, 10, 2, "low"),
            method("multiply", type, 30, 1, 0, 10, 2, "low"),
            method("getLastResult", type, 41, 1, 0, 7, 0, "low")), includeDetails);
    }

    /**
     * ComplexityBoundaries: 56 lines, 6 blank, no comment. cc05, cc06, cc10 and cc11 hold 4, 5,
     * 9 and 10 top-level ifs.
     */
    private static Map<String, Object> complexityBoundaries() {
        String type = "com.example.ComplexityBoundaries";
        return metrics(BOUNDARIES, 56, 6, 0, 33, 28, 6.6, 11, List.of(
            method("cc01", type, 4, 1, 0, 3, 1, "low"),
            method("cc05", type, 8, 5, 4, 8, 1, "low"),
            method("cc06", type, 17, 6, 5, 9, 1, "medium"),
            method("cc10", type, 27, 10, 9, 13, 1, "medium"),
            method("cc11", type, 41, 11, 10, 14, 1, "high")), true);
    }

    private static Map<String, Object> method(String name, String type, int line, int cyclomatic, int cognitive,
                                              int physicalLOC, int parameterCount, String risk) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("name", name);
        method.put("type", type);
        method.put("line", line);
        method.put("cyclomaticComplexity", cyclomatic);
        method.put("cognitiveComplexity", cognitive);
        method.put("physicalLOC", physicalLOC);
        method.put("parameterCount", parameterCount);
        method.put("risk", risk);
        return method;
    }

    /** The success payload of a file; the risk counts are read from {@code methods}' risks. */
    private static Map<String, Object> metrics(String file, int physicalLOC, int blankLines, int commentLines,
                                               int totalCC, int totalCognitive, double averageCC, int maxCC,
                                               List<Map<String, Object>> methods, boolean includeDetails) {
        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("path", file);
        fileInfo.put("physicalLOC", physicalLOC);
        fileInfo.put("blankLines", blankLines);
        fileInfo.put("commentLines", commentLines);
        fileInfo.put("codeLOC", physicalLOC - blankLines - commentLines);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCyclomaticComplexity", totalCC);
        summary.put("totalCognitiveComplexity", totalCognitive);
        summary.put("averageMethodCC", averageCC);
        summary.put("maxMethodCC", maxCC);
        summary.put("methodCount", methods.size());
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("highRiskMethods", (int) methods.stream().filter(m -> "high".equals(m.get("risk"))).count());
        risk.put("mediumRiskMethods", (int) methods.stream().filter(m -> "medium".equals(m.get("risk"))).count());
        risk.put("lowRiskMethods", (int) methods.stream().filter(m -> "low".equals(m.get("risk"))).count());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", fileInfo);
        data.put("summary", summary);
        data.put("riskAssessment", risk);
        if (includeDetails) {
            data.put("methods", methods);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", methods.size());
        meta.put("returnedCount", methods.size());
        meta.put("suggestedNextTools", (int) risk.get("highRiskMethods") > 0
            ? List.of("Consider refactoring high-complexity methods")
            : List.of("get_dependency_graph to analyze dependencies"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to as a client
     * reads it, and through the JSON-RPC envelope both equal {@code expected}.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = envelope.assertEnvelopeFidelity("get_complexity_metrics", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
