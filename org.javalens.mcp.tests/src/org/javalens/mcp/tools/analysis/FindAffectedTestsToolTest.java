package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins find_affected_tests (issue #21) against the reachability-maven
 * fixture: direct coverage, transitive coverage through non-test helpers,
 * coverage through interface dispatch, disabled-test flagging, the empty
 * case, self-selection, and maxResults boundaries.
 */
class FindAffectedTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindAffectedTestsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("reachability-maven");
        tool = new FindAffectedTestsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("reachability-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> testMethods(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("testMethods");
    }

    private ObjectNode argsAt(String relativePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve(relativePath).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("coordinate round-trip: search_symbols output feeds find_affected_tests verbatim (issue #31)")
    void searchSymbols_coordinatesRoundTrip() {
        // #31 claimed search_symbols is 1-based and find_affected_tests off by
        // one. Pin the contract: the line/column search_symbols reports for a
        // class resolves that exact symbol in find_affected_tests with no
        // adjustment - both are 0-based and consistent.
        org.javalens.mcp.tools.SearchSymbolsTool search =
            new org.javalens.mcp.tools.SearchSymbolsTool(() -> service);
        ObjectNode searchArgs = objectMapper.createObjectNode();
        searchArgs.put("query", "Widget");
        searchArgs.put("kind", "class");
        ToolResponse searchResult = search.execute(searchArgs);
        assertTrue(searchResult.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) getData(searchResult).get("results");
        Map<String, Object> widget = results.stream()
            .filter(s -> "com.reach.Widget".equals(s.get("qualifiedName")))
            .findFirst().orElseThrow();
        int line = ((Number) widget.get("line")).intValue();
        int column = ((Number) widget.get("column")).intValue();

        ToolResponse affected = tool.execute(argsAt("src/main/java/com/reach/Widget.java", line, column));
        assertTrue(affected.isSuccess(),
            () -> "search_symbols position " + line + ":" + column
                + " must resolve in find_affected_tests with no off-by-one; got: " + affected.getError());
        assertEquals("Widget", getData(affected).get("symbol"));
        assertEquals(1, getData(affected).get("testMethodCount"),
            "the round-tripped class must report its covering test");
    }

    @Test
    @DisplayName("selecting a CLASS aggregates coverage of its members (issue #32)")
    void typeSelection_aggregatesMemberCoverage() {
        // Widget has an explicit constructor and is exercised by WidgetTest
        // only through its members (new Widget().compute(...)). The type node
        // itself has no incoming edge - pre-fix this returned 0 tests.
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/Widget.java", 9, 13));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("Widget", data.get("symbol"));
        assertEquals("SourceType", data.get("symbolType"));
        assertEquals(1, data.get("testMethodCount"));

        List<Map<String, Object>> tests = testMethods(r);
        assertEquals(1, tests.size(), () -> "tests: " + tests);
        assertEquals("WidgetTest", tests.get(0).get("className"));
        assertEquals("computesViaMember", tests.get(0).get("methodName"));
    }

    @Test
    @DisplayName("selecting a FIELD returns the tests that read it (the claim's field dimension)")
    void fieldSelection_returnsReadingTests() {
        // Widget.seed (0-based line 11, col 21) is read by WidgetTest. The
        // description claims field targets are supported - pin it.
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/Widget.java", 11, 21));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("seed", data.get("symbol"));
        assertEquals(1, data.get("testMethodCount"));
        assertEquals("computesViaMember", testMethods(r).get(0).get("methodName"));
    }

    @Test
    @DisplayName("selecting a class exercised through interface dispatch finds the dispatch tests")
    void typeSelection_throughInterfaceDispatch() {
        // EnglishGreeter: instantiated (type node) AND its greet/prefix members
        // covered by the dispatch tests - the type closure must surface them.
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/EnglishGreeter.java", 6, 13));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("EnglishGreeter", data.get("symbol"));
        java.util.Set<String> names = testMethods(r).stream()
            .map(t -> (String) t.get("methodName"))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("greetsThroughInterface", "disabledGreeting"), names,
            () -> "both dispatch tests must cover the class; got: " + names);
    }

    @Test
    @DisplayName("direct and transitive coverage: exact test methods, helper excluded")
    void directAndTransitiveCoverage() {
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/TestedOnly.java", 8, 16));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("onlyFromTest", data.get("symbol"));
        assertEquals(2, data.get("testMethodCount"));

        List<Map<String, Object>> tests = testMethods(r);
        assertEquals(2, tests.size());

        Map<String, Object> direct = tests.get(0);
        assertEquals("TestedOnlyTest", direct.get("className"));
        assertEquals("doublesInput", direct.get("methodName"));
        assertEquals("com.reach.TestedOnlyTest#doublesInput()", direct.get("key"));
        assertEquals("src/test/java/com/reach/TestedOnlyTest.java", direct.get("filePath"));
        assertEquals(12, direct.get("line"));
        assertNull(direct.get("disabled"), "enabled tests carry no disabled flag");

        Map<String, Object> transitive = tests.get(1);
        assertEquals("viaHelper", transitive.get("methodName"));
        assertEquals(18, transitive.get("line"));

        // helper() reaches the symbol but is not a test method.
        assertTrue(tests.stream().noneMatch(t -> "helper".equals(t.get("methodName"))),
            "non-test helper must not appear: " + tests);
    }

    @Test
    @DisplayName("coverage through interface dispatch; disabled covering test is flagged")
    void interfaceDispatchAndDisabledFlag() {
        // prefix is reached only via EnglishGreeter.greet <- Greeter.greet <- tests.
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/EnglishGreeter.java", 13, 20));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        List<Map<String, Object>> tests = testMethods(r);
        assertEquals(2, tests.size(), () -> "tests: " + tests);

        Map<String, Object> enabled = tests.get(0);
        assertEquals("greetsThroughInterface", enabled.get("methodName"));
        assertNull(enabled.get("disabled"));

        Map<String, Object> disabled = tests.get(1);
        assertEquals("disabledGreeting", disabled.get("methodName"));
        assertEquals(Boolean.TRUE, disabled.get("disabled"),
            "@Disabled covering test must be flagged so the agent knows it will not run");
    }

    @Test
    @DisplayName("a symbol no test reaches returns an empty set, not an error")
    void noCoveringTests_emptySuccess() {
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/Orphan.java", 11, 17));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        assertEquals("deadMethod", getData(r).get("symbol"));
        assertEquals(0, getData(r).get("testMethodCount"));
        assertEquals(List.of(), testMethods(r));
    }

    @Test
    @DisplayName("selecting a test method includes the test itself")
    void selectingTestMethod_includesItself() {
        ToolResponse r = tool.execute(argsAt("src/test/java/com/reach/TestedOnlyTest.java", 12, 10));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        List<Map<String, Object>> tests = testMethods(r);
        assertEquals(1, tests.size());
        assertEquals("doublesInput", tests.get(0).get("methodName"));
    }

    @Test
    @DisplayName("maxResults truncates with true totals")
    void maxResults_truncates() {
        ObjectNode args = argsAt("src/main/java/com/reach/EnglishGreeter.java", 13, 20);
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, testMethods(r).size());
        assertEquals(2, getData(r).get("testMethodCount"));
        assertEquals(2, r.getMeta().getTotalCount());
        assertEquals(1, r.getMeta().getReturnedCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    @Test
    @DisplayName("negative maxResults is rejected as INVALID_PARAMETER")
    void maxResults_negativeRejected() {
        ObjectNode args = argsAt("src/main/java/com/reach/TestedOnly.java", 8, 16);
        args.put("maxResults", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("position without a symbol returns SYMBOL_NOT_FOUND")
    void noSymbol_symbolNotFound() {
        ToolResponse r = tool.execute(argsAt("src/main/java/com/reach/TestedOnly.java", 1, 0));
        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode());
    }

    @Test
    @DisplayName("without a loaded project returns PROJECT_NOT_LOADED")
    void projectNotLoaded() {
        FindAffectedTestsTool unloaded = new FindAffectedTestsTool(() -> null);
        ToolResponse r = unloaded.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", r.getError().getCode());
    }
}
