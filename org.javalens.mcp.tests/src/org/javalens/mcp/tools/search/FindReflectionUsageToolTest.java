package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReflectionUsageTool(() -> service);
        envelope = new EnvelopeHarness(service);
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
        assertEquals(8, ((Number) data.get("totalCalls")).intValue(),
            "exactly eight reflection calls in DiAndReflectionPatterns.java (Method.invoke twice)");
    }

    @Test
    @DisplayName("every reflection call carries a non-null reflectionMethod label")
    void includesReflectionMethodLabel() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");
        assertEquals(8, calls.size());
        for (Map<String, Object> c : calls) {
            assertNotNull(c.get("reflectionMethod"), "every call must carry a reflectionMethod label; got: " + c);
        }
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("summary keys equal the distinct call methods and its counts sum to totalCalls")
    void groupsResultsInSummary() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("reflectionCalls");
        java.util.Set<String> callMethods = calls.stream()
            .map(c -> (String) c.get("reflectionMethod")).collect(java.util.stream.Collectors.toSet());
        assertEquals(callMethods, summary.keySet(), "summary keys == distinct call methods; got: " + summary);
        int sum = summary.values().stream().mapToInt(v -> ((Number) v).intValue()).sum();
        assertEquals(calls.size(), sum, "summary counts must sum to the total call count");
    }

    @Test
    @DisplayName("maxResults=1 lists one call of each reflection method, counts all eight calls and reports the cut")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        // maxResults caps the calls listed per reflection method: Method.invoke lists its call
        // on line 32 of its two, and its summary count stays 2.
        assertPayload(args, reflection(List.of(FOR_NAME_26, GET_METHOD_31, GET_DECLARED_FIELD_36,
            GET_DECLARED_CONSTRUCTOR_27, INVOKE_32, FIELD_GET_38, NEW_INSTANCE_27)));
    }

    @Test
    @DisplayName("negative maxResults is rejected with INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", -1);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
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

        // DiAndReflectionPatterns' reflection call sites map to exactly these seven detected
        // APIs (setAccessible is not in the detection table). Pin the exact set from the
        // per-call view (the summary test pins the same set from the summary view).
        assertEquals(java.util.Set.of(
            "Class.forName", "Class.getDeclaredConstructor", "Constructor.newInstance",
            "Class.getMethod", "Method.invoke", "Class.getDeclaredField", "Field.get"),
            reflectionMethods,
            "exact set of detected reflection APIs; got: " + reflectionMethods);
    }

    @Test
    @DisplayName("Field.get isolation: the real field.get() is detected, the Supplier.get() decoys are not")
    void fieldGet_excludesSupplierGetDecoys() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldGets = ((List<Map<String, Object>>) getData(r).get("reflectionCalls"))
            .stream()
            .filter(c -> "Field.get".equals(c.get("reflectionMethod")))
            .toList();

        // Ground truth from source: the only reflective field read is
        // DiAndReflectionPatterns.getFieldByReflection `field.get(target)`.
        assertTrue(fieldGets.stream().anyMatch(c ->
                String.valueOf(c.get("filePath")).replace('\\', '/').endsWith("DiAndReflectionPatterns.java")),
            "the real field.get(target) must be detected; got: " + fieldGets);

        // The two `supplier.get()` calls in AnonymousClassExamples (Supplier.get,
        // not Field.get) must NOT be miscounted - a regression to simple-name
        // matching would put a Field.get entry there.
        assertTrue(fieldGets.stream().noneMatch(c ->
                String.valueOf(c.get("filePath")).replace('\\', '/').endsWith("AnonymousClassExamples.java")),
            "Supplier.get() must not be classified as Field.get (false positive); got: " + fieldGets);
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
    @DisplayName("Default maxResults: no clip — totalCalls == reflectionCalls.size() == returnedCount == 8, truncated false")
    void totalCallsEqualsListSize() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCalls")).intValue();
        int listSize = callsOf(r).size();
        // At the default cap (100) nothing is clipped, so the pre-clip total, the
        // post-clip list, and meta.returnedCount all equal the eight call sites.
        assertEquals(8, total, "pre-clip totalCalls");
        assertEquals(8, listSize, "post-clip reflectionCalls.size()");
        assertEquals(Integer.valueOf(8), r.getMeta().getReturnedCount(),
            "meta.returnedCount == reflectionCalls.size()");
        assertEquals(Integer.valueOf(8), r.getMeta().getTotalCount(),
            "meta.totalCount == pre-clip totalCalls");
        assertFalse(Boolean.TRUE.equals(r.getMeta().getTruncated()),
            "nothing is clipped at the default cap, so truncated must be false");
    }

    @Test
    @DisplayName("maxResults=1 truncates: Method.invoke's two sites clip to one, so totalCalls (8, pre-clip) exceeds the clipped list (7) and meta.truncated is true")
    void maxResultsOne_truncatesMethodInvoke() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Pre-clip total counts all eight call sites (Method.invoke twice). The per-
        // reflection-method cap of 1 keeps one call per distinct label = seven.
        assertEquals(8, ((Number) data.get("totalCalls")).intValue(),
            "totalCalls is the pre-clip total across all labels");
        assertEquals(7, callsOf(r).size(),
            "the clipped list keeps one call per distinct label (7 APIs)");
        assertEquals(Integer.valueOf(8), r.getMeta().getTotalCount());
        assertEquals(Integer.valueOf(7), r.getMeta().getReturnedCount());
        assertTrue(Boolean.TRUE.equals(r.getMeta().getTruncated()),
            "dropping the second Method.invoke must flag truncation");
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
    @DisplayName("All four documented detection categories (Class.* lookup, Class.constructor lookup, Method/Field/Constructor invocation, Class.forName/newInstance) yield at least one match against the fixture")
    void allDocumentedCategoriesPresent() {
        // The tool's getDescription enumerates four categories. The fixture
        // DiAndReflectionPatterns exercises at least one API per category.
        // This guard pins that no future change to REFLECTION_METHODS drops a
        // documented category by accident.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) getData(r).get("summary");
        assertNotNull(summary);

        // Category 1: Class.forName() / Class.newInstance()
        assertTrue(summary.containsKey("Class.forName"),
            "Documented category 'Class.forName/newInstance' must yield matches; got: " + summary.keySet());

        // Category 2: Class.getMethod / getDeclaredMethod / getField / getDeclaredField
        assertTrue(summary.containsKey("Class.getMethod") || summary.containsKey("Class.getDeclaredField"),
            "Documented category 'Class.getMethod/getField family' must yield matches; got: " + summary.keySet());

        // Category 3: Class.getConstructor / getDeclaredConstructor
        assertTrue(summary.containsKey("Class.getDeclaredConstructor") || summary.containsKey("Class.getConstructor"),
            "Documented category 'Class.getConstructor family' must yield matches; got: " + summary.keySet());

        // Category 4: Method.invoke / Field.get/set / Constructor.newInstance
        assertTrue(
            summary.containsKey("Method.invoke")
                || summary.containsKey("Field.get")
                || summary.containsKey("Field.set")
                || summary.containsKey("Constructor.newInstance"),
            "Documented category 'reflective invocation' must yield matches; got: " + summary.keySet());
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

    // ========== Exact magnitude + scope isolation ==========

    @Test
    @DisplayName("Exactly eight reflection calls, all in DiAndReflectionPatterns.java, with the exact per-API summary (Method.invoke twice)")
    void reflectionUsage_exactlyEightCallsAllInFixture() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // DiAndReflectionPatterns is the only file with reflection. Its call sites:
        // Class.forName, getDeclaredConstructor(), Constructor.newInstance(), getMethod,
        // Method.invoke (twice: invokeByReflection + secondInvoke), getDeclaredField,
        // Field.get — eight (setAccessible is not in the detection table). No matches
        // may come from the classpath or workspace.
        assertEquals(8, ((Number) data.get("totalCalls")).intValue(),
            "exactly eight reflection call sites; got: " + callsOf(r));
        assertEquals(8, callsOf(r).size());

        for (Map<String, Object> c : callsOf(r)) {
            String fp = String.valueOf(c.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith("DiAndReflectionPatterns.java"),
                "every reflection call is in the fixture source; got: " + fp);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(java.util.Set.of(
            "Class.forName", "Class.getDeclaredConstructor", "Constructor.newInstance",
            "Class.getMethod", "Method.invoke", "Class.getDeclaredField", "Field.get"),
            summary.keySet(),
            "exact set of detected reflection APIs; got: " + summary);
        // Method.invoke has two call sites; every other API exactly one.
        Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put("Class.forName", 1);
        expected.put("Class.getDeclaredConstructor", 1);
        expected.put("Constructor.newInstance", 1);
        expected.put("Class.getMethod", 1);
        expected.put("Method.invoke", 2);
        expected.put("Class.getDeclaredField", 1);
        expected.put("Field.get", 1);
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertEquals(e.getValue().intValue(), ((Number) summary.get(e.getKey())).intValue(),
                "exact per-API count for " + e.getKey() + "; got: " + summary);
        }
    }

    @Test
    @DisplayName("No scope leak: every entry is a project .java file, never a target/work workspace path")
    void noScopeLeak_allEntriesAreProjectSource() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : callsOf(r)) {
            String fp = String.valueOf(c.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith(".java"), "entry must be a .java source file; got: " + fp);
            assertFalse(fp.contains("target/work") || fp.contains("javalens-WS"),
                "reflection search must not leak Eclipse workspace-metadata matches; got: " + fp);
        }
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: exactly eight reflection calls, none leaked from target/work")
    void envelope_exactlyEight_noLeak() {
        JsonNode payload = envelope.assertEnvelopeFidelity("find_reflection_usage", envelope.args());
        assertTrue(payload.get("success").asBoolean(),
            () -> "find_reflection_usage failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(8, data.get("totalCalls").asInt(),
            "the eight reflection calls must survive the JSON-RPC envelope");
        for (JsonNode c : data.get("reflectionCalls")) {
            String fp = c.get("filePath").asText().replace('\\', '/');
            assertFalse(fp.contains("target/work") || fp.contains("javalens-WS"),
                "no workspace-metadata leak through the envelope; got: " + fp);
        }
    }

    // ========== exact payload helpers ==========

    private static final String REFLECTION = "src/main/java/com/example/DiAndReflectionPatterns.java";

    /*
     * The eight reflection calls of DiAndReflectionPatterns. Each match runs from the method's
     * name to the closing parenthesis of its call. Raw offsets depend on line endings, so they
     * are removed; line and column pin each call.
     */
    private static final Map<String, Object> FOR_NAME_26 = call("Class.forName", 26, 31, "forName(className)",
        "Class<?> clazz = Class.forName(className);");
    private static final Map<String, Object> GET_DECLARED_CONSTRUCTOR_27 = call("Class.getDeclaredConstructor", 27,
        21, "getDeclaredConstructor()", "return clazz.getDeclaredConstructor().newInstance();");
    private static final Map<String, Object> NEW_INSTANCE_27 = call("Constructor.newInstance", 27, 46,
        "newInstance()", "return clazz.getDeclaredConstructor().newInstance();");
    private static final Map<String, Object> GET_METHOD_31 = call("Class.getMethod", 31, 42,
        "getMethod(methodName)", "Method method = target.getClass().getMethod(methodName);");
    private static final Map<String, Object> INVOKE_32 = call("Method.invoke", 32, 22, "invoke(target)",
        "return method.invoke(target);");
    private static final Map<String, Object> GET_DECLARED_FIELD_36 = call("Class.getDeclaredField", 36, 40,
        "getDeclaredField(fieldName)", "Field field = target.getClass().getDeclaredField(fieldName);");
    private static final Map<String, Object> FIELD_GET_38 = call("Field.get", 38, 21, "get(target)",
        "return field.get(target);");
    private static final Map<String, Object> INVOKE_120 = call("Method.invoke", 120, 22, "invoke(target)",
        "return method.invoke(target);");

    private static Map<String, Object> call(String reflectionMethod, int line, int column, String matched,
                                            String context) {
        Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("filePath", REFLECTION);
        call.put("length", matched.length());
        call.put("line", line);
        call.put("column", column);
        call.put("context", context);
        call.put("reflectionMethod", reflectionMethod);
        return call;
    }

    /**
     * The success payload listing {@code calls}, in the tool's reflection-method order: all eight
     * calls are counted, Method.invoke's two among them.
     */
    private static Map<String, Object> reflection(List<Map<String, Object>> calls) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("Class.forName", 1);
        summary.put("Class.getMethod", 1);
        summary.put("Class.getDeclaredField", 1);
        summary.put("Class.getDeclaredConstructor", 1);
        summary.put("Method.invoke", 2);
        summary.put("Field.get", 1);
        summary.put("Constructor.newInstance", 1);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("totalCalls", 8);
        data.put("summary", summary);
        data.put("reflectionCalls", calls);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", 8);
        meta.put("returnedCount", calls.size());
        meta.put("truncated", calls.size() < 8);
        meta.put("suggestedNextTools", List.of(
            "analyze_change_impact to assess risk of renaming reflected types",
            "get_symbol_info at a reflection call site for context"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each without its calls' raw offsets.
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
        JsonNode wire = withoutOffsets(envelope.assertEnvelopeFidelity("find_reflection_usage", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("reflectionCalls").forEach(call -> ((ObjectNode) call).remove("offset"));
        return copy;
    }
}
