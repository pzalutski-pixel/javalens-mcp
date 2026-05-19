package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindReflectionUsageToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReflectionUsageTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReflectionUsageTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Detection Tests ==========

    @Test
    @DisplayName("should find reflection usage in project")
    void findsReflectionUsage() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalCalls = (int) data.get("totalCalls");
        assertTrue(totalCalls > 0, "Should find reflection calls in DiAndReflectionPatterns.java");
    }

    @Test
    @DisplayName("should include reflection method label in results")
    void includesReflectionMethodLabel() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");
        if (!calls.isEmpty()) {
            Map<String, Object> firstCall = calls.get(0);
            assertNotNull(firstCall.get("reflectionMethod"), "Should include reflection method label");
            // filePath and line may be absent if match is in a binary JAR
        }
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should group results by reflection method type in summary")
    void groupsResultsInSummary() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("summary"), "Should include summary");
        assertNotNull(data.get("reflectionCalls"), "Should include reflectionCalls list");
    }

    @Test
    @DisplayName("should respect maxResults parameter (per-reflection-method cap, per the tool's description)")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // The tool documents `maxResults` as "Maximum results per reflection method". The
        // summary maps each detected reflection label to its count; every value must obey
        // the cap.
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary);
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            int count = ((Number) entry.getValue()).intValue();
            assertTrue(count <= 1,
                "maxResults=1 caps each reflection label's count to 1; "
                    + entry.getKey() + " has " + count + " entries; full summary: " + summary);
        }
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("reflection calls include Class.forName, getMethod, Method.invoke, getDeclaredField, Field.get from DiAndReflectionPatterns")
    void findsSpecificReflectionApis() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");

        java.util.Set<String> reflectionMethods = calls.stream()
            .map(c -> (String) c.get("reflectionMethod"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());

        // DiAndReflectionPatterns has Class.forName, getDeclaredConstructor().newInstance(),
        // getMethod, Method.invoke, getDeclaredField, Field.get (and setAccessible).
        // Some of these (newInstance, setAccessible) may not be classified as core reflection
        // by the tool; assert the most universally-detected APIs appear.
        assertTrue(reflectionMethods.contains("Class.forName"),
            "Expected Class.forName in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Class.getMethod"),
            "Expected Class.getMethod in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Method.invoke"),
            "Expected Method.invoke in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Class.getDeclaredField"),
            "Expected Class.getDeclaredField in detected reflection methods; got: " + reflectionMethods);
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("reflectionCalls");
    }

    @Test
    @DisplayName("Reflection call entries always carry reflectionMethod label and filePath; project-source entries also carry line/column")
    void callEntries_carryLabelAndProjectEntriesHaveLineColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> calls = callsOf(r);
        assertFalse(calls.isEmpty(),
            "Fixtures contain reflection usage; reflectionCalls list must be non-empty");

        // Every entry must carry a non-blank reflectionMethod label and a filePath.
        for (Map<String, Object> c : calls) {
            String rm = (String) c.get("reflectionMethod");
            assertNotNull(rm, "reflectionMethod missing: " + c);
            assertFalse(rm.isBlank(), "reflectionMethod non-blank; got: " + c);
            String fp = (String) c.get("filePath");
            assertNotNull(fp, "filePath missing: " + c);
            assertFalse(fp.isBlank(), "filePath non-blank; got: " + c);
        }

        // The project-source subset (entries whose filePath ends with .java) must have
        // proper line/column. There must be at least one such entry from
        // DiAndReflectionPatterns.java — that's where the fixture's reflection lives.
        java.util.List<Map<String, Object>> projectEntries = calls.stream()
            .filter(c -> ((String) c.get("filePath")).replace('\\', '/').endsWith(".java"))
            .collect(java.util.stream.Collectors.toList());
        assertFalse(projectEntries.isEmpty(),
            "At least one reflection call must have a resolvable .java filePath");
        boolean hasFixture = false;
        for (Map<String, Object> c : projectEntries) {
            assertNotNull(c.get("line"), "line missing on project entry: " + c);
            assertNotNull(c.get("column"), "column missing on project entry: " + c);
            String fp = ((String) c.get("filePath")).replace('\\', '/');
            if (fp.endsWith("DiAndReflectionPatterns.java")) hasFixture = true;
        }
        assertTrue(hasFixture,
            "Expected at least one entry from DiAndReflectionPatterns.java; got: "
                + projectEntries);
    }

    @Test
    @DisplayName("Summary maps labels to counts equal to the number of reflectionCalls entries with that label")
    void summaryCountsMatchCallsList() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, Number> summary = (Map<String, Number>) data.get("summary");
        assertNotNull(summary);

        // Group reflectionCalls by label, compare to summary.
        java.util.Map<String, Long> grouped = new java.util.HashMap<>();
        for (Map<String, Object> c : callsOf(r)) {
            String label = (String) c.get("reflectionMethod");
            grouped.merge(label, 1L, Long::sum);
        }
        for (Map.Entry<String, Number> e : summary.entrySet()) {
            Long actual = grouped.get(e.getKey());
            assertNotNull(actual, "Label in summary but not in calls list: " + e.getKey());
            assertEquals(e.getValue().longValue(), actual.longValue(),
                "Summary count for " + e.getKey() + " must equal calls-list count");
        }
        assertEquals(summary.size(), grouped.size(),
            "Summary and grouped-calls keys must match: summary=" + summary.keySet() + " grouped=" + grouped.keySet());
    }

    @Test
    @DisplayName("totalCalls is the pre-clip total; reflectionCalls.size() is post-clip and equals meta.returnedCount")
    void totalCallsEqualsListSize() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCalls")).intValue();
        int listSize = callsOf(r).size();
        // totalCalls (== meta.totalCount) reflects the pre-clip total across all reflection
        // method labels — this can exceed the post-clip list size when the per-label cap
        // truncates calls. reflectionCalls.size() == meta.returnedCount.
        Integer returned = r.getMeta().getReturnedCount();
        assertEquals(listSize, returned,
            "reflectionCalls.size() must equal meta.returnedCount; got list=" + listSize
                + " returned=" + returned);
        assertTrue(total >= listSize,
            "totalCalls (pre-clip) must be >= reflectionCalls.size() (post-clip); got total="
                + total + " list=" + listSize);
    }

    @Test
    @DisplayName("Labels of detected APIs are the simple-name dot form (e.g., Class.forName, not java.lang.Class.forName)")
    void labelFormatIsSimpleDotName() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : callsOf(r)) {
            String label = (String) c.get("reflectionMethod");
            assertNotNull(label);
            assertFalse(label.startsWith("java."),
                "Label must use simple-name dot form, not FQN; got: " + label);
            assertTrue(label.contains("."),
                "Label must be <SimpleType>.<methodName>; got: " + label);
        }
    }

    @Test
    @DisplayName("Detected APIs include the expected categories from the fixture: Class.forName, Class.getMethod, Method.invoke, Class.getDeclaredField, Class.getDeclaredConstructor, Field.get")
    void detectedCategoriesCoverFixture() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) getData(r).get("summary");
        assertNotNull(summary);
        // DiAndReflectionPatterns explicitly calls:
        //   Class.forName, clazz.getDeclaredConstructor().newInstance() → Class.getDeclaredConstructor + Constructor.newInstance,
        //   target.getClass().getMethod, method.invoke,
        //   target.getClass().getDeclaredField, field.get.
        // Tool's REFLECTION_METHODS table covers all of these. Expect all six labels in summary.
        for (String expected : List.of(
                "Class.forName",
                "Class.getDeclaredConstructor",
                "Class.getMethod",
                "Method.invoke",
                "Class.getDeclaredField",
                "Field.get")) {
            assertTrue(summary.containsKey(expected),
                "Expected " + expected + " in summary; got: " + summary.keySet());
        }
    }
}
