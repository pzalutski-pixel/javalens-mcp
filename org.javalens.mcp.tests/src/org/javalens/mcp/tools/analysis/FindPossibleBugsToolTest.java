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

    @Test @DisplayName("BugPatterns.java: exactly 7 issues, counts consistent")
    void findsBugsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<?> issues = (List<?>) data.get("issues");
        int total = ((Number) data.get("totalIssues")).intValue();
        int high = ((Number) data.get("highCount")).intValue();
        int medium = ((Number) data.get("mediumCount")).intValue();
        int low = ((Number) data.get("lowCount")).intValue();
        // EMPTY_CATCH x2, STRING_COMPARISON x2, SYNC_ON_STRING x2, RESOURCE_LEAK x1.
        assertEquals(7, total, "BugPatterns has exactly 7 detectable issues; got: " + issues);
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

        // The tool reports each issue with a stable `code`. BugPatterns triggers exactly
        // four distinct detectors: empty catch, == on String, sync on String, unclosed
        // resource (emptyExceptionHandler / stringCompareWithOperator / syncOnStringVariable
        // / unclosedResource / multipleBugs). No NULL_DEREFERENCE here.
        java.util.Set<String> codes = new java.util.TreeSet<>();
        for (Map<String, Object> issue : issues) {
            codes.add((String) issue.get("code"));
        }
        assertEquals(java.util.Set.of(
            "EMPTY_CATCH", "STRING_COMPARISON", "SYNC_ON_STRING", "RESOURCE_LEAK"), codes,
            "BugPatterns must surface exactly these four issue codes; got: " + issues);
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
        JsonNode payload = envelope.assertEnvelopeFidelity("find_possible_bugs", args);

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

    // ========== severity values ==========

    @Test
    @DisplayName("severity is compared case-insensitively: HIGH on BugPatterns gives exactly its four high issues")
    void severity_caseInsensitive() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "HIGH");
        assertPayload(args, report(4, 0, 0, List.of(
            STRING_COMPARISON_25, SYNC_ON_STRING_41, SYNC_ON_STRING_70, STRING_COMPARISON_71)));
    }

    @Test
    @DisplayName("an undocumented severity is INVALID_PARAMETER naming the accepted values")
    void severity_unknownIsRefused() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "critical");
        assertPayload(args, Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'severity': Must be one of high, medium, low, all "
                + "(case-insensitive); got: critical")));
    }

    // ========== exact payload helpers ==========

    private static final String BUG_PATTERNS = "src/main/java/com/example/BugPatterns.java";

    /*
     * The issues of BugPatterns, in the order the AST visit meets them. Each is reported at the
     * start of its node: the catch keyword, the left operand of ==, the synchronized keyword and
     * the declaration statement.
     */
    private static final Map<String, Object> EMPTY_CATCH_16 =
        issue("EMPTY_CATCH", "Empty catch block swallows exception", "medium", BUG_PATTERNS, 16, 10);
    private static final Map<String, Object> STRING_COMPARISON_25 = issue("STRING_COMPARISON",
        "String comparison with == instead of equals()", "high", BUG_PATTERNS, 25, 15);
    private static final Map<String, Object> SYNC_ON_STRING_41 = issue("SYNC_ON_STRING",
        "Synchronizing on String (interned strings are shared)", "high", BUG_PATTERNS, 41, 8);
    private static final Map<String, Object> RESOURCE_LEAK_51 = issue("RESOURCE_LEAK",
        "Closeable resource may not be closed: InputStream", "medium", BUG_PATTERNS, 51, 8);
    private static final Map<String, Object> SYNC_ON_STRING_70 = issue("SYNC_ON_STRING",
        "Synchronizing on String (interned strings are shared)", "high", BUG_PATTERNS, 70, 12);
    private static final Map<String, Object> STRING_COMPARISON_71 = issue("STRING_COMPARISON",
        "String comparison with == instead of equals()", "high", BUG_PATTERNS, 71, 23);
    private static final Map<String, Object> EMPTY_CATCH_73 =
        issue("EMPTY_CATCH", "Empty catch block swallows exception", "medium", BUG_PATTERNS, 73, 10);

    private static Map<String, Object> issue(String code, String message, String severity, String file,
                                             int line, int column) {
        Map<String, Object> issue = new java.util.LinkedHashMap<>();
        issue.put("code", code);
        issue.put("message", message);
        issue.put("severity", severity);
        issue.put("filePath", file);
        issue.put("line", line);
        issue.put("column", column);
        return issue;
    }

    /** The success payload listing {@code issues}, of which {@code high}, {@code medium} and {@code low} are of each severity. */
    private static Map<String, Object> report(int high, int medium, int low, List<Map<String, Object>> issues) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("totalIssues", issues.size());
        data.put("highCount", high);
        data.put("mediumCount", medium);
        data.put("lowCount", low);
        data.put("issues", issues);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", issues.size());
        meta.put("returnedCount", issues.size());
        meta.put("suggestedNextTools", issues.isEmpty()
            ? List.of("No potential bugs found")
            : List.of("Review and fix the identified issues"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
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
        JsonNode wire = envelope.assertEnvelopeFidelity("find_possible_bugs", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
