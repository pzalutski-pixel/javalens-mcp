package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.SearchSymbolsTool;
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
 * search_symbols on the simple-maven fixture: pattern matching, kind filtering, pagination and
 * parameter validation. Every expectation is the whole payload, derived from the fixture source
 * and asserted through execute() and through the JSON-RPC envelope. Positions are zero-based and
 * point at each declaration's name; results come in search order (by file path, then source
 * order).
 */
class SearchSymbolsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String MAIN = "src/main/java/com/example/";

    private static final List<String> NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    private static final Map<String, Object> CALCULATOR =
        type("Calculator", "class", MAIN + "Calculator.java", 5, 13, "com.example.Calculator", "com.example");
    private static final Map<String, Object> USER_SERVICE = type("UserService", "class",
        MAIN + "service/UserService.java", 10, 13, "com.example.service.UserService", "com.example.service");
    private static final Map<String, Object> FIELD_HOLDER =
        type("FieldHolder", "class", MAIN + "FieldHolder.java", 2, 13, "com.example.FieldHolder", "com.example");
    private static final Map<String, Object> I_SHAPE =
        type("IShape", "interface", MAIN + "IShape.java", 2, 17, "com.example.IShape", "com.example");
    private static final Map<String, Object> COLOR = type("Color", "enum", MAIN + "TypeKindsFixture.java", 11, 16,
        "com.example.TypeKindsFixture$Color", "com.example");
    private static final Map<String, Object> INNER_SHAPE = type("InnerShape", "class",
        MAIN + "AnonymousShapeUser.java", 27, 24, "com.example.AnonymousShapeUser$InnerShape", "com.example");
    private static final Map<String, Object> NON_STATIC_INNER_SHAPE = type("NonStaticInnerShape", "class",
        MAIN + "AnonymousShapeUser.java", 34, 17, "com.example.AnonymousShapeUser$NonStaticInnerShape", "com.example");

    /**
     * add(String) overridden by the anonymous ArrayList subclass in
     * AnonymousClassExamples.nonFunctionalInterface: its @Override is on line 127, the
     * declaration on line 128.
     */
    private static final Map<String, Object> ANONYMOUS_ADD =
        method("add", MAIN + "AnonymousClassExamples.java", 128, 27, "add(String s)", "");
    private static final Map<String, Object> CALCULATOR_ADD =
        method("add", MAIN + "Calculator.java", 14, 15, "add(int a, int b)", "Calculator");
    private static final Map<String, Object> ADD_USER =
        method("addUser", MAIN + "service/UserService.java", 27, 19, "addUser(String username)", "UserService");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SearchSymbolsTool tool;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SearchSymbolsTool(() -> service);
        envelope = new EnvelopeHarness(service);
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class search for Calculator returns exactly com.example.Calculator with name, kind, location, qualifiedName and package")
    void classSearch_returnsAllFields() {
        assertPayload(args("Calculator", "class", null, null), page("Calculator", "class", 0, List.of(CALCULATOR), 1));
    }

    @Test
    @DisplayName("Trailing wildcard Calc* matches exactly Calculator")
    void trailingWildcard_matchesCorrectly() {
        assertPayload(args("Calc*", null, null, null), page("Calc*", null, 0, List.of(CALCULATOR), 1));
    }

    @Test
    @DisplayName("Leading wildcard *Service matches exactly UserService")
    void leadingWildcard_matchesCorrectly() {
        assertPayload(args("*Service", null, null, null), page("*Service", null, 0, List.of(USER_SERVICE), 1));
    }

    @Test
    @DisplayName("Method kind filter (given as Method) returns exactly the methods named add*: the anonymous class's add, Calculator.add and UserService.addUser")
    void methodKindFilter_returnsOnlyMethods() {
        assertPayload(args("add*", "Method", null, null),
            page("add*", "Method", 0, List.of(ANONYMOUS_ADD, CALCULATOR_ADD, ADD_USER), 3));
    }

    // ========== Pagination Tests ==========

    @Test
    @DisplayName("Pagination: *Shape with maxResults=2 returns the first two of the four *Shape types and reports more")
    void pagination_returnsCorrectMetadata() {
        // The types named *Shape in search order: AnonymousShapeUser's InnerShape and
        // NonStaticInnerShape, IShape, and Java21Modern's Shape.
        assertPayload(args("*Shape", null, 2, 0),
            page("*Shape", null, 0, List.of(INNER_SHAPE, NON_STATIC_INNER_SHAPE), 4));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or blank query is INVALID_PARAMETER naming query")
    void parameterValidation_returnsErrors() {
        Map<String, Object> error = error("Invalid parameter 'query': Required parameter missing");
        ObjectNode blank = objectMapper.createObjectNode();
        blank.put("query", "   ");
        assertAll(
            () -> assertPayload(objectMapper.createObjectNode(), error),
            () -> assertPayload(blank, error));
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("No matches returns an empty result list with totalCount 0 and no more pages")
    void noMatches_returnsEmptyResults() {
        assertPayload(args("NonExistentClass", null, null, null), page("NonExistentClass", null, 0, List.of(), 0));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Middle wildcard F*er matches exactly FieldHolder (FilledCircle does not end in er)")
    void middleWildcard_matchesCorrectly() {
        assertPayload(args("F*er", null, null, null), page("F*er", null, 0, List.of(FIELD_HOLDER), 1));
    }

    @Test
    @DisplayName("Single-char wildcard ?Shape matches exactly IShape, not Shape, InnerShape or NonStaticInnerShape")
    void singleCharWildcard_matchesCorrectly() {
        assertPayload(args("?Shape", null, 1000, null), page("?Shape", null, 0, List.of(I_SHAPE), 1));
    }

    @Test
    @DisplayName("Kind=interface filter on IShape returns exactly the IShape interface")
    void kindInterface_filterReturnsOnlyInterfaces() {
        assertPayload(args("IShape", "interface", 100, null), page("IShape", "interface", 0, List.of(I_SHAPE), 1));
    }

    @Test
    @DisplayName("Kind=Field filter: lastResult resolves to exactly Calculator.lastResult with readable type 'int'")
    void kindField_filterReturnsOnlyFields() {
        Map<String, Object> lastResult = new LinkedHashMap<>();
        lastResult.put("name", "lastResult");
        lastResult.put("kind", "field");
        lastResult.put("filePath", MAIN + "Calculator.java");
        lastResult.put("line", 6);
        lastResult.put("column", 16);
        lastResult.put("type", "int");
        lastResult.put("containingType", "Calculator");
        assertPayload(args("lastResult", "Field", null, null), page("lastResult", "Field", 0, List.of(lastResult), 1));
    }

    @Test
    @DisplayName("Type result includes qualifiedName and package")
    void typeResult_includesQualifiedNameAndPackage() {
        assertPayload(args("Calculator", "class", null, null), page("Calculator", "class", 0, List.of(CALCULATOR), 1));
    }

    @Test
    @DisplayName("Method results for add carry signature and containingType: the anonymous class's add (containingType empty) and Calculator.add")
    void methodResult_includesSignatureAndContainingType() {
        assertPayload(args("add", "Method", 1000, null),
            page("add", "Method", 0, List.of(ANONYMOUS_ADD, CALCULATOR_ADD), 2));
    }

    @Test
    @DisplayName("Negative maxResults returns INVALID_PARAMETER naming maxResults")
    void negativeMaxResults_returnsInvalidParameter() {
        assertPayload(args("Calculator", null, -1, null),
            error("Invalid parameter 'maxResults': Must be >= 0; got: -1"));
    }

    @Test
    @DisplayName("Negative offset returns INVALID_PARAMETER naming offset (independent from maxResults)")
    void negativeOffset_returnsInvalidParameter() {
        assertPayload(args("Calculator", null, null, -1),
            error("Invalid parameter 'offset': Must be >= 0; got: -1"));
    }

    @Test
    @DisplayName("kind='enum' filter on Color returns exactly TypeKindsFixture.Color")
    void kindEnum_filterReturnsOnlyEnums() {
        assertPayload(args("Color", "enum", 1000, null), page("Color", "enum", 0, List.of(COLOR), 1));
    }

    @Test
    @DisplayName("Pagination offset skips the first N results: add* methods, two per page, offsets 0 and 2")
    void pagination_offsetSkipsResults() {
        assertAll(
            () -> assertPayload(args("add*", "Method", 2, 0),
                page("add*", "Method", 0, List.of(ANONYMOUS_ADD, CALCULATOR_ADD), 3)),
            () -> assertPayload(args("add*", "Method", 2, 2),
                page("add*", "Method", 2, List.of(ADD_USER), 3)));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator class search returns exactly com.example.Calculator")
    void envelope_calculatorSearch_exactQualifiedName() {
        assertPayload(args("Calculator", "class", null, null), page("Calculator", "class", 0, List.of(CALCULATOR), 1));
    }

    private ObjectNode args(String query, String kind, Integer maxResults, Integer offset) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", query);
        if (kind != null) args.put("kind", kind);
        if (maxResults != null) args.put("maxResults", maxResults);
        if (offset != null) args.put("offset", offset);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct = objectMapper.valueToTree(tool.execute(args.deepCopy()));
        JsonNode wire = envelope.assertEnvelopeFidelity("search_symbols", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** A page of {@code total} matching declarations starting at {@code offset}. */
    private static Map<String, Object> page(String query, String kind, int offset,
                                            List<Map<String, Object>> results, int total) {
        boolean hasMore = offset + results.size() < total;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        if (kind != null) {
            data.put("kind", kind);
        }
        data.put("results", results);
        data.put("pagination", Map.of("offset", offset, "returned", results.size(), "hasMore", hasMore));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", results.size());
        meta.put("truncated", hasMore);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    private static Map<String, Object> type(String name, String kind, String file, int line, int column,
                                            String qualifiedName, String pkg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", kind);
        result.put("filePath", file);
        result.put("line", line);
        result.put("column", column);
        result.put("qualifiedName", qualifiedName);
        result.put("package", pkg);
        return result;
    }

    private static Map<String, Object> method(String name, String file, int line, int column, String signature,
                                              String containingType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", "method");
        result.put("filePath", file);
        result.put("line", line);
        result.put("column", column);
        result.put("signature", signature);
        result.put("containingType", containingType);
        return result;
    }
}
