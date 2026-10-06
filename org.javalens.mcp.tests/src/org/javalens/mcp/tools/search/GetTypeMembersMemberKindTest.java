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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_type_members' {@code memberKind} filter on the symbol-kinds fixture's Catalog.java.
 * memberKind accepts method, field and type, compared case-insensitively; {@code field}
 * returns every field the type declares, static final constants and enum constants included,
 * each with its finer kind; any other value is an INVALID_PARAMETER naming the accepted values.
 * Every expectation is the whole payload, asserted through execute() and through the JSON-RPC
 * envelope.
 */
class GetTypeMembersMemberKindTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CATALOG_FILE = "src/main/java/org/example/kinds/Catalog.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "find_references to find usages of a member",
        "go_to_definition to navigate to member source",
        "get_type_hierarchy to see inheritance chain");

    private static final Map<String, Object> CATALOG = typeBlock("Catalog", "org.example.kinds.Catalog", "class");
    private static final Map<String, Object> SHADE = typeBlock("Shade", "org.example.kinds.Catalog$Shade", "enum");

    /** Catalog's four fields in source order: instance, static, static final, final instance. */
    private static final List<Map<String, Object>> CATALOG_FIELDS = List.of(
        member("count", "field", List.of("private"), "type", "int", 10),
        member("created", "field", List.of("static"), "type", "int", 12),
        member("LABEL", "constant", List.of("public", "static", "final"), "type", "String", 14),
        member("width", "field", List.of("final"), "type", "int", 16));

    /** Shade's two enum constants (no declared modifiers) and its instance field. */
    private static final List<Map<String, Object>> SHADE_FIELDS = List.of(
        member("LIGHT", "enumConstant", List.of(), "type", "Shade", 42),
        member("DARK", "enumConstant", List.of(), "type", "Shade", 43),
        member("depth", "field", List.of("private"), "type", "int", 45));

    /** Catalog's constructors and methods in source order; the lambdas inside test(String) are not members. */
    private static final List<Map<String, Object>> CATALOG_METHODS = List.of(
        member("Catalog", "constructor", List.of("public"), "signature", "Catalog()", 18),
        member("Catalog", "constructor", List.of("public"), "signature", "Catalog(int count)", 21),
        member("count", "method", List.of("public"), "signature", "count(): int", 25),
        member("register", "method", List.of("static"), "signature", "register(String name): void", 29),
        member("test", "method", List.of(), "signature", "test(String s): boolean", 60));

    /** Catalog's member types in source order, with their declared modifiers. */
    private static final List<Map<String, Object>> CATALOG_TYPES = List.of(
        nested("Note", "class", List.of("static"), 34),
        nested("Shelf", "interface", List.of("public"), 37),
        nested("Shade", "enum", List.of("public"), 41),
        nested("Item", "record", List.of("public"), 48),
        nested("Tag", "annotation", List.of("public"), 57));

    private static final String ACCEPTED = "Must be one of method, field, type (case-insensitive); got: ";

    private final ObjectMapper mapper = new ObjectMapper();
    private GetTypeMembersTool tool;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("symbol-kinds");
        tool = new GetTypeMembersTool(() -> service);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("memberKind=field returns every field of Catalog: instance, static, static final constant and final instance")
    void fieldKind_returnsEveryFieldIncludingConstants() {
        assertPayload(members("org.example.kinds.Catalog", "field"), payload(CATALOG, "fields", CATALOG_FIELDS));
    }

    @Test
    @DisplayName("memberKind=field on an enum returns its enum constants and its instance field")
    void fieldKind_onEnum_returnsEnumConstantsAndField() {
        assertPayload(members("org.example.kinds.Catalog.Shade", "field"), payload(SHADE, "fields", SHADE_FIELDS));
    }

    @Test
    @DisplayName("memberKind=method returns Catalog's constructors and methods only")
    void methodKind_returnsConstructorsAndMethods() {
        assertPayload(members("org.example.kinds.Catalog", "method"), payload(CATALOG, "methods", CATALOG_METHODS));
    }

    @Test
    @DisplayName("memberKind=type returns Catalog's member types only")
    void typeKind_returnsMemberTypes() {
        assertPayload(members("org.example.kinds.Catalog", "type"), payload(CATALOG, "nestedTypes", CATALOG_TYPES));
    }

    @Test
    @DisplayName("without memberKind every member list is returned")
    void noKind_returnsEveryMemberList() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "org.example.kinds.Catalog");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", CATALOG);
        data.put("methods", CATALOG_METHODS);
        data.put("fields", CATALOG_FIELDS);
        data.put("nestedTypes", CATALOG_TYPES);
        data.put("totalMembers", 14);
        assertPayload(args, success(data, 14));
    }

    @Test
    @DisplayName("memberKind is compared case-insensitively")
    void memberKind_isCaseInsensitive() {
        assertAll(
            () -> assertPayload(members("org.example.kinds.Catalog", "FIELD"), payload(CATALOG, "fields", CATALOG_FIELDS)),
            () -> assertPayload(members("org.example.kinds.Catalog.Shade", "Field"), payload(SHADE, "fields", SHADE_FIELDS)),
            () -> assertPayload(members("org.example.kinds.Catalog", "Method"), payload(CATALOG, "methods", CATALOG_METHODS)),
            () -> assertPayload(members("org.example.kinds.Catalog", "TYPE"), payload(CATALOG, "nestedTypes", CATALOG_TYPES)));
    }

    @ParameterizedTest(name = "memberKind=\"{0}\"")
    @ValueSource(strings = {"fields", "methods", "constant", "constructor", "all", "foo", ""})
    @DisplayName("an unrecognized memberKind is INVALID_PARAMETER naming the accepted values")
    void unrecognizedMemberKind_isInvalidParameter(String memberKind) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAMETER");
        error.put("message", "Invalid parameter 'memberKind': " + ACCEPTED + memberKind);
        assertPayload(members("org.example.kinds.Catalog", memberKind), Map.of("success", false, "error", error));
    }

    private ObjectNode members(String typeName, String memberKind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", typeName);
        args.put("memberKind", memberKind);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct = mapper.valueToTree(tool.execute(args.deepCopy()));
        JsonNode wire = envelope.assertEnvelopeFidelity("get_type_members", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** A success whose data holds the type block and one member list. */
    private static Map<String, Object> payload(Map<String, Object> type, String list, List<Map<String, Object>> members) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put(list, members);
        data.put("totalMembers", members.size());
        return success(data, members.size());
    }

    private static Map<String, Object> success(Map<String, Object> data, int total) {
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

    private static Map<String, Object> typeBlock(String name, String qualifiedName, String kind) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", name);
        type.put("qualifiedName", qualifiedName);
        type.put("kind", kind);
        type.put("filePath", CATALOG_FILE);
        return type;
    }

    /** A field (with its type) or a method (with its signature): name, kind, modifiers, then the detail and the line. */
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

    private static Map<String, Object> nested(String name, String kind, List<String> modifiers, int line) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", name);
        type.put("qualifiedName", "org.example.kinds.Catalog$" + name);
        type.put("kind", kind);
        type.put("modifiers", modifiers);
        type.put("line", line);
        return type;
    }
}
