package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindUnreachableCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins find_unreachable_code against the reachability-maven fixture: exact
 * unreachable inventory with mains+tests as roots, root reporting, the
 * test-roots toggle, type suppression when any member is reachable,
 * deterministic ordering, and maxResults boundaries.
 */
class FindUnreachableCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindUnreachableCodeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("reachability-maven");
        tool = new FindUnreachableCodeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> unreachable(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("unreachable");
    }

    // ========== Default roots: mains + tests ==========

    @Test
    @DisplayName("reports exactly the dead members, sorted by file then line")
    void defaultRoots_exactInventory() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        List<Map<String, Object>> entries = unreachable(r);
        assertEquals(5, entries.size(), () -> "entries: " + entries);

        assertEntry(entries.get(0), "method", "com.reach.EnglishGreeter#unusedPublicHelper()",
            "public", "src/main/java/com/reach/EnglishGreeter.java", 17);
        assertEntry(entries.get(1), "type", "com.reach.Orphan",
            "public", "src/main/java/com/reach/Orphan.java", 7);
        assertEntry(entries.get(2), "field", "com.reach.Orphan#DEAD_CONSTANT",
            "public", "src/main/java/com/reach/Orphan.java", 9);
        assertEntry(entries.get(3), "method", "com.reach.Orphan#deadMethod()",
            "public", "src/main/java/com/reach/Orphan.java", 11);
        assertEntry(entries.get(4), "method", "com.reach.Orphan#deadChain()",
            "package", "src/main/java/com/reach/Orphan.java", 15);

        assertEquals(5, getData(r).get("unreachableCount"));
        assertEquals(5, r.getMeta().getTotalCount());
        assertEquals(5, r.getMeta().getReturnedCount());
    }

    private void assertEntry(Map<String, Object> entry, String kind, String key,
                             String visibility, String filePath, int line) {
        assertEquals(kind, entry.get("kind"), () -> "kind of " + entry);
        assertEquals(key, entry.get("key"), () -> "key of " + entry);
        assertEquals(visibility, entry.get("visibility"), () -> "visibility of " + entry);
        assertEquals(filePath, entry.get("filePath"), () -> "filePath of " + entry);
        assertEquals(line, entry.get("line"), () -> "line of " + entry);
    }

    @Test
    @DisplayName("reports the roots used: main methods listed, test methods counted")
    void defaultRoots_rootsReported() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> roots = (Map<String, Object>) getData(r).get("roots");
        assertEquals(List.of("com.reach.Main#main(String[])"), roots.get("mainMethods"));
        assertEquals(3, roots.get("testMethodCount"));
    }

    @Test
    @DisplayName("types with any reachable member are not reported even when never instantiated")
    void reachableMemberSuppressesType() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        Set<String> keys = keys(r);
        assertFalse(keys.contains("com.reach.Base"),
            "Base is never instantiated but hook() is reachable");
        assertFalse(keys.contains("com.reach.Greeter"),
            "Greeter is never instantiated but greet(String) is reachable");
    }

    // ========== Test-roots toggle ==========

    @Test
    @DisplayName("includeTestRoots=false makes the test-only chain and test classes dead")
    void withoutTestRoots_exactKeySet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("includeTestRoots", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        assertEquals(Set.of(
            "com.reach.EnglishGreeter#unusedPublicHelper()",
            "com.reach.Orphan",
            "com.reach.Orphan#DEAD_CONSTANT",
            "com.reach.Orphan#deadMethod()",
            "com.reach.Orphan#deadChain()",
            "com.reach.TestedOnly",
            "com.reach.TestedOnly#onlyFromTest(int)",
            "com.reach.TestedOnlyTest",
            "com.reach.TestedOnlyTest#doublesInput()",
            "com.reach.TestedOnlyTest#viaHelper()",
            "com.reach.TestedOnlyTest#helper()",
            "com.reach.GreeterDispatchTest",
            "com.reach.GreeterDispatchTest#greetsThroughInterface()"),
            keys(r));
        assertEquals(13, getData(r).get("unreachableCount"));
    }

    private Set<String> keys(ToolResponse r) {
        return unreachable(r).stream().map(e -> (String) e.get("key")).collect(Collectors.toSet());
    }

    // ========== maxResults boundaries ==========

    @Test
    @DisplayName("maxResults truncates in sorted order and flags truncation")
    void maxResults_truncates() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> entries = unreachable(r);
        assertEquals(2, entries.size());
        assertEquals("com.reach.EnglishGreeter#unusedPublicHelper()", entries.get(0).get("key"));
        assertEquals("com.reach.Orphan", entries.get(1).get("key"));
        assertEquals(5, r.getMeta().getTotalCount());
        assertEquals(2, r.getMeta().getReturnedCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    @Test
    @DisplayName("maxResults=0 returns no entries but the true total")
    void maxResults_zero() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 0);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(0, unreachable(r).size());
        assertEquals(5, r.getMeta().getTotalCount());
    }

    @Test
    @DisplayName("maxResults at total and beyond returns everything untruncated")
    void maxResults_totalAndBeyond() {
        for (int max : new int[] {5, 6, Integer.MAX_VALUE}) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("maxResults", max);

            ToolResponse r = tool.execute(args);
            assertTrue(r.isSuccess(), "maxResults=" + max);
            assertEquals(5, unreachable(r).size(), "maxResults=" + max);
            assertNotEquals(Boolean.TRUE, r.getMeta().getTruncated(), "maxResults=" + max);
        }
    }

    @Test
    @DisplayName("negative maxResults is rejected as INVALID_PARAMETER")
    void maxResults_negativeRejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    // ========== Error paths ==========

    @Test
    @DisplayName("without a loaded project returns PROJECT_NOT_LOADED")
    void projectNotLoaded() {
        FindUnreachableCodeTool unloaded = new FindUnreachableCodeTool(() -> null);
        ToolResponse r = unloaded.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", r.getError().getCode());
    }
}
