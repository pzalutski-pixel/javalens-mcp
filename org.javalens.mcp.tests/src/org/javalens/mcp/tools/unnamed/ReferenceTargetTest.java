package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * A position names the symbol written there. At a call of a method of a class file the symbol is
 * that method, and its references are the project's own: the sources of the JDK hold their own
 * calls of it, which are no references in the project. At a name the compiler cannot resolve
 * there is no symbol: a tool refuses rather than answer for the member enclosing the position.
 * A position on no name, such as the indentation of a statement, still belongs to the member
 * enclosing it. The fixture is potential-matches ({@link PotentialMatches}): Client calls
 * IntConsumer.accept at line 17, Stray calls fill on a Ledger, a type the compiler cannot find, at
 * line 14.
 */
class ReferenceTargetTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> REFERENCES_NEXT_TOOLS = List.of(
        "go_to_definition to see the symbol definition",
        "get_type_hierarchy for type symbols");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("find_references at filler.accept(5) answers for IntConsumer.accept with the project's one reference")
    void classFileMethodReferences() {
        Map<String, Object> data = map(
            "symbol", "accept",
            "symbolKind", "method",
            "containingType", "java.util.function.IntConsumer",
            "totalCount", 1,
            "locations", List.of(map("filePath", CLIENT, "line", 17, "column", 15, "context", "filler.accept(5);",
                "referenceKind", "METHOD_INVOCATION")));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(CLIENT, 17, 15),
            success(data, map("totalCount", 1, "returnedCount", 1, "truncated", false,
                "suggestedNextTools", REFERENCES_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming at filler.accept(5) lists Client.use, IntConsumer.accept's one caller in the project")
    void classFileMethodCallers() {
        Map<String, Object> data = map(
            "method", "accept",
            "declaringClass", "java.util.function.IntConsumer",
            "signature", "accept(int)",
            "totalCallers", 1,
            "callers", List.of(map("filePath", CLIENT, "line", 17, "column", 15, "context", "filler.accept(5);",
                "callerMethod", "use", "callerSignature", "use(Store, Object)", "callerClass", PACKAGE + "Client")));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, position(CLIENT, 17, 15),
            success(data, map("totalCount", 1, "returnedCount", 1, "truncated", false,
                "suggestedNextTools", List.of(
                    "get_call_hierarchy_outgoing to see what this method calls",
                    "go_to_definition to navigate to a caller"))));
    }

    @Test
    @DisplayName("find_references at ledger.fill(3), a name that does not resolve, refuses with SYMBOL_NOT_FOUND")
    void unresolvedNameReferences() {
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(STRAY, 14, 15),
            symbolNotFound("No symbol found at position"));
    }

    @Test
    @DisplayName("rename_symbol at ledger.fill(3), a name that does not resolve, refuses with SYMBOL_NOT_FOUND and renames nothing")
    void unresolvedNameRename() {
        ObjectNode args = position(STRAY, 14, 15);
        args.put("newName", "pour");
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, args,
            symbolNotFound("No symbol at position"));
    }

    @Test
    @DisplayName("find_references at the indentation of store.fill(1), on no name, answers for Client.use, the method enclosing it, which nothing references")
    void positionOnNoName() {
        Map<String, Object> data = map(
            "symbol", "use",
            "symbolKind", "method",
            "containingType", PACKAGE + "Client",
            "totalCount", 0,
            "locations", List.of());
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope, position(CLIENT, 14, 4),
            success(data, map("totalCount", 0, "returnedCount", 0, "truncated", false,
                "suggestedNextTools", REFERENCES_NEXT_TOOLS)));
    }

    private static Map<String, Object> symbolNotFound(String message) {
        return map("success", false, "error", map(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: " + message,
            "hint", "Use search_symbols to find available symbols"));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
