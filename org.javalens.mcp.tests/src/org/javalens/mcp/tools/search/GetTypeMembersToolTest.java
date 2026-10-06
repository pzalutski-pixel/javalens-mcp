package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * get_type_members on the simple-maven fixture. Every expectation is derived from the fixture
 * source: a member's line is where its source range starts, at its Javadoc or annotations when
 * it has them, and members come in source order. Result payloads are asserted whole through
 * execute() and through the JSON-RPC envelope; the inherited java.lang.Object members, which
 * come from the JDK running the tests, are the one part pinned by named entries instead.
 */
class GetTypeMembersToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String MAIN = "src/main/java/com/example/";

    private static final List<String> NEXT_TOOLS = List.of(
        "find_references to find usages of a member",
        "go_to_definition to navigate to member source",
        "get_type_hierarchy to see inheritance chain");

    private static final Map<String, Object> CALCULATOR_TYPE =
        typeBlock("Calculator", "com.example.Calculator", MAIN + "Calculator.java");

    /** Calculator's methods; each line is the start of the method's Javadoc. */
    private static final List<Map<String, Object>> CALCULATOR_METHODS = List.of(
        member("add", "method", List.of("public"), "signature", "add(int a, int b): int", 8),
        member("subtract", "method", List.of("public"), "signature", "subtract(int a, int b): int", 19),
        member("multiply", "method", List.of("public"), "signature", "multiply(int a, int b): int", 30),
        member("getLastResult", "method", List.of("public"), "signature", "getLastResult(): int", 41));

    private static final List<Map<String, Object>> CALCULATOR_FIELDS = List.of(
        member("lastResult", "field", List.of("private"), "type", "int", 6));

    /** FilledCircle's constructor and its two overrides; each override's line is its @Override. */
    private static final List<Map<String, Object>> FILLED_CIRCLE_METHODS = List.of(
        member("FilledCircle", "constructor", List.of("public"), "signature",
            "FilledCircle(int radius, String fillColor)", 7),
        member("draw", "method", List.of("public"), "signature", "draw(): void", 12),
        member("fill", "method", List.of("public"), "signature", "fill(): void", 17));

    private static final List<Map<String, Object>> FILLED_CIRCLE_FIELDS = List.of(
        member("radius", "field", List.of("private", "final"), "type", "int", 4),
        member("fillColor", "field", List.of("private"), "type", "String", 5));

    /** java.lang.Object.toString as an inherited, binary member: declaredIn set, no line. */
    private static final Map<String, Object> OBJECT_TO_STRING = inherited(
        "toString", List.of("public"), "toString(): String", "java.lang.Object", null);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetTypeMembersTool tool;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeMembersTool(() -> service);
        envelope = new EnvelopeHarness(service);
    }

    @Test @DisplayName("gets type members with complete response: Calculator's type block, 4 methods, 1 field, no nested types")
    void getsTypeMembersComprehensively() {
        assertPayload(args("com.example.Calculator", null, null), calculatorAll());
    }

    @Test @DisplayName("supports optional parameters: includeInherited adds Object.toString with declaredIn; memberKind=method returns only the 4 methods")
    void supportsOptionalParameters() {
        JsonNode inherited = envelope.assertEnvelopeFidelity("get_type_members",
            args("com.example.Calculator", true, null));
        List<JsonNode> methods = list(inherited.path("data").path("methods"));
        assertAll(
            () -> assertEquals(json(CALCULATOR_METHODS), json(methods.subList(0, 4)),
                "Calculator's own methods come first, without declaredIn"),
            () -> assertEquals(List.of(json(OBJECT_TO_STRING)), named(methods.subList(4, methods.size()), "toString"),
                "the one inherited toString is Object's"),
            () -> assertEquals(json(CALCULATOR_FIELDS), inherited.path("data").path("fields"),
                "Object declares no fields, so only lastResult"));

        assertPayload(args("com.example.Calculator", null, "method"),
            payload(CALCULATOR_TYPE, Map.of("methods", CALCULATOR_METHODS), 4));
    }

    @Test @DisplayName("simple name Calculator resolves to the same payload as the FQN")
    void findsTypeBySimpleName() {
        assertPayload(args("Calculator", null, null), calculatorAll());
    }

    @Test @DisplayName("missing typeName is rejected with INVALID_PARAMETER")
    void requiresTypeName() {
        assertPayload(objectMapper.createObjectNode(), Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'typeName': Required parameter missing")));
    }

    @Test @DisplayName("unknown type is rejected with SYMBOL_NOT_FOUND naming the type")
    void handlesUnknownType() {
        assertPayload(args("com.nonexistent.Type", null, null), Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: Type not found: com.nonexistent.Type",
            "hint", "Use search_symbols to find available symbols")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator: exact method list (add, subtract, multiply, getLastResult), no constructor")
    void calculator_exactDeclaredMethodSet() {
        assertPayload(args("com.example.Calculator", null, null), calculatorAll());
    }

    @Test
    @DisplayName("FilledCircle declares its constructor, draw and fill (both overrides) and two fields, without inherited Object methods")
    void filledCircle_declaredMethodsOnly() {
        Map<String, Object> lists = new LinkedHashMap<>();
        lists.put("methods", FILLED_CIRCLE_METHODS);
        lists.put("fields", FILLED_CIRCLE_FIELDS);
        lists.put("nestedTypes", List.of());
        assertPayload(args("com.example.FilledCircle", null, null), payload(filledCircleType(), lists, 5));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("memberKind=field returns only Calculator's field, omits methods and nestedTypes")
    void memberKindField_filtersToFieldsOnly() {
        assertPayload(args("com.example.Calculator", null, "field"),
            payload(CALCULATOR_TYPE, Map.of("fields", CALCULATOR_FIELDS), 1));
    }

    @Test
    @DisplayName("memberKind=type returns TypeKindsFixture's five member types, each line at its Javadoc, and omits methods and fields")
    void memberKindType_filtersToNestedTypesOnly() {
        List<Map<String, Object>> nested = List.of(
            nested("Color", "com.example.TypeKindsFixture$Color", "enum", List.of("public"), 10),
            nested("GenericContainer", "com.example.TypeKindsFixture$GenericContainer", "class",
                List.of("public", "static"), 15),
            nested("Inner", "com.example.TypeKindsFixture$Inner", "class", List.of("public", "static"), 29),
            nested("DefaultMethodHolder", "com.example.TypeKindsFixture$DefaultMethodHolder", "interface",
                List.of("public"), 60),
            nested("BoundedBox", "com.example.TypeKindsFixture$BoundedBox", "class", List.of("public", "static"), 94));
        assertPayload(args("com.example.TypeKindsFixture", null, "type"), payload(
            typeBlock("TypeKindsFixture", "com.example.TypeKindsFixture", MAIN + "TypeKindsFixture.java"),
            Map.of("nestedTypes", nested), 5));
    }

    @Test
    @DisplayName("includeInherited=true on FilledCircle: its own members exactly, IFillable's and IShape's methods with declaredIn, and Object's toString, equals and hashCode")
    void includeInherited_addsSuperMethods() {
        JsonNode payload = envelope.assertEnvelopeFidelity("get_type_members",
            args("com.example.FilledCircle", true, null));
        JsonNode data = payload.path("data");
        List<JsonNode> methods = list(data.path("methods"));
        int count = methods.size();

        // The interface methods come last; the order of the two interfaces is the type
        // hierarchy's, so they are compared as a set.
        Set<JsonNode> interfaceMethods = Set.of(
            json(inherited("fill", List.of(), "fill(): void", "com.example.IFillable", 4)),
            json(inherited("draw", List.of(), "draw(): void", "com.example.IShape", 4)),
            json(inherited("describe", List.of("default"), "describe(): String", "com.example.IShape", 6)));

        // Between them are java.lang.Object's methods. Their full set and rendering come from the
        // JDK running the tests (binary members, parameter names with or without attached
        // source), so an exact list is not derivable from the fixture: they are pinned as the
        // methods declared in java.lang.Object that include toString, equals and hashCode.
        List<JsonNode> objectMethods = methods.subList(3, count - 3);
        Set<String> objectNames = new TreeSet<>();
        for (JsonNode method : objectMethods) {
            objectNames.add(method.path("name").asText());
        }

        assertAll(
            () -> assertEquals(json(FILLED_CIRCLE_METHODS), json(methods.subList(0, 3)),
                "FilledCircle's own methods come first"),
            () -> assertEquals(interfaceMethods, Set.copyOf(methods.subList(count - 3, count)),
                "IFillable.fill, IShape.draw and IShape.describe come last"),
            () -> assertEquals(Set.of("java.lang.Object"), declaredIn(objectMethods),
                "every method between them is declared in java.lang.Object"),
            () -> assertTrue(objectNames.containsAll(Set.of("toString", "equals", "hashCode")),
                "bound, see above: Object's methods include toString, equals and hashCode; got " + objectNames),
            () -> assertEquals(json(FILLED_CIRCLE_FIELDS), data.path("fields"), "Object declares no fields"),
            () -> assertEquals(json(List.of()), data.path("nestedTypes"), "member types are not inherited"),
            () -> assertEquals(count + 2, data.path("totalMembers").asInt(), "totalMembers counts every list"),
            () -> assertEquals(count + 2, payload.path("meta").path("totalCount").asInt(), "meta.totalCount"));
    }

    @Test
    @DisplayName("includeInherited=true: inherited Object.toString carries declaredIn and no line; FilledCircle's own draw carries a line and no declaredIn")
    void includeInherited_declaredInIsSetToSourceType() {
        JsonNode payload = envelope.assertEnvelopeFidelity("get_type_members",
            args("com.example.FilledCircle", true, null));
        List<JsonNode> methods = list(payload.path("data").path("methods"));
        assertAll(
            () -> assertEquals(List.of(json(OBJECT_TO_STRING)), named(methods, "toString")),
            () -> assertEquals(List.of(json(FILLED_CIRCLE_METHODS.get(1)),
                    json(inherited("draw", List.of(), "draw(): void", "com.example.IShape", 4))),
                named(methods, "draw")));
    }

    @Test
    @DisplayName("memberKind='unknown' (not method/field/type) is INVALID_PARAMETER naming the accepted values, with no data")
    void memberKindUnknown_isInvalidParameter() {
        // memberKind accepts method, field and type, compared case-insensitively; any
        // other value is refused rather than answered with empty member lists.
        assertPayload(args("com.example.Calculator", null, "unknown"), Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'memberKind': "
                + "Must be one of method, field, type (case-insensitive); got: unknown")));
    }

    @Test
    @DisplayName("Method info: Calculator.add is add(int a, int b): int, exactly public, at its Javadoc line")
    void methodInfo_includesSignatureAndModifiers() {
        assertPayload(args("com.example.Calculator", null, null), calculatorAll());
    }

    @Test
    @DisplayName("Field info: Calculator.lastResult has type int and exactly the modifier private")
    void fieldInfo_includesTypeAndModifiers() {
        assertPayload(args("com.example.Calculator", null, null), calculatorAll());
    }

    @Test
    @DisplayName("Nested type info: SearchPatterns' only member type is InnerClass, kind class, public static, at its Javadoc line")
    void nestedTypeInfo_appearsWithKind() {
        assertPayload(args("com.example.SearchPatterns", null, "type"), payload(
            typeBlock("SearchPatterns", "com.example.SearchPatterns", MAIN + "SearchPatterns.java"),
            Map.of("nestedTypes", List.of(nested("InnerClass", "com.example.SearchPatterns$InnerClass", "class",
                List.of("public", "static"), 214))), 1));
    }

    @Test
    @DisplayName("Generic-class member signatures preserve type-variable references")
    void genericClass_memberSignatures_preserveTypeVariables() {
        // GenericClass<T>: field `private T value;`, `public T read()` and `public void set(T v)`.
        Map<String, Object> lists = new LinkedHashMap<>();
        lists.put("methods", List.of(
            member("read", "method", List.of("public"), "signature", "read(): T", 11),
            member("set", "method", List.of("public"), "signature", "set(T v): void", 15)));
        lists.put("fields", List.of(member("value", "field", List.of("private"), "type", "T", 9)));
        lists.put("nestedTypes", List.of());
        assertPayload(args("com.example.genericunused.GenericClass", null, null), payload(
            typeBlock("GenericClass", "com.example.genericunused.GenericClass", MAIN + "genericunused/GenericClass.java"),
            lists, 3));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator has exactly its 4 declared methods and 1 field")
    void envelope_calculator_exactMembers() {
        assertPayload(args("com.example.Calculator", null, null), calculatorAll());
    }

    private ObjectNode args(String typeName, Boolean includeInherited, String memberKind) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", typeName);
        if (includeInherited != null) args.put("includeInherited", includeInherited);
        if (memberKind != null) args.put("memberKind", memberKind);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = json(expected);
        JsonNode direct = json(tool.execute(args.deepCopy()));
        JsonNode wire = envelope.assertEnvelopeFidelity("get_type_members", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private JsonNode json(Object value) {
        return objectMapper.valueToTree(value);
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    /** The entries of {@code members} with the given name, in order. */
    private static List<JsonNode> named(List<JsonNode> members, String name) {
        List<JsonNode> named = new ArrayList<>();
        for (JsonNode member : members) {
            if (name.equals(member.path("name").asText())) {
                named.add(member);
            }
        }
        return named;
    }

    private static Set<String> declaredIn(List<JsonNode> members) {
        Set<String> declaredIn = new TreeSet<>();
        for (JsonNode member : members) {
            declaredIn.add(member.path("declaredIn").asText());
        }
        return declaredIn;
    }

    private static Map<String, Object> calculatorAll() {
        Map<String, Object> lists = new LinkedHashMap<>();
        lists.put("methods", CALCULATOR_METHODS);
        lists.put("fields", CALCULATOR_FIELDS);
        lists.put("nestedTypes", List.of());
        return payload(CALCULATOR_TYPE, lists, 5);
    }

    private static Map<String, Object> filledCircleType() {
        return typeBlock("FilledCircle", "com.example.FilledCircle", MAIN + "FilledCircle.java");
    }

    /** A success whose data holds the type block, the given member lists and their total. */
    private static Map<String, Object> payload(Map<String, Object> type, Map<String, Object> lists, int total) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.putAll(lists);
        data.put("totalMembers", total);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", total);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> typeBlock(String name, String qualifiedName, String filePath) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", name);
        type.put("qualifiedName", qualifiedName);
        type.put("kind", "class");
        type.put("filePath", filePath);
        return type;
    }

    /** A declared field (with its type) or method (with its signature). */
    private static Map<String, Object> member(String name, String kind, List<String> modifiers,
                                              String detailKey, String detail, int line) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", name);
        member.put("kind", kind);
        member.put("modifiers", modifiers);
        member.put(detailKey, detail);
        member.put("line", line);
        return member;
    }

    /** An inherited method: declaredIn set, and a line only when its declaring type has source. */
    private static Map<String, Object> inherited(String name, List<String> modifiers, String signature,
                                                 String declaredIn, Integer line) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", name);
        member.put("kind", "method");
        member.put("modifiers", modifiers);
        member.put("signature", signature);
        member.put("declaredIn", declaredIn);
        if (line != null) {
            member.put("line", line);
        }
        return member;
    }

    private static Map<String, Object> nested(String name, String qualifiedName, String kind,
                                              List<String> modifiers, int line) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", name);
        type.put("qualifiedName", qualifiedName);
        type.put("kind", kind);
        type.put("modifiers", modifiers);
        type.put("line", line);
        return type;
    }
}
