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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * search_symbols' {@code kind} filter on the symbol-kinds fixture, whose single file
 * Catalog.java declares two classes, an interface, an enum, a record and an annotation type;
 * five methods and three constructors; four fields of class Catalog (instance, static, static
 * final, final instance) and the enum's two constants and instance field. Parameters, local
 * variables and the record's components share the file without being field declarations. The
 * method test(String) holds a lambda implementing Predicate.test, one implementing
 * Function.apply and a method reference; none of them is a method or type declaration.
 *
 * <p>Each accepted kind returns exactly the declarations of that kind, compared
 * case-insensitively; without a kind the search returns every type; any other kind is an
 * INVALID_PARAMETER naming the accepted values. Every expectation is the whole payload, asserted
 * through execute() and through the JSON-RPC envelope.
 */
class SearchSymbolsKindTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CATALOG = "src/main/java/org/example/kinds/Catalog.java";
    private static final String PACKAGE = "org.example.kinds";

    private static final List<String> NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    // Types, in source order (zero-based line and column of each name).
    private static final Map<String, Object> CATALOG_CLASS = type("Catalog", "class", "Catalog", 8, 13);
    private static final Map<String, Object> NOTE = type("Note", "class", "Catalog$Note", 34, 17);
    private static final Map<String, Object> SHELF = type("Shelf", "interface", "Catalog$Shelf", 37, 21);
    private static final Map<String, Object> SHADE = type("Shade", "enum", "Catalog$Shade", 41, 16);
    private static final Map<String, Object> ITEM = type("Item", "record", "Catalog$Item", 48, 18);
    private static final Map<String, Object> TAG = type("Tag", "annotation", "Catalog$Tag", 57, 22);

    // Methods; the constructors Catalog(), Catalog(int) and the compact Item constructor are not
    // methods, and neither are the lambdas inside test(String). Catalog's methods precede its member
    // types' methods in search order.
    private static final Map<String, Object> COUNT_METHOD = method("count", 25, 15, "count()", "Catalog");
    private static final Map<String, Object> REGISTER = method("register", 29, 16, "register(String name)", "Catalog");
    private static final Map<String, Object> TEST = method("test", 60, 12, "test(String s)", "Catalog");
    private static final Map<String, Object> PLACE = method("place", 38, 13, "place(Item item)", "Shelf");
    private static final Map<String, Object> SUMMARY = method("summary", 52, 22, "summary()", "Item");

    // Field declarations, each with its finer kind. The record components title and pages,
    // the parameters count, name and item, and the local variable entry are not field declarations.
    private static final Map<String, Object> COUNT_FIELD = field("count", "field", 10, 16, "int", "Catalog");
    private static final Map<String, Object> CREATED = field("created", "field", 12, 15, "int", "Catalog");
    private static final Map<String, Object> LABEL = field("LABEL", "constant", 14, 31, "String", "Catalog");
    private static final Map<String, Object> WIDTH = field("width", "field", 16, 14, "int", "Catalog");
    private static final Map<String, Object> LIGHT = field("LIGHT", "enumConstant", 42, 8, "Shade", "Shade");
    private static final Map<String, Object> DARK = field("DARK", "enumConstant", 43, 8, "Shade", "Shade");
    private static final Map<String, Object> DEPTH = field("depth", "field", 45, 20, "int", "Shade");

    private static final String ACCEPTED =
        "Must be one of class, interface, enum, record, annotation, method, field (case-insensitive); got: ";

    private final ObjectMapper mapper = new ObjectMapper();
    private SearchSymbolsTool tool;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("symbol-kinds");
        tool = new SearchSymbolsTool(() -> service);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("kind=class returns exactly the classes: no interface, enum, record or annotation type")
    void classKind_returnsExactlyTheClasses() {
        assertPayload(search("class"), success("class", List.of(CATALOG_CLASS, NOTE)));
    }

    @Test
    @DisplayName("kind=interface returns exactly the interface, not the annotation type")
    void interfaceKind_returnsExactlyTheInterface() {
        assertPayload(search("interface"), success("interface", List.of(SHELF)));
    }

    @Test
    @DisplayName("kind=enum returns exactly the enum")
    void enumKind_returnsExactlyTheEnum() {
        assertPayload(search("enum"), success("enum", List.of(SHADE)));
    }

    @Test
    @DisplayName("kind=record returns exactly the record")
    void recordKind_returnsExactlyTheRecord() {
        assertPayload(search("record"), success("record", List.of(ITEM)));
    }

    @Test
    @DisplayName("kind=annotation returns exactly the annotation type")
    void annotationKind_returnsExactlyTheAnnotationType() {
        assertPayload(search("annotation"), success("annotation", List.of(TAG)));
    }

    @Test
    @DisplayName("kind=method returns exactly the methods, interface and record methods included, constructors and lambda expressions excluded")
    void methodKind_returnsExactlyTheMethods() {
        assertPayload(search("method"), success("method", List.of(COUNT_METHOD, REGISTER, TEST, PLACE, SUMMARY)));
    }

    @Test
    @DisplayName("kind=method on test returns only the declared method, not the lambda implementing Predicate.test; on apply it returns nothing")
    void methodKind_namedLikeFunctionalMethods_excludesLambdas() {
        assertAll(
            () -> assertPayload(search("test", "method"), success("test", "method", List.of(TEST))),
            () -> assertPayload(search("apply", "method"), success("apply", "method", List.of())));
    }

    @Test
    @DisplayName("kind=field returns every field declaration (instance, static, static final, final instance, enum constants), each with its finer kind")
    void fieldKind_returnsEveryFieldDeclaration() {
        assertPayload(search("field"),
            success("field", List.of(COUNT_FIELD, CREATED, LABEL, WIDTH, LIGHT, DARK, DEPTH)));
    }

    @Test
    @DisplayName("without kind the search returns every type and no lambda, method or field")
    void noKind_returnsEveryType() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "*");
        assertPayload(args, success(null, List.of(CATALOG_CLASS, NOTE, SHELF, SHADE, ITEM, TAG)));
    }

    @Test
    @DisplayName("kind is compared case-insensitively and echoed as given")
    void kind_isCaseInsensitive() {
        assertAll(
            () -> assertPayload(search("FIELD"),
                success("FIELD", List.of(COUNT_FIELD, CREATED, LABEL, WIDTH, LIGHT, DARK, DEPTH))),
            () -> assertPayload(search("Class"), success("Class", List.of(CATALOG_CLASS, NOTE))),
            () -> assertPayload(search("ReCoRd"), success("ReCoRd", List.of(ITEM))),
            () -> assertPayload(search("Annotation"), success("Annotation", List.of(TAG))),
            () -> assertPayload(search("METHOD"),
                success("METHOD", List.of(COUNT_METHOD, REGISTER, TEST, PLACE, SUMMARY))));
    }

    @ParameterizedTest(name = "kind=\"{0}\"")
    @ValueSource(strings = {"constant", "enumConstant", "constructor", "type", "fields", "foo", ""})
    @DisplayName("an unrecognized kind is INVALID_PARAMETER naming every accepted value")
    void unrecognizedKind_isInvalidParameter(String kind) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAMETER");
        error.put("message", "Invalid parameter 'kind': " + ACCEPTED + kind);
        assertPayload(search(kind), Map.of("success", false, "error", error));
    }

    private ObjectNode search(String kind) {
        return search("*", kind);
    }

    private ObjectNode search(String query, String kind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        args.put("kind", kind);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct = mapper.valueToTree(tool.execute(args.deepCopy()));
        JsonNode wire = envelope.assertEnvelopeFidelity("search_symbols", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** A complete, unpaged result for query "*". */
    private static Map<String, Object> success(String kind, List<Map<String, Object>> results) {
        return success("*", kind, results);
    }

    /** A complete, unpaged result: the query, the kind as given (absent when null), every result returned. */
    private static Map<String, Object> success(String query, String kind, List<Map<String, Object>> results) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        if (kind != null) {
            data.put("kind", kind);
        }
        data.put("results", results);
        data.put("pagination", Map.of("offset", 0, "returned", results.size(), "hasMore", false));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", results.size());
        meta.put("returnedCount", results.size());
        meta.put("truncated", false);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> type(String name, String kind, String binaryName, int line, int column) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", kind);
        result.put("filePath", CATALOG);
        result.put("line", line);
        result.put("column", column);
        result.put("qualifiedName", PACKAGE + "." + binaryName);
        result.put("package", PACKAGE);
        return result;
    }

    private static Map<String, Object> method(String name, int line, int column, String signature, String containingType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", "method");
        result.put("filePath", CATALOG);
        result.put("line", line);
        result.put("column", column);
        result.put("signature", signature);
        result.put("containingType", containingType);
        return result;
    }

    private static Map<String, Object> field(String name, String kind, int line, int column, String type, String containingType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("kind", kind);
        result.put("filePath", CATALOG);
        result.put("line", line);
        result.put("column", column);
        result.put("type", type);
        result.put("containingType", containingType);
        return result;
    }
}
