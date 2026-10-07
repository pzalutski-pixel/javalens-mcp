package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * find_possible_bugs on simple-maven's BugPatterns, NullDerefPatterns and Calculator, and on the
 * whole possible-bugs project. Every expectation is the whole payload, derived from the fixture
 * source and asserted through execute() and through the JSON-RPC envelope. Each issue is reported
 * at the start of its node, zero-based: the catch keyword, the left operand of ==, the
 * synchronized keyword, the declaration statement, the receiver of the dereference.
 *
 * <p>possible-bugs: org.bugs.Streams leaks the InputStream it declares on line 8 and has an empty
 * catch on line 15; org.bugs.text.Texts compares Strings with == on line 7, synchronizes on its
 * String field on line 11 and dereferences a local initialized to null on line 18; org.bugs.Clean
 * has no issue.
 */
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

    @Test @DisplayName("BugPatterns.java: exactly its 7 issues in visit order, 4 high and 3 medium")
    void findsBugsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        assertPayload(args, report(4, 3, 0, BUG_PATTERNS_ISSUES));
    }

    @Test @DisplayName("severity=high keeps exactly BugPatterns' four high issues")
    void supportsSeverityFilter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "high");
        assertPayload(args, report(4, 0, 0, List.of(
            STRING_COMPARISON_25, SYNC_ON_STRING_41, SYNC_ON_STRING_70, STRING_COMPARISON_71)));
    }

    @Test @DisplayName("Calculator.java, given by absolute path, has no issue")
    void returnsNoIssuesForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        assertPayload(args, report(0, 0, 0, List.of()));
    }

    @Test @DisplayName("Without filePath every file of possible-bugs is analyzed: Streams' two issues and Texts' three")
    void analyzesWholeProject() throws Exception {
        JdtServiceImpl bugs = helper.loadProject("possible-bugs");
        assertPayload(new FindPossibleBugsTool(() -> bugs), new EnvelopeHarness(bugs),
            objectMapper.createObjectNode(), report(3, 2, 0, POSSIBLE_BUGS_ISSUES), byFile());
    }

    // ========== Semantic-grade tests (exact bug patterns in BugPatterns.java) ==========

    @Test
    @DisplayName("BugPatterns.java with severity=all: empty catch, == on String, sync on String, unclosed resource")
    void bugPatterns_detectsAllDocumentedIssueCategories() {
        // Four detectors fire in BugPatterns; it declares no local initialized to null.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "all");
        assertPayload(args, report(4, 3, 0, BUG_PATTERNS_ISSUES));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Each issue carries its severity: Streams' resource leak and empty catch are medium")
    void issueSeverity_valid() throws Exception {
        JdtServiceImpl bugs = helper.loadProject("possible-bugs");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", STREAMS);
        assertPayload(new FindPossibleBugsTool(() -> bugs), new EnvelopeHarness(bugs), args,
            report(0, 2, 0, List.of(RESOURCE_LEAK_8, EMPTY_CATCH_15)), UnaryOperator.identity());
    }

    @Test
    @DisplayName("highCount + mediumCount + lowCount == totalIssues: severity=high over possible-bugs counts Texts' three high issues")
    void counts_consistent() throws Exception {
        JdtServiceImpl bugs = helper.loadProject("possible-bugs");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "high");
        assertPayload(new FindPossibleBugsTool(() -> bugs), new EnvelopeHarness(bugs), args,
            report(3, 0, 0, List.of(STRING_COMPARISON_7, SYNC_ON_STRING_11, NULL_DEREFERENCE_18)), byFile());
    }

    @Test
    @DisplayName("severity='medium' keeps exactly BugPatterns' three medium issues")
    void severityMedium_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "medium");
        assertPayload(args, report(0, 3, 0, List.of(EMPTY_CATCH_16, RESOURCE_LEAK_51, EMPTY_CATCH_73)));
    }

    @Test
    @DisplayName("severity='low' keeps nothing: no detector reports a low issue")
    void severityLow_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", BUG_PATTERNS);
        args.put("severity", "low");
        assertPayload(args, report(0, 0, 0, List.of()));
    }

    @Test
    @DisplayName("Detects the local initialized to null and then dereferenced (documented null-pointer risk)")
    void detectsNullInitializedDereference() {
        // derefereneAfterAssignedNull declares `String s = null;` and returns `s.length()`.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", NULL_DEREF);
        assertPayload(args, report(1, 0, 0, List.of(NULL_DEREFERENCE_10)));
    }

    @Test
    @DisplayName("The null-checked dereference in safeNullCheck (line 17) is not flagged")
    void safeNullCheck_isNotFlagged() {
        // The only issue of NullDerefPatterns is on line 10; safeNullCheck guards with
        // `if (s == null) return 0;` before `s.length()`.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", NULL_DEREF);
        assertPayload(args, report(1, 0, 0, List.of(NULL_DEREFERENCE_10)));
    }

    @Test
    @DisplayName("Calculator.java, given relative to the project, returns zero issues and zero counts")
    void cleanFile_zeroCounts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/Calculator.java");
        assertPayload(args, report(0, 0, 0, List.of()));
    }

    // ========== Exact magnitude + location for a deterministic detector ==========

    @Test
    @DisplayName("NullDerefPatterns: exactly one NULL_DEREFERENCE, at the deref site (0-based line 10)")
    void nullDereference_exactSingleSiteAndLine() {
        // Only derefereneAfterAssignedNull matches the null-literal-initialized rule. The
        // unchecked-parameter deref (dereferenceWithoutCheck) and the guarded ones
        // (safeNullCheck, conditionalDeref) are not flagged.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", NULL_DEREF);
        args.put("severity", "High");
        assertPayload(args, report(1, 0, 0, List.of(NULL_DEREFERENCE_10)));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: the single NULL_DEREFERENCE sits at 0-based line 10")
    void envelope_nullDereference_exactLine() {
        ObjectNode args = envelope.args();
        args.put("filePath", NULL_DEREF);
        assertPayload(args, report(1, 0, 0, List.of(NULL_DEREFERENCE_10)));
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
    private static final String NULL_DEREF = "src/main/java/com/example/NullDerefPatterns.java";
    private static final String STREAMS = "src/main/java/org/bugs/Streams.java";
    private static final String TEXTS = "src/main/java/org/bugs/text/Texts.java";

    private static final String EMPTY_CATCH = "Empty catch block swallows exception";
    private static final String STRING_COMPARISON = "String comparison with == instead of equals()";
    private static final String SYNC_ON_STRING = "Synchronizing on String (interned strings are shared)";

    /* The issues of BugPatterns, in the order the AST visit meets them. */
    private static final Map<String, Object> EMPTY_CATCH_16 =
        issue("EMPTY_CATCH", EMPTY_CATCH, "medium", BUG_PATTERNS, 16, 10);
    private static final Map<String, Object> STRING_COMPARISON_25 =
        issue("STRING_COMPARISON", STRING_COMPARISON, "high", BUG_PATTERNS, 25, 15);
    private static final Map<String, Object> SYNC_ON_STRING_41 =
        issue("SYNC_ON_STRING", SYNC_ON_STRING, "high", BUG_PATTERNS, 41, 8);
    private static final Map<String, Object> RESOURCE_LEAK_51 = issue("RESOURCE_LEAK",
        "Closeable resource may not be closed: InputStream", "medium", BUG_PATTERNS, 51, 8);
    private static final Map<String, Object> SYNC_ON_STRING_70 =
        issue("SYNC_ON_STRING", SYNC_ON_STRING, "high", BUG_PATTERNS, 70, 12);
    private static final Map<String, Object> STRING_COMPARISON_71 =
        issue("STRING_COMPARISON", STRING_COMPARISON, "high", BUG_PATTERNS, 71, 23);
    private static final Map<String, Object> EMPTY_CATCH_73 =
        issue("EMPTY_CATCH", EMPTY_CATCH, "medium", BUG_PATTERNS, 73, 10);
    private static final List<Map<String, Object>> BUG_PATTERNS_ISSUES = List.of(EMPTY_CATCH_16,
        STRING_COMPARISON_25, SYNC_ON_STRING_41, RESOURCE_LEAK_51, SYNC_ON_STRING_70, STRING_COMPARISON_71,
        EMPTY_CATCH_73);

    private static final Map<String, Object> NULL_DEREFERENCE_10 = issue("NULL_DEREFERENCE",
        "Variable `s` is initialized to null and dereferenced without reassignment", "high", NULL_DEREF, 10, 15);

    /* The issues of possible-bugs, by file and then in visit order. */
    private static final Map<String, Object> RESOURCE_LEAK_8 = issue("RESOURCE_LEAK",
        "Closeable resource may not be closed: InputStream", "medium", STREAMS, 8, 8);
    private static final Map<String, Object> EMPTY_CATCH_15 =
        issue("EMPTY_CATCH", EMPTY_CATCH, "medium", STREAMS, 15, 10);
    private static final Map<String, Object> STRING_COMPARISON_7 =
        issue("STRING_COMPARISON", STRING_COMPARISON, "high", TEXTS, 7, 15);
    private static final Map<String, Object> SYNC_ON_STRING_11 =
        issue("SYNC_ON_STRING", SYNC_ON_STRING, "high", TEXTS, 11, 8);
    private static final Map<String, Object> NULL_DEREFERENCE_18 = issue("NULL_DEREFERENCE",
        "Variable `text` is initialized to null and dereferenced without reassignment", "high", TEXTS, 18, 15);
    private static final List<Map<String, Object>> POSSIBLE_BUGS_ISSUES = List.of(RESOURCE_LEAK_8, EMPTY_CATCH_15,
        STRING_COMPARISON_7, SYNC_ON_STRING_11, NULL_DEREFERENCE_18);

    private static Map<String, Object> issue(String code, String message, String severity, String file,
                                             int line, int column) {
        Map<String, Object> issue = new LinkedHashMap<>();
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalIssues", issues.size());
        data.put("highCount", high);
        data.put("mediumCount", medium);
        data.put("lowCount", low);
        data.put("issues", issues);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", issues.size());
        meta.put("returnedCount", issues.size());
        meta.put("suggestedNextTools", issues.isEmpty()
            ? List.of("No potential bugs found")
            : List.of("Review and fix the identified issues"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload with its issues in file order: a project-wide scan visits the files in the
     * project's package order, and the sort is stable, so each file's issues keep their visit order.
     */
    private static UnaryOperator<JsonNode> byFile() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            if (copy.path("data") instanceof ObjectNode data && data.get("issues") instanceof ArrayNode issues) {
                List<JsonNode> sorted = new ArrayList<>();
                issues.forEach(sorted::add);
                sorted.sort(Comparator.comparing(issue -> issue.path("filePath").asText()));
                data.putArray("issues").addAll(sorted);
            }
            return copy;
        };
    }

    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        assertPayload(tool, envelope, args, expected, UnaryOperator.identity());
    }

    /**
     * The payload of {@code bugsTool} through execute(), read back from the JSON text it
     * serializes to as a client reads it, and through {@code harness}'s JSON-RPC envelope both
     * equal {@code expected} after {@code normalize}.
     */
    private void assertPayload(FindPossibleBugsTool bugsTool, EnvelopeHarness harness, ObjectNode args,
                               Map<String, Object> expected, UnaryOperator<JsonNode> normalize) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = normalize.apply(objectMapper.readTree(
                objectMapper.writeValueAsString(bugsTool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = normalize.apply(harness.assertEnvelopeFidelity("find_possible_bugs", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
