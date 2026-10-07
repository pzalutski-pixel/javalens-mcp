package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * A member type is found by the name every tool reports it by, its fully qualified name with '$'
 * between nested types ({@code com.example.gen.Desk$Voucher}), as by its dotted name: one tool's
 * answer works as another tool's typeName. A '$' name that a member type and a local class both
 * carry names the member type. A '$' name that names no member type is not found.
 *
 * <p>Desk.java (codegen-names) declares the public static member class {@code Voucher} (line 6)
 * with the field {@code Stamp stamp} (line 8) and the method {@code public int value(int base)}
 * (line 10); no type extends it, and Desk.java compiles without a warning.
 */
class MemberTypeNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String VOUCHER = "com.example.gen.Desk$Voucher";
    private static final String DESK = CodegenNames.GEN + "Desk.java";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(CodegenNames.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_type_members of com.example.gen.Desk$Voucher lists the field stamp and the method value")
    void typeMembersByDollarName() {
        Map<String, Object> data = map(
            "type", map("name", "Voucher", "qualifiedName", VOUCHER, "kind", "class", "filePath", DESK),
            "methods", List.of(map("name", "value", "kind", "method", "modifiers", List.of("public"),
                "signature", "value(int base): int", "line", 10)),
            "fields", List.of(map("name", "stamp", "kind", "field", "modifiers", List.of(), "type", "Stamp", "line", 8)),
            "nestedTypes", List.of(),
            "totalMembers", 2);
        Map<String, Object> meta = map(
            "totalCount", 2,
            "returnedCount", 2,
            "suggestedNextTools", List.of(
                "find_references to find usages of a member",
                "go_to_definition to navigate to member source",
                "get_type_hierarchy to see inheritance chain"));
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, typeName(VOUCHER), success(data, meta));
    }

    @Test
    @DisplayName("analyze_type of com.example.gen.Desk$Voucher analyzes the member class Voucher")
    void analyzeTypeByDollarName() {
        ObjectNode args = typeName(VOUCHER);
        args.put("includeUsages", false);
        Map<String, Object> data = map(
            "type", map(
                "name", "Voucher",
                "qualifiedName", VOUCHER,
                "package", "com.example.gen",
                "kind", "class",
                "modifiers", List.of("public", "static"),
                "file", DESK,
                "line", 6),
            "members", map(
                "constructors", List.of(),
                "methods", List.of(map("name", "value", "modifiers", List.of("public"),
                    "signature", "value(int base): int", "returnType", "int", "line", 10)),
                "fields", List.of(map("name", "stamp", "type", "Stamp", "modifiers", List.of(), "line", 8)),
                "nestedTypes", List.of(),
                "constructorCount", 0,
                "methodCount", 1,
                "fieldCount", 1,
                "nestedTypeCount", 0),
            "hierarchy", map(),
            "diagnostics", map("errorCount", 0, "warningCount", 0, "hasProblems", false));
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "analyze_method for detailed method analysis",
            "find_references for specific symbol usages",
            "get_call_hierarchy_incoming for method callers"));
        assertPayload(mapper, new AnalyzeTypeTool(() -> service), envelope, args, success(data, meta));
    }

    @Test
    @DisplayName("get_type_members of com.example.semantics.Shadow$Local finds the member class Local, not the local class Local of probe() that the model names alike")
    void memberTypeOwnsItsDollarName() throws Exception {
        // Shadow.java (unnamed-semantics) declares the static member class Local (line 4), its
        // depth() at line 5, and in probe() a local class Local whose depth() is at line 12; the
        // Java model names both com.example.semantics.Shadow$Local.
        JdtServiceImpl semantics = helper.loadProject("unnamed-semantics");
        String local = "com.example.semantics.Shadow$Local";
        Map<String, Object> data = map(
            "type", map("name", "Local", "qualifiedName", local, "kind", "class",
                "filePath", "src/main/java/com/example/semantics/Shadow.java"),
            "methods", List.of(map("name", "depth", "kind", "method", "modifiers", List.of(),
                "signature", "depth(): int", "line", 5)),
            "fields", List.of(),
            "nestedTypes", List.of(),
            "totalMembers", 1);
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "suggestedNextTools", List.of(
                "find_references to find usages of a member",
                "go_to_definition to navigate to member source",
                "get_type_hierarchy to see inheritance chain"));
        assertPayload(mapper, new GetTypeMembersTool(() -> semantics), new EnvelopeHarness(semantics), typeName(local),
            success(data, meta));
    }

    @Test
    @DisplayName("get_type_members of com.example.gen.Desk$Missing is SYMBOL_NOT_FOUND: Desk declares no member type Missing")
    void unknownMemberTypeNotFound() {
        String missing = "com.example.gen.Desk$Missing";
        assertPayload(mapper, new GetTypeMembersTool(() -> service), envelope, typeName(missing),
            map("success", false, "error", map(
                "code", "SYMBOL_NOT_FOUND",
                "message", "Symbol not found: Type not found: " + missing,
                "hint", "Use search_symbols to find available symbols")));
    }

    private ObjectNode typeName(String name) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", name);
        return args;
    }
}
