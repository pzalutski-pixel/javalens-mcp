package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins analyze_change_impact's transitive mode (issue #20: full blast radius)
 * against the reachability-maven fixture. The search-BFS depth mode is pinned
 * by AnalyzeChangeImpactToolTest and stays unchanged; transitive=true switches
 * to the project graph's reverse closure, which crosses override declarations
 * and has no depth ceiling. Every expectation is the whole payload, through
 * execute() and through the JSON-RPC envelope; affected methods are sorted by key
 * and affected files by path, each with the number of affected methods it declares.
 */
class AnalyzeChangeImpactTransitiveTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String MAIN = "src/main/java/com/reach/";
    private static final String TEST = "src/test/java/com/reach/";

    private AnalyzeChangeImpactTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("reachability-maven");
        tool = new AnalyzeChangeImpactTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("reachability-maven");
    }

    private ObjectNode argsAt(String relativePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve(relativePath).toString());
        args.put("line", line);
        args.put("column", column);
        args.put("transitive", true);
        return args;
    }

    @Test
    @DisplayName("transitive impact on a CLASS aggregates its members' callers (issue #32)")
    void transitive_typeAggregatesMembers() {
        // Widget is exercised only through its explicit ctor + compute() - the
        // type node has no direct caller. The class's blast radius must still
        // include the member-level caller (WidgetTest.computesViaMember).
        assertPayload(argsAt(MAIN + "Widget.java", 9, 13), transitive("Widget", "SourceType",
            List.of("com.reach.WidgetTest#computesViaMember()"), 1,
            List.of(file(TEST + "WidgetTest.java", 1))));
    }

    @Test
    @DisplayName("transitive closure crosses interface dispatch with no depth ceiling")
    void transitive_crossesOverrideDeclarations() {
        // prefix <- EnglishGreeter.greet <- Greeter.greet <- App.run <- Main.main
        // is an effective depth of 4 through an override hop: out of reach for
        // the depth<=3 search mode, exact for the graph closure.
        assertPayload(argsAt(MAIN + "EnglishGreeter.java", 13, 20),
            transitive("prefix", "SourceMethod", PREFIX_CALLERS, 5, PREFIX_FILES));
    }

    @Test
    @DisplayName("transitive closure walks test helper chains and groups affected files")
    void transitive_exactMethodsAndFiles() {
        assertPayload(argsAt(MAIN + "TestedOnly.java", 8, 16), transitive("onlyFromTest", "SourceMethod",
            List.of(
                "com.reach.TestedOnlyTest#doublesInput()",
                "com.reach.TestedOnlyTest#helper()",
                "com.reach.TestedOnlyTest#viaHelper()"), 3,
            List.of(file(TEST + "TestedOnlyTest.java", 3))));
    }

    @Test
    @DisplayName("a symbol with no callers returns empty sets, not an error")
    void transitive_noCallers_emptySuccess() {
        assertPayload(argsAt(MAIN + "Main.java", 8, 24),
            transitive("main", "SourceMethod", List.of(), 0, List.of()));
    }

    @Test
    @DisplayName("transitive mode caps affectedMethods at maxResults with true totals")
    void transitive_maxResultsTruncates() {
        // affectedFiles counts every affected method, the listed and the cut.
        ObjectNode args = argsAt(MAIN + "EnglishGreeter.java", 13, 20);
        args.put("maxResults", 2);
        assertPayload(args, transitive("prefix", "SourceMethod", PREFIX_CALLERS.subList(0, 2), 5, PREFIX_FILES));
    }

    @Test
    @DisplayName("depth mode is unaffected by the option: onlyFromTest's two call sites in TestedOnlyTest")
    void depthMode_shapeUnchanged() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve(MAIN + "TestedOnly.java").toString());
        args.put("line", 8);
        args.put("column", 16);
        String testFile = TEST + "TestedOnlyTest.java";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", "onlyFromTest");
        data.put("symbolType", "SourceMethod");
        data.put("depth", 1);
        data.put("directReferences", 2);
        data.put("totalReferences", 2);
        data.put("affectedFiles", List.of(Map.of("filePath", testFile, "referenceCount", 2)));
        data.put("callSites", List.of(
            callSite(testFile, 14, 17, "assert t.onlyFromTest(2) == 4;", "doublesInput"),
            callSite(testFile, 23, 32, "return new TestedOnly().onlyFromTest(3);", "helper")));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", 2);
        meta.put("returnedCount", 2);
        meta.put("suggestedNextTools", List.of(
            "rename_symbol for safe renaming across all affected files",
            "find_references for simple reference list without grouping"));
        assertPayload(args, Map.of("success", true, "data", data, "meta", meta));
    }

    // ========== exact payload helpers ==========

    private static final List<String> PREFIX_CALLERS = List.of(
        "com.reach.App#run(String)",
        "com.reach.EnglishGreeter#greet(String)",
        "com.reach.GreeterDispatchTest#disabledGreeting()",
        "com.reach.GreeterDispatchTest#greetsThroughInterface()",
        "com.reach.Main#main(String[])");
    private static final List<Map<String, Object>> PREFIX_FILES = List.of(
        file(MAIN + "App.java", 1),
        file(MAIN + "EnglishGreeter.java", 1),
        file(MAIN + "Main.java", 1),
        file(TEST + "GreeterDispatchTest.java", 2));

    private static Map<String, Object> file(String path, int methodCount) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("filePath", path);
        file.put("methodCount", methodCount);
        return file;
    }

    /** An onlyFromTest call, `onlyFromTest(n)`, 15 characters, of TestedOnlyTest's {@code method}. */
    private static Map<String, Object> callSite(String file, int line, int column, String context, String method) {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("filePath", file);
        site.put("length", 15);
        site.put("line", line);
        site.put("column", column);
        site.put("context", context);
        site.put("depth", 1);
        site.put("enclosingMethod", method);
        site.put("enclosingType", "com.reach.TestedOnlyTest");
        return site;
    }

    /** The transitive payload listing {@code listed} of {@code total} affected methods. */
    private static Map<String, Object> transitive(String symbol, String symbolType, List<String> listed, int total,
                                                  List<Map<String, Object>> files) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("symbolType", symbolType);
        data.put("transitive", true);
        data.put("totalAffectedMethods", total);
        data.put("affectedMethods", listed);
        data.put("affectedFiles", files);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", listed.size());
        meta.put("truncated", listed.size() < total);
        meta.put("suggestedNextTools", List.of(
            "find_affected_tests for the tests covering this symbol",
            "find_references for the direct call sites with locations"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each without its call sites' raw offsets.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = withoutOffsets(objectMapper.readTree(
                objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = withoutOffsets(envelope.assertEnvelopeFidelity("analyze_change_impact", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("callSites").forEach(site -> ((ObjectNode) site).remove("offset"));
        return copy;
    }
}
