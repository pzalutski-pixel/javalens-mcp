package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetTypeUsageSummaryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetTypeUsageSummaryTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeUsageSummaryTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("Calculator usage summary: exact counts per category match the sibling find_* tools")
    @SuppressWarnings("unchecked")
    void calculator_summaryCountsMatchSiblingTools() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("com.example.Calculator", data.get("typeName"));
        assertEquals("class", data.get("kind"));

        Map<String, Object> usages = (Map<String, Object>) data.get("usages");
        assertNotNull(usages);

        // Each subcategory must be a map with `count` and `locations` keys.
        Map<String, Object> instantiations = (Map<String, Object>) usages.get("instantiations");
        Map<String, Object> casts = (Map<String, Object>) usages.get("casts");
        Map<String, Object> instanceofChecks = (Map<String, Object>) usages.get("instanceofChecks");
        Map<String, Object> typeArguments = (Map<String, Object>) usages.get("typeArguments");
        assertNotNull(instantiations.get("locations"));
        assertNotNull(casts.get("locations"));
        assertNotNull(instanceofChecks.get("locations"));
        assertNotNull(typeArguments.get("locations"));

        int instantiationCount = ((Number) instantiations.get("count")).intValue();
        int castCount = ((Number) casts.get("count")).intValue();
        int instanceofCount = ((Number) instanceofChecks.get("count")).intValue();
        int typeArgCount = ((Number) typeArguments.get("count")).intValue();

        // Cross-tool consistency with the sibling find_* tools, all exact.
        assertEquals(5, instantiationCount,
            "5 `new Calculator()` (SearchPatterns x3, UserService, SampleTest); got: " + instantiations);
        assertEquals(1, castCount,
            "find_casts asserts exactly 1 cast for Calculator; aggregate must match; got: " + casts);
        assertEquals(2, instanceofCount,
            "find_instanceof_checks asserts exactly 2 checks for Calculator; aggregate must match; got: "
                + instanceofChecks);
        assertEquals(2, typeArgCount,
            "2 `List<Calculator>` type arguments (SearchPatterns field + local); got: " + typeArguments);

        assertEquals(10, ((Number) data.get("totalUsages")).intValue(),
            "totalUsages = 5 + 1 + 2 + 2; got data: " + data);
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        assertEquals("com.example.Calculator", getData(tool.execute(args)).get("typeName"));
    }

    @Test @DisplayName("maxPerCategory=2 lists at most two locations a category, in search order, and counts every usage")
    void respectsMaxPerCategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 2);
        // Only instantiations (5) has more usages than the cap: 7 of the 10 locations are listed.
        Map<String, Object> usages = new java.util.LinkedHashMap<>();
        usages.put("instantiations", category(5, List.of(NEW_CALCULATOR_57, NEW_CALCULATOR_211)));
        usages.put("casts", category(1, List.of(CAST_79)));
        usages.put("instanceofChecks", category(2, List.of(INSTANCEOF_78, INSTANCEOF_99)));
        usages.put("typeArguments", category(2, List.of(TYPE_ARGUMENT_26, TYPE_ARGUMENT_210)));
        assertPayload(args, summaryPayload("com.example.Calculator", "class", usages, 10, 7));
    }

    @Test @DisplayName("missing typeName -> exact INVALID_PARAMETER")
    void requiresTypeName() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'typeName': Required parameter missing", r.getError().getMessage());
    }

    @Test @DisplayName("unknown type -> exact SYMBOL_NOT_FOUND")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        ToolResponse r = tool.execute(unknown);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND, r.getError().getCode());
        assertEquals("Symbol not found: Type not found: com.nonexistent.Type", r.getError().getMessage());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Response carries typeName, kind, usages, totalUsages")
    void responseShape_carriesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : java.util.List.of("typeName", "kind", "usages", "totalUsages")) {
            assertNotNull(data.get(key), key + " missing on response: " + data.keySet());
        }
    }

    @Test
    @DisplayName("usages has all 4 subcategories: instantiations, casts, instanceofChecks, typeArguments")
    @SuppressWarnings("unchecked")
    void usages_hasAllCategories() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        for (String key : java.util.List.of("instantiations", "casts", "instanceofChecks", "typeArguments")) {
            assertNotNull(usages.get(key), key + " missing on usages: " + usages);
        }
    }

    @Test
    @DisplayName("Each subcategory has count + locations; count equals locations.size() up to maxPerCategory")
    @SuppressWarnings("unchecked")
    void subcategory_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 50);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        for (String key : java.util.List.of("instantiations", "casts", "instanceofChecks", "typeArguments")) {
            Map<String, Object> cat = (Map<String, Object>) usages.get(key);
            assertNotNull(cat.get("count"), "count missing on " + key + ": " + cat);
            assertNotNull(cat.get("locations"), "locations missing on " + key + ": " + cat);
            int count = ((Number) cat.get("count")).intValue();
            java.util.List<?> locs = (java.util.List<?>) cat.get("locations");
            // The tool stores the clipped match-list size as count, so count IS locations.size().
            assertEquals(count, locs.size(),
                key + ": count must equal locations.size(); got count=" + count
                    + " locations=" + locs.size());
        }
    }

    @Test
    @DisplayName("Annotation type (Marker): annotationUsages subcategory is added; kind='annotation'")
    @SuppressWarnings("unchecked")
    void annotationType_addsAnnotationUsagesSubcategory() {
        // Source: `if (type.isAnnotation())` adds an `annotationUsages` subcategory
        // that's absent for non-annotation types. Pin both the additive branch
        // (Marker → key present, count >= 1 since AnnotationUsages.java applies it)
        // and that kind is reported as the lowercase 'annotation'.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxPerCategory", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("annotation", data.get("kind"),
            "Marker is an annotation type — kind must be lowercase 'annotation'; got: " + data);

        Map<String, Object> usages = (Map<String, Object>) data.get("usages");
        Map<String, Object> annotationUsages = (Map<String, Object>) usages.get("annotationUsages");
        assertNotNull(annotationUsages,
            "annotationUsages subcategory MUST be added for annotation types; got: " + usages);
        int count = ((Number) annotationUsages.get("count")).intValue();
        assertEquals(7, count,
            "@Marker is applied in exactly 7 positions across AnnotationUsages.java; got: " + annotationUsages);
        assertNotNull(annotationUsages.get("locations"),
            "annotationUsages locations list missing; got: " + annotationUsages);
    }

    @Test
    @DisplayName("Non-annotation type (Calculator): annotationUsages subcategory is OMITTED")
    @SuppressWarnings("unchecked")
    void nonAnnotationType_omitsAnnotationUsagesSubcategory() {
        // Mirror of the previous test: the `if (type.isAnnotation())` guard means
        // a class/interface/enum/record must NOT carry an annotationUsages key.
        // Pins the absence-of-key contract.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        assertFalse(usages.containsKey("annotationUsages"),
            "Non-annotation types must NOT carry annotationUsages key; got: " + usages);
    }

    @Test
    @DisplayName("Animal: typeArgument count=0, cast count=0, instanceof count=0; instantiation count=1 (FieldHolder)")
    @SuppressWarnings("unchecked")
    void animal_isolation_counts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");
        args.put("maxPerCategory", 50);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        assertEquals(0, ((Number) ((Map<String, Object>) usages.get("casts")).get("count")).intValue(),
            "Animal never cast — casts.count must be 0");
        assertEquals(0, ((Number) ((Map<String, Object>) usages.get("instanceofChecks")).get("count")).intValue(),
            "Animal never instanceof-checked — instanceofChecks.count must be 0");
        assertEquals(0, ((Number) ((Map<String, Object>) usages.get("typeArguments")).get("count")).intValue(),
            "Animal not used as generic type argument — typeArguments.count must be 0");
        assertEquals(1, ((Number) ((Map<String, Object>) usages.get("instantiations")).get("count")).intValue(),
            "Animal instantiated exactly once in FieldHolder; got: "
                + ((Map<String, Object>) usages.get("instantiations")));
    }

    @Test
    @DisplayName("Annotation Marker: TYPE_USE positions (parameter, local var, type argument) are included")
    @SuppressWarnings("unchecked")
    void annotation_typeUsePositions_included() {
        // AnnotationUsages.java applies @Marker in seven positions:
        //   line 7 (class), 10 (field), 13 (constructor), 17 (method),
        //   31 (TYPE_USE on parameter `@Marker int p`),
        //   32 (TYPE_USE on local var `@Marker int local`),
        //   36 (TYPE_USE in type argument `List<@Marker String>`).
        // SearchEngine's annotation-reference indexing must catch the
        // TYPE_USE positions, not just declaration-modifier positions.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxPerCategory", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> usages = (Map<String, Object>) getData(r).get("usages");
        Map<String, Object> annotationUsages = (Map<String, Object>) usages.get("annotationUsages");
        int count = ((Number) annotationUsages.get("count")).intValue();
        assertEquals(7, count,
            "@Marker applied in exactly 7 positions including 3 TYPE_USE; got: " + count);

        // Exact 0-based @Marker positions, matching find_annotation_usages.
        List<Map<String, Object>> locations = (List<Map<String, Object>>) annotationUsages.get("locations");
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (Map<String, Object> loc : locations) {
            Object line = loc.get("line");
            if (line instanceof Number n) lines.add(n.intValue());
        }
        assertEquals(java.util.Set.of(6, 9, 12, 16, 30, 31, 35), lines,
            "@Marker 0-based positions: class 6, field 9, ctor 12, method 16, "
                + "TYPE_USE param 30, local 31, type-arg 35; got: " + lines);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Animal usage is casts 0, instanceof 0, typeArgs 0, instantiations 1")
    void envelope_animal_exactCounts() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Animal");
        args.put("maxPerCategory", 50);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_type_usage_summary", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_type_usage_summary failed through the envelope: " + payload);
        JsonNode usages = payload.get("data").get("usages");
        assertEquals(0, usages.get("casts").get("count").asInt(), "Animal never cast");
        assertEquals(0, usages.get("instanceofChecks").get("count").asInt(), "Animal never instanceof-checked");
        assertEquals(0, usages.get("typeArguments").get("count").asInt(), "Animal not a generic type arg");
        assertEquals(1, usages.get("instantiations").get("count").asInt(),
            "Animal instantiated exactly once (FieldHolder) — count must survive the envelope");
    }

    // ========== maxPerCategory: exact counts, cut locations, documented range ==========

    @Test
    @DisplayName("maxPerCategory=1 on Calculator keeps the first location of each category and counts every usage exactly")
    void maxPerCategoryOne_exactCountsFirstLocations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 1);
        Map<String, Object> usages = new java.util.LinkedHashMap<>();
        usages.put("instantiations", category(5, List.of(NEW_CALCULATOR_57)));
        usages.put("casts", category(1, List.of(CAST_79)));
        usages.put("instanceofChecks", category(2, List.of(INSTANCEOF_78)));
        usages.put("typeArguments", category(2, List.of(TYPE_ARGUMENT_26)));
        assertPayload(args, summaryPayload("com.example.Calculator", "class", usages, 10, 4));
    }

    @Test
    @DisplayName("maxPerCategory outside 1 to 50 is INVALID_PARAMETER naming the range, never clamped")
    void maxPerCategory_outsideRangeIsRefused() {
        ObjectNode zero = objectMapper.createObjectNode();
        zero.put("typeName", "com.example.Calculator");
        zero.put("maxPerCategory", 0);
        ObjectNode fiftyOne = objectMapper.createObjectNode();
        fiftyOne.put("typeName", "com.example.Calculator");
        fiftyOne.put("maxPerCategory", 51);
        assertAll(
            () -> assertPayload(zero, refusal("Invalid parameter 'maxPerCategory': Must be between 1 and 50; got: 0")),
            () -> assertPayload(fiftyOne,
                refusal("Invalid parameter 'maxPerCategory': Must be between 1 and 50; got: 51")));
    }

    // ========== exact payload helpers ==========

    private static final String SEARCH_PATTERNS = "src/main/java/com/example/SearchPatterns.java";

    /*
     * Each location is the type reference "Calculator" (length 10). Raw offsets depend on line
     * endings, so they are removed; line and column pin each location.
     */
    private static final Map<String, Object> NEW_CALCULATOR_57 =
        location(SEARCH_PATTERNS, 57, 30, "Calculator calc = new Calculator();");
    private static final Map<String, Object> CAST_79 =
        location(SEARCH_PATTERNS, 79, 31, "Calculator calc = (Calculator) obj;");
    private static final Map<String, Object> INSTANCEOF_78 =
        location(SEARCH_PATTERNS, 78, 27, "if (obj instanceof Calculator) {");
    private static final Map<String, Object> TYPE_ARGUMENT_26 =
        location(SEARCH_PATTERNS, 26, 17, "private List<Calculator> calculatorList;");
    private static final Map<String, Object> NEW_CALCULATOR_211 =
        location(SEARCH_PATTERNS, 211, 22, "calcs.add(new Calculator());");
    private static final Map<String, Object> INSTANCEOF_99 =
        location(SEARCH_PATTERNS, 99, 27, "if (obj instanceof Calculator) {");
    private static final Map<String, Object> TYPE_ARGUMENT_210 =
        location(SEARCH_PATTERNS, 210, 13, "List<Calculator> calcs = new ArrayList<>();");

    private static Map<String, Object> location(String file, int line, int column, String context) {
        Map<String, Object> location = new java.util.LinkedHashMap<>();
        location.put("filePath", file);
        location.put("length", 10);
        location.put("line", line);
        location.put("column", column);
        location.put("context", context);
        return location;
    }

    private static Map<String, Object> category(int count, List<Map<String, Object>> locations) {
        Map<String, Object> category = new java.util.LinkedHashMap<>();
        category.put("count", count);
        category.put("locations", locations);
        return category;
    }

    /** The success payload for {@code usages}: {@code total} usages, {@code returned} locations listed. */
    private static Map<String, Object> summaryPayload(String typeName, String kind, Map<String, Object> usages,
                                                      int total, int returned) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("typeName", typeName);
        data.put("kind", kind);
        data.put("usages", usages);
        data.put("totalUsages", total);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", returned);
        meta.put("truncated", returned < total);
        meta.put("suggestedNextTools", List.of(
            "analyze_type for complete type analysis",
            "get_type_hierarchy to understand inheritance",
            "find_references for all reference types"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each without its locations' raw offsets.
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
        JsonNode wire = withoutOffsets(envelope.assertEnvelopeFidelity("get_type_usage_summary", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** {@code payload} without the offset of each location of every usage category. */
    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("usages").forEach(category ->
            category.path("locations").forEach(location -> ((ObjectNode) location).remove("offset")));
        return copy;
    }
}
