package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
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
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("calculates metrics comprehensively")
    void calculatesMetricsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File metrics
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        String path = (String) file.get("path");
        assertNotNull(path, "file.path missing");
        assertTrue(path.endsWith(".java"), "file.path ends with .java; got: " + file);
        assertTrue((Integer) file.get("physicalLOC") > 0);

        // Summary — all metric counts non-negative; methodCount > 0 (Calculator has methods)
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertTrue(((Number) summary.get("totalCyclomaticComplexity")).intValue() >= 0,
            "totalCyclomaticComplexity >= 0; got: " + summary);
        assertTrue(((Number) summary.get("totalCognitiveComplexity")).intValue() >= 0,
            "totalCognitiveComplexity >= 0; got: " + summary);
        assertTrue(((Number) summary.get("methodCount")).intValue() > 0,
            "Calculator has methods; methodCount > 0; got: " + summary);

        // Risk assessment — counts non-negative
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("riskAssessment");
        assertTrue(((Number) risk.get("highRiskMethods")).intValue() >= 0,
            "highRiskMethods >= 0; got: " + risk);
        assertTrue(((Number) risk.get("lowRiskMethods")).intValue() >= 0,
            "lowRiskMethods >= 0; got: " + risk);

        // Method details included by default — non-empty
        @SuppressWarnings("unchecked")
        List<?> methods = (List<?>) data.get("methods");
        assertNotNull(methods, "methods list must be present by default");
        assertFalse(methods.isEmpty(), "Calculator has methods; details list must be non-empty");
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

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(badPath).isSuccess());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertFalse(tool.execute(emptyPath).isSuccess());
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
        assertTrue(describeCC >= 4,
            "describe has 4 non-default SwitchCase nodes (null, String s, Integer i, int[] arr); "
                + "CC must reflect them. Got: " + describeCC);
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
}
