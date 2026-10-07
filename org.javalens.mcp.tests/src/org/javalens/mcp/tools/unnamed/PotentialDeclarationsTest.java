package org.javalens.mcp.tools.unnamed;

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

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * search_symbols lists the declarations JDT's search confirms. A query naming the declaring type
 * makes the search resolve each declaration of the name (MethodPattern.mustResolve) and match it
 * when its declaring type is the type named or a subtype of it (MethodLocator.resolveLevel(Binding)
 * :789-796, through resolveLevelAsSubtype :895-945). Stray implements Probe, which does not
 * resolve in its package, so the search cannot rule out that Stray is a subtype of Client:
 * resolveLevelAsSubtype reaches Stray's missing interface, whose binding is invalid
 * (PatternLocator.resolveLevelForType :825-827), and Stray's use is a potential match of
 * Client.use. Stray.use names Stray itself and matches it. A query by name alone resolves nothing
 * and every declaration of that name matches (MethodLocator.match(MethodDeclaration) :255). The
 * fixture is potential-matches ({@link PotentialMatches}).
 */
class PotentialDeclarationsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    private static final Map<String, Object> CLIENT_USE = map("name", "use", "kind", "method",
        "filePath", CLIENT, "line", 13, "column", 9, "signature", "use(Store store, Object item)",
        "containingType", PACKAGE + "Client");

    private static final Map<String, Object> STRAY_USE_RESULT = map("name", "use", "kind", "method",
        "filePath", STRAY, "line", 13, "column", 9, "signature", "use(Ledger ledger, Object item)",
        "containingType", "com.example.stray.Stray");

    private static final Map<String, Object> STRAY_USE =
        at(STRAY, 13, 9, 3, "void use(Ledger ledger, Object item) throws Fault {");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("search_symbols Client.use finds Client's use; Stray's use, in a class whose interface does not resolve and so may be a subtype of Client, is a potential match")
    void qualifiedQueryWithPotentialSubtype() {
        Map<String, Object> data = map(
            "query", "Client.use",
            "kind", "method",
            "results", List.of(CLIENT_USE),
            "pagination", map("offset", 0, "returned", 1, "hasMore", false),
            "potentialMatches", potential(1, List.of(STRAY_USE)));
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, query("Client.use"),
            success(data, meta(1, 1)), withoutOffsets());
    }

    @Test
    @DisplayName("search_symbols Stray.use finds Stray's use, declared in the type the query names, with no potential match")
    void qualifiedQueryOfItsOwnType() {
        Map<String, Object> data = map(
            "query", "Stray.use",
            "kind", "method",
            "results", List.of(STRAY_USE_RESULT),
            "pagination", map("offset", 0, "returned", 1, "hasMore", false));
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, query("Stray.use"),
            success(data, meta(1, 1)), withoutOffsets());
    }

    @Test
    @DisplayName("search_symbols use, by name alone, finds both declarations with no potential match")
    void nameQuery() {
        Map<String, Object> data = map(
            "query", "use",
            "kind", "method",
            "results", List.of(CLIENT_USE, STRAY_USE_RESULT),
            "pagination", map("offset", 0, "returned", 2, "hasMore", false));
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, query("use"),
            success(data, meta(2, 2)), withoutOffsets());
    }

    private static Map<String, Object> meta(int total, int returned) {
        return map("totalCount", total, "returnedCount", returned, "truncated", false,
            "suggestedNextTools", NEXT_TOOLS);
    }

    private ObjectNode query(String query) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        args.put("kind", "method");
        return args;
    }
}
