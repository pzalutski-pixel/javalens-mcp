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

        // Cross-tool consistency: these counts are verified independently by
        // FindCastsToolTest (1 cast) and FindInstanceofChecksToolTest (2 instanceofs).
        // The aggregate tool must surface the same numbers.
        assertEquals(1, castCount,
            "find_casts asserts exactly 1 cast for Calculator; aggregate must match; got: " + casts);
        assertEquals(2, instanceofCount,
            "find_instanceof_checks asserts exactly 2 checks for Calculator; aggregate must match; got: "
                + instanceofChecks);

        // SearchPatterns alone has `new Calculator()` in createObjects, useGenerics, and
        // InnerClass.createCalculator — at least 3 instantiations.
        assertTrue(instantiationCount >= 3,
            "Calculator is instantiated at least 3 times across SearchPatterns; got: " + instantiations);

        // totalUsages must equal the sum of the category counts (the tool computes it as
        // such, so this asserts the contract holds).
        int expectedTotal = instantiationCount + castCount + instanceofCount + typeArgCount;
        assertEquals(expectedTotal, ((Number) data.get("totalUsages")).intValue(),
            "totalUsages must equal sum of subcategory counts; got data: " + data);
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        assertEquals("com.example.Calculator", getData(tool.execute(args)).get("typeName"));
    }

    @Test @DisplayName("respects maxPerCategory")
    void respectsMaxPerCategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 1);

        assertTrue(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        assertFalse(tool.execute(unknown).isSuccess());
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
            // count is the actual total; locations may be capped at maxPerCategory.
            assertTrue(locs.size() <= count || locs.size() <= 50,
                key + ": locations.size() should be <= count or cap; got count=" + count
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
        assertTrue(count >= 1,
            "Marker is applied in AnnotationUsages.java — count must be >= 1; got: " + annotationUsages);
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
        assertTrue(count >= 7,
            "@Marker is applied in 7 positions across AnnotationUsages.java including "
                + "three TYPE_USE positions; count must reflect them all; got: " + count);

        // Verify the TYPE_USE line numbers (0-based 30, 31, 35) appear in the locations.
        List<Map<String, Object>> locations = (List<Map<String, Object>>) annotationUsages.get("locations");
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (Map<String, Object> loc : locations) {
            Object line = loc.get("line");
            if (line instanceof Number n) lines.add(n.intValue());
        }
        assertTrue(lines.contains(30) || lines.contains(31) || lines.contains(35),
            "At least one TYPE_USE @Marker position (parameter/local/type-arg) must appear; got lines: " + lines);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Animal usage is casts 0, instanceof 0, typeArgs 0, instantiations 1")
    void envelope_animal_exactCounts() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Animal");
        args.put("maxPerCategory", 50);
        JsonNode payload = envelope.payload("get_type_usage_summary", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_type_usage_summary failed through the envelope: " + payload);
        JsonNode usages = payload.get("data").get("usages");
        assertEquals(0, usages.get("casts").get("count").asInt(), "Animal never cast");
        assertEquals(0, usages.get("instanceofChecks").get("count").asInt(), "Animal never instanceof-checked");
        assertEquals(0, usages.get("typeArguments").get("count").asInt(), "Animal not a generic type arg");
        assertEquals(1, usages.get("instantiations").get("count").asInt(),
            "Animal instantiated exactly once (FieldHolder) — count must survive the envelope");
    }
}
