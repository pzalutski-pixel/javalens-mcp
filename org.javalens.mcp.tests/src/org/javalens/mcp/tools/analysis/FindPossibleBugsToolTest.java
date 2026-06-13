package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindPossibleBugsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindPossibleBugsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindPossibleBugsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds bugs comprehensively")
    void findsBugsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<?> issues = (List<?>) data.get("issues");
        assertNotNull(issues, "issues list missing");
        int total = ((Number) data.get("totalIssues")).intValue();
        int high = ((Number) data.get("highCount")).intValue();
        int medium = ((Number) data.get("mediumCount")).intValue();
        int low = ((Number) data.get("lowCount")).intValue();
        assertTrue(total >= 0 && high >= 0 && medium >= 0 && low >= 0,
            "all counts >= 0; got: " + data);
        assertEquals(high + medium + low, total,
            "high+medium+low must equal totalIssues; got: " + data);
        assertEquals(total, issues.size(),
            "totalIssues must equal issues list size; got: " + data);
    }

    @Test @DisplayName("supports severity filter")
    void supportsSeverityFilter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "high");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) getData(r).get("issues");
        for (Map<String, Object> issue : issues) {
            assertEquals("high", issue.get("severity"));
        }
    }

    @Test @DisplayName("returns no issues for clean file")
    void returnsNoIssuesForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals(0, getData(r).get("totalIssues"));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalIssues"));
    }

    // ========== Semantic-grade tests (exact bug patterns in BugPatterns.java) ==========

    @Test
    @DisplayName("BugPatterns.java: detects empty catch, ==-on-string, sync-on-string, unclosed resource")
    void bugPatterns_detectsAllDocumentedIssueCategories() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) getData(r).get("issues");

        // Collect rule/category labels (the tool reports each issue with some kind of
        // category identifier). We don't pin the exact key name; collect across the most
        // common label fields.
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (Map<String, Object> issue : issues) {
            for (String field : List.of("rule", "category", "type", "name", "message", "description")) {
                Object v = issue.get(field);
                if (v != null) labels.add(v.toString().toLowerCase());
            }
        }

        // The fixture has: emptyExceptionHandler (empty catch), stringCompareWithOperator
        // (== on String), syncOnStringVariable (sync on String field), unclosedResource
        // (FileInputStream not closed), multipleBugs (combination). At minimum the tool
        // must surface each category.
        assertTrue(labels.stream().anyMatch(l -> l.contains("empty") && l.contains("catch")),
            "Expected an issue mentioning empty catch; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("string") && (l.contains("==") || l.contains("equals") || l.contains("compar"))),
            "Expected an issue mentioning String == comparison; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("sync") && l.contains("string")),
            "Expected an issue mentioning synchronization on String; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("resource") || l.contains("close")),
            "Expected an issue mentioning resource leak / unclosed resource; got labels: " + labels);
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> issuesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("issues");
    }

    @Test
    @DisplayName("Each issue carries severity in {high, medium, low}")
    void issueSeverity_valid() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> validSeverity = java.util.Set.of("high", "medium", "low");
        for (Map<String, Object> i : issuesOf(r)) {
            Object sev = i.get("severity");
            assertNotNull(sev, "severity missing on issue: " + i);
            assertTrue(validSeverity.contains(sev.toString()),
                "severity must be one of {high, medium, low}; got: " + sev);
        }
    }

    @Test
    @DisplayName("highCount + mediumCount + lowCount == totalIssues")
    void counts_consistent() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        long high = ((Number) data.get("highCount")).longValue();
        long med = ((Number) data.get("mediumCount")).longValue();
        long low = ((Number) data.get("lowCount")).longValue();
        long total = ((Number) data.get("totalIssues")).longValue();
        assertEquals(total, high + med + low,
            "high+medium+low must equal totalIssues; got high=" + high + " med=" + med
                + " low=" + low + " total=" + total);
    }

    @Test
    @DisplayName("severity='medium' filter returns only medium-severity issues")
    void severityMedium_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "medium");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> i : issuesOf(r)) {
            assertEquals("medium", i.get("severity"),
                "All issues must be medium when severity=medium; got: " + i);
        }
    }

    @Test
    @DisplayName("severity='low' filter returns only low-severity issues")
    void severityLow_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "low");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> i : issuesOf(r)) {
            assertEquals("low", i.get("severity"),
                "All issues must be low when severity=low; got: " + i);
        }
    }

    @Test
    @DisplayName("Detects null-initialized local that is later dereferenced (documented null-pointer risk)")
    void detectsNullInitializedDereference() {
        // NullDerefPatterns.derefereneAfterAssignedNull declares `String s = null;`
        // and then returns `s.length()`. The contract claims null-pointer risk
        // detection — this deterministic case (NullLiteral initializer, no
        // intervening reassignment, then deref) must be flagged.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NullDerefPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        boolean found = issuesOf(r).stream().anyMatch(i ->
            "NULL_DEREFERENCE".equals(i.get("code")));
        assertTrue(found,
            "Tool documents null-pointer risk detection; a null-initialized local "
                + "dereferenced without reassignment must be flagged with NULL_DEREFERENCE. "
                + "Got: " + issuesOf(r));
    }

    @Test
    @DisplayName("Properly null-checked local is NOT flagged as null-dereference")
    void safeNullCheck_isNotFlagged() {
        // NullDerefPatterns.safeNullCheck guards with `if (s == null) return 0;`
        // before calling `s.length();`. The detector must not flag this.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NullDerefPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        boolean falsePositiveOnSafe = issuesOf(r).stream().anyMatch(i -> {
            int line = ((Number) i.get("line")).intValue();
            // Line 17 (0-based) = source line 18 = `return s.length();` in safeNullCheck
            return "NULL_DEREFERENCE".equals(i.get("code")) && line == 17;
        });
        assertFalse(falsePositiveOnSafe,
            "Null-checked dereference must not be flagged; got: " + issuesOf(r));
    }

    @Test
    @DisplayName("Calculator.java (clean) returns zero issues + zero highCount")
    void cleanFile_zeroCounts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("totalIssues")).intValue());
        assertEquals(0, ((Number) data.get("highCount")).intValue());
        assertEquals(0, ((Number) data.get("mediumCount")).intValue());
        assertEquals(0, ((Number) data.get("lowCount")).intValue());
        assertTrue(issuesOf(r).isEmpty());
    }

    // ========== Exact magnitude + location for a deterministic detector ==========

    private java.util.Set<Integer> nullDerefLines(List<Map<String, Object>> issues) {
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (Map<String, Object> i : issues) {
            if ("NULL_DEREFERENCE".equals(i.get("code"))) {
                lines.add(((Number) i.get("line")).intValue());
            }
        }
        return lines;
    }

    @Test
    @DisplayName("NullDerefPatterns: exactly one NULL_DEREFERENCE, at the deref site (0-based line 10)")
    void nullDereference_exactSingleSiteAndLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NullDerefPatterns.java");
        args.put("maxResults", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        // Only derefereneAfterAssignedNull (`String s = null;` then `return s.length();`)
        // matches the null-literal-initialized rule. The unchecked-parameter deref
        // (dereferenceWithoutCheck) and the guarded ones (safeNullCheck, conditionalDeref)
        // must NOT be flagged. The deref site `return s.length();` is 0-based line 10.
        assertEquals(java.util.Set.of(10), nullDerefLines(issuesOf(r)),
            "exactly one NULL_DEREFERENCE at 0-based line 10; got: " + issuesOf(r));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: the single NULL_DEREFERENCE sits at 0-based line 10")
    void envelope_nullDereference_exactLine() {
        ObjectNode args = envelope.args();
        args.put("filePath", "src/main/java/com/example/NullDerefPatterns.java");
        args.put("maxResults", 100);
        JsonNode payload = envelope.payload("find_possible_bugs", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_possible_bugs failed through the envelope: " + payload);
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (JsonNode i : payload.get("data").get("issues")) {
            if (i.has("code") && "NULL_DEREFERENCE".equals(i.get("code").asText())) {
                lines.add(i.get("line").asInt());
            }
        }
        assertEquals(java.util.Set.of(10), lines,
            "the single NULL_DEREFERENCE magnitude and line must survive the envelope");
    }
}
