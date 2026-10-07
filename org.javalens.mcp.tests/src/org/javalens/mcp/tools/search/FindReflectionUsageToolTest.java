package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * find_reflection_usage on simple-maven, whose only reflection calls are DiAndReflectionPatterns'
 * eight: Class.forName (line 26), Class.getDeclaredConstructor and Constructor.newInstance (27),
 * Class.getMethod (31), Method.invoke (32 and 120), Class.getDeclaredField (36) and Field.get (38).
 * The calls come grouped by reflection method in the tool's table order, each method's calls in
 * source order. AnonymousClassExamples' two Supplier.get() calls are no Field.get calls. Every
 * expectation is the whole payload, through execute() and through the JSON-RPC envelope; each
 * entry is a project source file, never a workspace-metadata copy.
 */
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

    // ========== Detection Tests ==========

    @Test
    @DisplayName("should find reflection usage in project: exactly the eight calls of DiAndReflectionPatterns")
    void findsReflectionUsage() {
        assertPayload(objectMapper.createObjectNode(), reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("every reflection call carries its reflectionMethod label")
    void includesReflectionMethodLabel() {
        assertPayload(objectMapper.createObjectNode(), reflection(ALL_CALLS));
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("summary keys equal the distinct call methods and its counts sum to totalCalls")
    void groupsResultsInSummary() {
        assertPayload(objectMapper.createObjectNode(), reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("maxResults=1 lists one call of each reflection method, counts all eight calls and reports the cut")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        // maxResults caps the calls listed per reflection method: Method.invoke lists its call
        // on line 32 of its two, and its summary count stays 2.
        assertPayload(args, reflection(FIRST_CALLS));
    }

    @Test
    @DisplayName("negative maxResults is rejected with INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", -1);
        assertPayload(args, Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'maxResults': Must be >= 0; got: -1")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("reflection calls include Class.forName, getMethod, Method.invoke, getDeclaredField, Field.get from DiAndReflectionPatterns")
    void findsSpecificReflectionApis() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Field.get isolation: the real field.get() is detected, the Supplier.get() decoys are not")
    void fieldGet_excludesSupplierGetDecoys() {
        // The only Field.get call is DiAndReflectionPatterns.getFieldByReflection's field.get(target).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Reflection call entries carry filePath, length, line, column, context and reflectionMethod")
    void callEntries_carryLabelAndProjectEntriesHaveLineColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Summary maps labels to counts equal to the number of reflectionCalls entries with that label")
    void summaryCountsMatchCallsList() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Default maxResults: no cut - totalCalls == reflectionCalls.size() == returnedCount == 8, truncated false")
    void totalCallsEqualsListSize() {
        assertPayload(objectMapper.createObjectNode(), reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("maxResults=1 truncates: Method.invoke lists one of its two calls, so totalCalls (8) exceeds the calls listed (7) and meta.truncated is true")
    void maxResultsOne_truncatesMethodInvoke() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        assertPayload(args, reflection(FIRST_CALLS));
    }

    @Test
    @DisplayName("Labels of detected APIs are the simple-name dot form (e.g., Class.forName, not java.lang.Class.forName)")
    void labelFormatIsSimpleDotName() {
        assertPayload(objectMapper.createObjectNode(), reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("All four documented detection categories (Class.* lookup, Class.constructor lookup, Method/Field/Constructor invocation, Class.forName/newInstance) are matched in the fixture")
    void allDocumentedCategoriesPresent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Detected APIs are exactly the fixture's: Class.forName, Class.getMethod, Method.invoke, Class.getDeclaredField, Class.getDeclaredConstructor, Field.get, Constructor.newInstance")
    void detectedCategoriesCoverFixture() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Exactly eight reflection calls, all in DiAndReflectionPatterns.java, with the exact per-API summary (Method.invoke twice)")
    void reflectionUsage_exactlyEightCallsAllInFixture() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("No scope leak: every entry is a project .java file, never a target/work workspace path")
    void noScopeLeak_allEntriesAreProjectSource() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);
        assertPayload(args, reflection(ALL_CALLS));
    }

    @Test
    @DisplayName("Through the real MCP envelope: exactly eight reflection calls, none leaked from target/work")
    void envelope_exactlyEight_noLeak() {
        assertPayload(envelope.args(), reflection(ALL_CALLS));
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

    private static final List<Map<String, Object>> ALL_CALLS = List.of(FOR_NAME_26, GET_METHOD_31,
        GET_DECLARED_FIELD_36, GET_DECLARED_CONSTRUCTOR_27, INVOKE_32, INVOKE_120, FIELD_GET_38, NEW_INSTANCE_27);
    private static final List<Map<String, Object>> FIRST_CALLS = List.of(FOR_NAME_26, GET_METHOD_31,
        GET_DECLARED_FIELD_36, GET_DECLARED_CONSTRUCTOR_27, INVOKE_32, FIELD_GET_38, NEW_INSTANCE_27);

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
