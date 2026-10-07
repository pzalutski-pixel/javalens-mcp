package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STORE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * analyze_method analyzes the method a position names wherever that method is declared: reached
 * from a call in another file, its block reports the file and line of its declaration and its
 * callees are read from that declaration. A method of a class file reports no file, line or
 * callees, as get_super_method and get_method_at_position render a class-file method (no
 * compilation unit, so no filePath or line): there is no source to read them from. The fixture is
 * potential-matches ({@link PotentialMatches}): Client.use calls Store.fill at line 14, and
 * Client.measure calls String.length at line 37.
 */
class DeclaredMethodAnalysisTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("analyze_method at store.fill(1) in Client analyzes Store.fill as declared in Store.java at line 6, with its callee Math.max")
    void methodOfAnotherFile() {
        Map<String, Object> data = map(
            "method", map(
                "name", "fill",
                "constructor", false,
                "modifiers", List.of("public"),
                "declaringType", PACKAGE + "Store",
                "signature", "fill(int amount): void",
                "returnType", "void",
                "file", STORE,
                "line", 6),
            "parameters", List.of(map("type", "int", "name", "amount")),
            "callers", map(
                "count", 1,
                "list", List.of(map("method", "use", "qualifiedType", PACKAGE + "Client", "file", CLIENT,
                    "callLine", 14)),
                "potentialMatches", potential(2, List.of(
                    at(STRAY, 14, 15, 7, "ledger.fill(3);"),
                    at(STRAY, 17, 37, 4, "IntConsumer filler = ledger::fill;")))),
            "callees", map(
                "count", 1,
                "list", List.of(map("name", "max", "kind", "METHOD", "qualifiedType", "java.lang.Math",
                    "signature", "max(int, int)", "callLine", 7))),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(14, 14),
            success(data, map("totalCount", 2, "returnedCount", 2, "truncated", false,
                "suggestedNextTools", NEXT_TOOLS)), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_method at text.length() analyzes String.length, a class-file method: no file, line or callees; its one caller in the project is Client.measure; it overrides CharSequence.length")
    void methodOfClassFile() {
        Map<String, Object> data = map(
            "method", map(
                "name", "length",
                "constructor", false,
                "modifiers", List.of("public"),
                "declaringType", "java.lang.String",
                "signature", "length(): int",
                "returnType", "int"),
            "parameters", List.of(),
            "callers", map(
                "count", 1,
                "list", List.of(map("method", "measure", "qualifiedType", PACKAGE + "Client", "file", CLIENT,
                    "callLine", 37))),
            "overrides", map("overrides", map("method", "length", "qualifiedType", "java.lang.CharSequence")));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(37, 20),
            success(data, map("totalCount", 1, "returnedCount", 1, "truncated", false,
                "suggestedNextTools", NEXT_TOOLS)));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(CLIENT).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
