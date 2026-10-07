package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("Calculator: exact LOC, summary, and risk profile (all methods trivial)")
    void calculatesMetricsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File metrics — exact (49 lines: 5 blank, 25 Javadoc comment, 19 code).
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        assertTrue(((String) file.get("path")).endsWith("Calculator.java"));
        assertEquals(49, ((Number) file.get("physicalLOC")).intValue());
        assertEquals(5, ((Number) file.get("blankLines")).intValue());
        assertEquals(25, ((Number) file.get("commentLines")).intValue());
        assertEquals(19, ((Number) file.get("codeLOC")).intValue());

        // Summary — the 4 methods (add/subtract/multiply/getLastResult) have no
        // decision points: CC 1 each, cognitive 0 each.
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(4, ((Number) summary.get("methodCount")).intValue());
        assertEquals(4, ((Number) summary.get("totalCyclomaticComplexity")).intValue());
        assertEquals(0, ((Number) summary.get("totalCognitiveComplexity")).intValue());
        assertEquals(1, ((Number) summary.get("maxMethodCC")).intValue());
        assertEquals(1.0, ((Number) summary.get("averageMethodCC")).doubleValue());

        // Risk — all 4 methods are low risk (CC <= 5).
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("riskAssessment");
        assertEquals(4, ((Number) risk.get("lowRiskMethods")).intValue());
        assertEquals(0, ((Number) risk.get("mediumRiskMethods")).intValue());
        assertEquals(0, ((Number) risk.get("highRiskMethods")).intValue());

        @SuppressWarnings("unchecked")
        List<?> methods = (List<?>) data.get("methods");
        assertEquals(4, methods.size(), "method details present with exactly the 4 methods");
    }

    @Test @DisplayName("respects includeDetails option")
    void respectsIncludeDetailsOption() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeDetails", false);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertNull(getData(r).get("methods"));
    }

    @Test @DisplayName("missing filePath -> exact INVALID_PARAMETER")
    void requiresFilePath() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", r.getError().getMessage());
    }

    @Test @DisplayName("non-existent file -> FILE_NOT_FOUND; empty filePath -> INVALID_PARAMETER")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        ToolResponse badResp = tool.execute(badPath);
        assertFalse(badResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, badResp.getError().getCode());
        assertEquals("File not found: /nonexistent/File.java", badResp.getError().getMessage());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        ToolResponse emptyResp = tool.execute(emptyPath);
        assertFalse(emptyResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, emptyResp.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", emptyResp.getError().getMessage());
    }

    // ========== Semantic-grade tests (CC boundaries from ComplexityBoundaries) ==========

    @Test
    @DisplayName("ComplexityBoundaries: cc01=1, cc05=5, cc06=6, cc10=10, cc11=11 cyclomatic complexity")
    void complexityBoundaries_cyclomaticAtKnownValues() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        Map<String, Integer> ccByName = new java.util.HashMap<>();
        for (Map<String, Object> m : methods) {
            String name = (String) m.get("name");
            Object cc = m.get("cyclomaticComplexity");
            if (cc instanceof Number n) {
                ccByName.put(name, n.intValue());
            }
        }

        assertEquals(1, (int) ccByName.getOrDefault("cc01", -1),
            "cc01 (no decisions) must have CC=1; got: " + ccByName);
        assertEquals(5, (int) ccByName.getOrDefault("cc05", -1),
            "cc05 (4 if statements) must have CC=5; got: " + ccByName);
        assertEquals(6, (int) ccByName.getOrDefault("cc06", -1),
            "cc06 (5 if statements) must have CC=6; got: " + ccByName);
        assertEquals(10, (int) ccByName.getOrDefault("cc10", -1),
            "cc10 (9 if statements) must have CC=10; got: " + ccByName);
        assertEquals(11, (int) ccByName.getOrDefault("cc11", -1),
            "cc11 (10 if statements) must have CC=11; got: " + ccByName);
    }

    @Test
    @DisplayName("ComplexityBoundaries: cognitive complexity is 0/4/5/9/10 for cc01/cc05/cc06/cc10/cc11")
    void complexityBoundaries_cognitiveAtKnownValues() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        Map<String, Integer> cognitiveByName = new java.util.HashMap<>();
        for (Map<String, Object> m : methods) {
            String name = (String) m.get("name");
            Object cog = m.get("cognitiveComplexity");
            if (cog instanceof Number n) {
                cognitiveByName.put(name, n.intValue());
            }
        }

        // Cognitive complexity has no base, charges 1 per decision point plus nesting
        // penalty. All if statements in these fixtures are at nesting level 0, so each
        // adds exactly 1 + 0 = 1 to cognitive.
        assertEquals(0, (int) cognitiveByName.getOrDefault("cc01", -1),
            "cc01 has no decisions; cognitive=0. Got: " + cognitiveByName);
        assertEquals(4, (int) cognitiveByName.getOrDefault("cc05", -1),
            "cc05 has 4 top-level if statements; cognitive=4. Got: " + cognitiveByName);
        assertEquals(5, (int) cognitiveByName.getOrDefault("cc06", -1),
            "cc06 has 5 top-level if statements; cognitive=5. Got: " + cognitiveByName);
        assertEquals(9, (int) cognitiveByName.getOrDefault("cc10", -1),
            "cc10 has 9 top-level if statements; cognitive=9. Got: " + cognitiveByName);
        assertEquals(10, (int) cognitiveByName.getOrDefault("cc11", -1),
            "cc11 has 10 top-level if statements; cognitive=10. Got: " + cognitiveByName);
    }

    @Test
    @DisplayName("Java21Modern: switch-expression cases each contribute +1 to CC (describe has 4 non-default cases)")
    void java21Modern_switchExpressionCases_contributeToCC() {
        // describe(Object) is a switch expression with: case null, case String s,
        // case Integer i, case int[] arr, default. The tool counts each non-default
        // SwitchCase as +1 toward CC; `case null` is a non-default SwitchCase, so
        // there are 4 non-default cases plus the base 1. Pinning CC=5 here guards
        // against regressions in SwitchCase handling for pattern-matching switches.
        String javaPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Java21Modern.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", javaPath);
        args.put("includeDetails", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        Map<String, Integer> ccByName = new java.util.HashMap<>();
        for (Map<String, Object> m : methods) {
            Object cc = m.get("cyclomaticComplexity");
            if (cc instanceof Number n) {
                ccByName.put((String) m.get("name"), n.intValue());
            }
        }
        Integer describeCC = ccByName.get("describe");
        assertNotNull(describeCC, "describe must appear in methods; got: " + ccByName);
        // base 1 + 4 non-default SwitchCase (null, String s, Integer i, int[] arr); default excluded.
        assertEquals(5, (int) describeCC,
            "describe CC = 1 base + 4 non-default switch cases. Got: " + describeCC);
    }

    @Test
    @DisplayName("ComplexityBoundaries: risk classification (low <=5, medium 6-10, high >10)")
    void complexityBoundaries_riskClassification() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        Map<String, String> riskByName = new java.util.HashMap<>();
        for (Map<String, Object> m : methods) {
            riskByName.put((String) m.get("name"), (String) m.get("risk"));
        }

        // Verify boundary classification exactly per documented thresholds.
        assertEquals("low", riskByName.get("cc01"),
            "cc01 (CC=1) → low. Got: " + riskByName);
        assertEquals("low", riskByName.get("cc05"),
            "cc05 (CC=5) → low (boundary: <=5 is low). Got: " + riskByName);
        assertEquals("medium", riskByName.get("cc06"),
            "cc06 (CC=6) → medium (boundary: >5 is medium). Got: " + riskByName);
        assertEquals("medium", riskByName.get("cc10"),
            "cc10 (CC=10) → medium (boundary: <=10 is medium). Got: " + riskByName);
        assertEquals("high", riskByName.get("cc11"),
            "cc11 (CC=11) → high (boundary: >10 is high). Got: " + riskByName);

        // riskAssessment summary must reflect these counts: 2 low (cc01, cc05), 2
        // medium (cc06, cc10), 1 high (cc11).
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("riskAssessment");
        assertEquals(2, ((Number) risk.get("lowRiskMethods")).intValue(),
            "Expected 2 low-risk methods (cc01, cc05); got: " + risk);
        assertEquals(2, ((Number) risk.get("mediumRiskMethods")).intValue(),
            "Expected 2 medium-risk methods (cc06, cc10); got: " + risk);
        assertEquals(1, ((Number) risk.get("highRiskMethods")).intValue(),
            "Expected 1 high-risk method (cc11); got: " + risk);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("file block has path, physicalLOC, blankLines, commentLines, codeLOC")
    void fileBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) getData(r).get("file");
        for (String key : List.of("path", "physicalLOC", "blankLines", "commentLines", "codeLOC")) {
            assertNotNull(file.get(key), key + " missing on file block: " + file);
        }
        int physical = ((Number) file.get("physicalLOC")).intValue();
        int blank = ((Number) file.get("blankLines")).intValue();
        int comment = ((Number) file.get("commentLines")).intValue();
        int code = ((Number) file.get("codeLOC")).intValue();
        assertEquals(physical, blank + comment + code,
            "physicalLOC = blankLines + commentLines + codeLOC; got " + file);
    }

    @Test
    @DisplayName("summary has totalCyclomaticComplexity, totalCognitiveComplexity, methodCount, averageMethodCC, maxMethodCC")
    void summary_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) getData(r).get("summary");
        for (String key : List.of("totalCyclomaticComplexity", "totalCognitiveComplexity",
                "methodCount", "averageMethodCC", "maxMethodCC")) {
            assertNotNull(summary.get(key), key + " missing on summary: " + summary);
        }
    }

    @Test
    @DisplayName("Per-method entry has name, cyclomaticComplexity, cognitiveComplexity, risk, line")
    void methodEntry_shape() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        for (Map<String, Object> m : methods) {
            for (String key : List.of("name", "cyclomaticComplexity", "cognitiveComplexity", "risk", "line")) {
                assertNotNull(m.get(key), key + " missing on method entry: " + m);
            }
        }
    }

    @Test
    @DisplayName("methodCount in summary equals methods.size() when includeDetails=true")
    void methodCount_matchesListSize() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        assertEquals(((Number) summary.get("methodCount")).intValue(), methods.size());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: ComplexityBoundaries cyclomatic and risk counts are exact")
    void envelope_complexityBoundaries_exact() {
        String boundariesPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/ComplexityBoundaries.java").toString();
        ObjectNode args = envelope.args();
        args.put("filePath", boundariesPath);
        args.put("includeDetails", true);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_complexity_metrics", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_complexity_metrics failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        java.util.Map<String, Integer> ccByName = new java.util.HashMap<>();
        for (JsonNode m : data.get("methods")) {
            ccByName.put(m.get("name").asText(), m.get("cyclomaticComplexity").asInt());
        }
        assertEquals(1, (int) ccByName.get("cc01"), "cc01 CC=1 through envelope");
        assertEquals(5, (int) ccByName.get("cc05"), "cc05 CC=5 through envelope");
        assertEquals(11, (int) ccByName.get("cc11"), "cc11 CC=11 through envelope");
        JsonNode risk = data.get("riskAssessment");
        assertEquals(2, risk.get("lowRiskMethods").asInt());
        assertEquals(2, risk.get("mediumRiskMethods").asInt());
        assertEquals(1, risk.get("highRiskMethods").asInt());
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
}
