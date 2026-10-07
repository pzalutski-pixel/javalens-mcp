package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_type_usage_summary on simple-maven's Calculator, Marker and Animal. Every expectation is the
 * whole payload, derived from the fixture source and asserted through execute() and through the
 * JSON-RPC envelope. Each category lists its locations in search order: by file path, then
 * position. Each location is the type's name where it is used; raw offsets depend on line endings,
 * so they are removed and line and column pin each location.
 *
 * <p>Calculator is instantiated five times (SearchPatterns lines 57, 211 and 226, UserService line
 * 19, SampleTest line 25), cast once (SearchPatterns line 79), checked with instanceof twice
 * (SearchPatterns lines 78 and 99) and a type argument twice (SearchPatterns lines 26 and 210).
 * AnnotationUsages applies @Marker in seven positions; FieldHolder instantiates Animal once.
 */
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

    @Test
    @DisplayName("Calculator usage summary with maxPerCategory=50: every usage of every category, exact counts")
    void calculator_summaryCountsMatchSiblingTools() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 50);
        assertPayload(args, calculatorEverything());
    }

    @Test @DisplayName("finds type by simple name: Calculator resolves to com.example.Calculator, all usages within the default 10")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");
        assertPayload(args, calculatorEverything());
    }

    @Test @DisplayName("maxPerCategory=2 lists at most two locations a category, in search order, and counts every usage")
    void respectsMaxPerCategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 2);
        // Only instantiations (5) has more usages than the cap: 7 of the 10 locations are listed.
        Map<String, Object> usages = new LinkedHashMap<>();
        usages.put("instantiations", category(5, List.of(NEW_CALCULATOR_57, NEW_CALCULATOR_211)));
        usages.put("casts", category(1, List.of(CAST_79)));
        usages.put("instanceofChecks", category(2, List.of(INSTANCEOF_78, INSTANCEOF_99)));
        usages.put("typeArguments", category(2, List.of(TYPE_ARGUMENT_26, TYPE_ARGUMENT_210)));
        assertPayload(args, summaryPayload("com.example.Calculator", "class", usages, 10, 7, null));
    }

    @Test @DisplayName("missing typeName -> exact INVALID_PARAMETER")
    void requiresTypeName() {
        assertPayload(objectMapper.createObjectNode(),
            refusal("Invalid parameter 'typeName': Required parameter missing"));
    }

    @Test @DisplayName("unknown type -> exact SYMBOL_NOT_FOUND")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        assertPayload(unknown, Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: Type not found: com.nonexistent.Type",
            "hint", "Use search_symbols to find available symbols")));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Response carries typeName, kind, usages, totalUsages")
    void responseShape_carriesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        assertPayload(args, calculatorEverything());
    }

    @Test
    @DisplayName("usages has all 4 subcategories: instantiations, casts, instanceofChecks, typeArguments")
    void usages_hasAllCategories() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        assertPayload(args, calculatorEverything());
    }

    @Test
    @DisplayName("Each subcategory has count + locations; with every usage within maxPerCategory, count equals the locations listed")
    void subcategory_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 50);
        assertPayload(args, calculatorEverything());
    }

    @Test
    @DisplayName("Annotation type (Marker): annotationUsages subcategory is added; kind='annotation'")
    void annotationType_addsAnnotationUsagesSubcategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxPerCategory", 50);
        assertPayload(args, marker());
    }

    @Test
    @DisplayName("Non-annotation type (Calculator): annotationUsages subcategory is OMITTED")
    void nonAnnotationType_omitsAnnotationUsagesSubcategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        assertPayload(args, calculatorEverything());
    }

    @Test
    @DisplayName("Animal: typeArgument count=0, cast count=0, instanceof count=0; instantiation count=1 (FieldHolder)")
    void animal_isolation_counts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");
        args.put("maxPerCategory", 50);
        assertPayload(args, animal());
    }

    @Test
    @DisplayName("Annotation Marker: TYPE_USE positions (parameter, local var, type argument) are included")
    void annotation_typeUsePositions_included() {
        // @Marker on the class (6), field (9), constructor (12), method (16), and TYPE_USE on a
        // parameter (30), a local variable (31) and a type argument (35).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxPerCategory", 50);
        assertPayload(args, marker());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Animal usage is casts 0, instanceof 0, typeArgs 0, instantiations 1")
    void envelope_animal_exactCounts() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Animal");
        args.put("maxPerCategory", 50);
        assertPayload(args, animal());
    }

    // ========== maxPerCategory: exact counts, cut locations, documented range ==========

    @Test
    @DisplayName("maxPerCategory=1 on Calculator keeps the first location of each category and counts every usage exactly")
    void maxPerCategoryOne_exactCountsFirstLocations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 1);
        Map<String, Object> usages = new LinkedHashMap<>();
        usages.put("instantiations", category(5, List.of(NEW_CALCULATOR_57)));
        usages.put("casts", category(1, List.of(CAST_79)));
        usages.put("instanceofChecks", category(2, List.of(INSTANCEOF_78)));
        usages.put("typeArguments", category(2, List.of(TYPE_ARGUMENT_26)));
        assertPayload(args, summaryPayload("com.example.Calculator", "class", usages, 10, 4, null));
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
    private static final String USER_SERVICE = "src/main/java/com/example/service/UserService.java";
    private static final String SAMPLE_TEST = "src/test/java/com/example/SampleTest.java";
    private static final String ANNOTATION_USAGES = "src/main/java/com/example/AnnotationUsages.java";

    /* Each Calculator location is the type reference "Calculator", 10 characters. */
    private static final Map<String, Object> NEW_CALCULATOR_57 =
        location(SEARCH_PATTERNS, 10, 57, 30, "Calculator calc = new Calculator();");
    private static final Map<String, Object> NEW_CALCULATOR_211 =
        location(SEARCH_PATTERNS, 10, 211, 22, "calcs.add(new Calculator());");
    private static final Map<String, Object> NEW_CALCULATOR_226 =
        location(SEARCH_PATTERNS, 10, 226, 23, "return new Calculator();");
    private static final Map<String, Object> NEW_CALCULATOR_USER_SERVICE =
        location(USER_SERVICE, 10, 19, 30, "this.calculator = new Calculator();");
    private static final Map<String, Object> NEW_CALCULATOR_SAMPLE_TEST =
        location(SAMPLE_TEST, 10, 25, 25, "calculator = new Calculator();");
    private static final Map<String, Object> CAST_79 =
        location(SEARCH_PATTERNS, 10, 79, 31, "Calculator calc = (Calculator) obj;");
    private static final Map<String, Object> INSTANCEOF_78 =
        location(SEARCH_PATTERNS, 10, 78, 27, "if (obj instanceof Calculator) {");
    private static final Map<String, Object> INSTANCEOF_99 =
        location(SEARCH_PATTERNS, 10, 99, 27, "if (obj instanceof Calculator) {");
    private static final Map<String, Object> TYPE_ARGUMENT_26 =
        location(SEARCH_PATTERNS, 10, 26, 17, "private List<Calculator> calculatorList;");
    private static final Map<String, Object> TYPE_ARGUMENT_210 =
        location(SEARCH_PATTERNS, 10, 210, 13, "List<Calculator> calcs = new ArrayList<>();");

    private static final String NO_DIRECT_USAGE =
        "Type has no direct usage in project source - may be used only via libraries or unused";

    private static Map<String, Object> location(String file, int length, int line, int column, String context) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("filePath", file);
        location.put("length", length);
        location.put("line", line);
        location.put("column", column);
        location.put("context", context);
        return location;
    }

    private static Map<String, Object> category(int count, List<Map<String, Object>> locations) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("count", count);
        category.put("locations", locations);
        return category;
    }

    /** Calculator with every one of its ten usages listed. */
    private static Map<String, Object> calculatorEverything() {
        Map<String, Object> usages = new LinkedHashMap<>();
        usages.put("instantiations", category(5, List.of(NEW_CALCULATOR_57, NEW_CALCULATOR_211, NEW_CALCULATOR_226,
            NEW_CALCULATOR_USER_SERVICE, NEW_CALCULATOR_SAMPLE_TEST)));
        usages.put("casts", category(1, List.of(CAST_79)));
        usages.put("instanceofChecks", category(2, List.of(INSTANCEOF_78, INSTANCEOF_99)));
        usages.put("typeArguments", category(2, List.of(TYPE_ARGUMENT_26, TYPE_ARGUMENT_210)));
        return summaryPayload("com.example.Calculator", "class", usages, 10, 10, null);
    }

    /**
     * Marker, an annotation type: never instantiated, cast, checked or a type argument, so the
     * insight says it has no direct usage; each @Marker location is its name after the @.
     */
    private static Map<String, Object> marker() {
        Map<String, Object> usages = new LinkedHashMap<>();
        usages.put("instantiations", category(0, List.of()));
        usages.put("casts", category(0, List.of()));
        usages.put("instanceofChecks", category(0, List.of()));
        usages.put("typeArguments", category(0, List.of()));
        usages.put("annotationUsages", category(7, List.of(
            location(ANNOTATION_USAGES, 6, 6, 1, "@Marker"),
            location(ANNOTATION_USAGES, 6, 9, 5, "@Marker"),
            location(ANNOTATION_USAGES, 6, 12, 5, "@Marker"),
            location(ANNOTATION_USAGES, 6, 16, 5, "@Marker"),
            location(ANNOTATION_USAGES, 6, 30, 33, "public void markedParameter(@Marker int p) {"),
            location(ANNOTATION_USAGES, 6, 31, 9, "@Marker int local = p;"),
            location(ANNOTATION_USAGES, 6, 35, 17, "public List<@Marker String> typeUseUsage() {"))));
        return summaryPayload("com.example.Marker", "annotation", usages, 7, 7, List.of(NO_DIRECT_USAGE));
    }

    /** Animal, a class instantiated once by FieldHolder and used no other way. */
    private static Map<String, Object> animal() {
        Map<String, Object> usages = new LinkedHashMap<>();
        usages.put("instantiations", category(1, List.of(
            location("src/main/java/com/example/FieldHolder.java", 6, 7, 23, "this.pet = new Animal();"))));
        usages.put("casts", category(0, List.of()));
        usages.put("instanceofChecks", category(0, List.of()));
        usages.put("typeArguments", category(0, List.of()));
        return summaryPayload("com.example.Animal", "class", usages, 1, 1, null);
    }

    /** The success payload for {@code usages}: {@code total} usages, {@code returned} locations listed. */
    private static Map<String, Object> summaryPayload(String typeName, String kind, Map<String, Object> usages,
                                                      int total, int returned, List<String> insights) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("typeName", typeName);
        data.put("kind", kind);
        data.put("usages", usages);
        data.put("totalUsages", total);
        if (insights != null) {
            data.put("insights", insights);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", returned);
        meta.put("truncated", returned < total);
        meta.put("suggestedNextTools", List.of(
            "analyze_type for complete type analysis",
            "get_type_hierarchy to understand inheritance",
            "find_references for all reference types"));
        Map<String, Object> payload = new LinkedHashMap<>();
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
