package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.CLIENT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PACKAGE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.PROBE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STORE;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.STRAY;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * A tool's limit on the results it lists bounds the potential matches it lists beside them, while
 * their count stays exact: maxResults for the call hierarchy, implementations, reflection calls,
 * symbols and the framework tools, maxCallers for analyze_method's callers. The potential matches
 * are no results, so meta counts the results alone. The fixture is potential-matches
 * ({@link PotentialMatches}).
 */
class PotentialCutTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming of Store.fill with maxResults 1 lists one caller of 3 and one potential match of 2")
    void incomingCallsCut() {
        Map<String, Object> data = map(
            "method", "fill",
            "declaringClass", PACKAGE + "Store",
            "signature", "fill(int)",
            "totalCallers", 3,
            "callers", List.of(map("filePath", CLIENT, "line", 14, "column", 14, "context", "store.fill(1);",
                "callerMethod", "use", "callerSignature", "use(Store, Object)", "callerClass", PACKAGE + "Client")),
            "potentialMatches", potential(2, List.of(at(STRAY, 14, 15, 7, "ledger.fill(3);"))));
        ObjectNode args = position(STORE, 6, 16);
        args.put("maxResults", 1);
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, args,
            success(data, map("totalCount", 3, "returnedCount", 1, "truncated", true, "suggestedNextTools", List.of(
                "get_call_hierarchy_outgoing to see what this method calls",
                "go_to_definition to navigate to a caller"))), withoutOffsets());
    }

    @Test
    @DisplayName("analyze_method of Store.fill with maxCallers 1 lists its one caller and one potential match of 2; meta counts the caller and the callee")
    void methodAnalysisCallersCut() {
        Map<String, Object> data = map(
            "method", map("name", "fill", "constructor", false, "modifiers", List.of("public"),
                "declaringType", PACKAGE + "Store", "signature", "fill(int amount): void", "returnType", "void",
                "file", STORE, "line", 6),
            "parameters", List.of(map("type", "int", "name", "amount")),
            "callers", map(
                "count", 1,
                "list", List.of(map("method", "use", "qualifiedType", PACKAGE + "Client", "file", CLIENT,
                    "callLine", 14)),
                "potentialMatches", potential(2, List.of(at(STRAY, 14, 15, 7, "ledger.fill(3);")))),
            "callees", map(
                "count", 1,
                "list", List.of(map("name", "max", "kind", "METHOD", "qualifiedType", "java.lang.Math",
                    "signature", "max(int, int)", "callLine", 7))),
            "overrides", map());
        ObjectNode args = position(STORE, 6, 16);
        args.put("maxCallers", 1);
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, args,
            success(data, map("totalCount", 2, "returnedCount", 2, "truncated", false, "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"))), withoutOffsets());
    }

    @Test
    @DisplayName("find_implementations of Probe with maxResults 0 lists no implementation of 1 and no potential match of 1")
    void implementationsCut() {
        Map<String, Object> data = map(
            "symbol", "Probe",
            "isInterface", true,
            "totalImplementations", 1,
            "implementations", List.of(),
            "potentialMatches", potential(1, List.of()));
        ObjectNode args = position(PROBE, 2, 17);
        args.put("maxResults", 0);
        assertPayload(mapper, new FindImplementationsTool(() -> service), envelope, args,
            success(data, map("totalCount", 1, "returnedCount", 0, "truncated", true, "suggestedNextTools", List.of(
                "get_type_hierarchy for full inheritance chain",
                "find_references to see all usages",
                "get_type_members to see members of an implementation"))));
    }

    @Test
    @DisplayName("find_reflection_usage with maxResults 0 lists no call of 1 and no potential call of 1")
    void reflectionCut() {
        Map<String, Object> data = map(
            "totalCalls", 1,
            "summary", map("Field.get", 1),
            "reflectionCalls", List.of(),
            "potentialMatches", potential(1, List.of()));
        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 0);
        assertPayload(mapper, new FindReflectionUsageTool(() -> service), envelope, args,
            success(data, map("totalCount", 1, "returnedCount", 0, "truncated", true, "suggestedNextTools", List.of(
                "analyze_change_impact to assess risk of renaming reflected types",
                "get_symbol_info at a reflection call site for context"))));
    }

    @Test
    @DisplayName("search_symbols Client.use with maxResults 0 lists no declaration of 1 and no potential match of 1")
    void symbolsCut() {
        Map<String, Object> data = map(
            "query", "Client.use",
            "kind", "method",
            "results", List.of(),
            "pagination", map("offset", 0, "returned", 0, "hasMore", true),
            "potentialMatches", potential(1, List.of()));
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "Client.use");
        args.put("kind", "method");
        args.put("maxResults", 0);
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, args,
            success(data, map("totalCount", 1, "returnedCount", 0, "truncated", true, "suggestedNextTools", List.of(
                "get_symbol_info at a result location for detailed info",
                "get_type_members for type results",
                "find_references to see all usages"))));
    }

    @Test
    @DisplayName("The framework tools with maxResults 0 list no potential match of 1")
    void frameworkCut() {
        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 0);
        assertPayload(mapper, new GetDiRegistrationsTool(() -> service), envelope, args, success(map(
                "summary", map("components", 0, "configurations", 0, "beans", 0, "injectionPoints", 0),
                "components", List.of(),
                "configurations", List.of(),
                "beans", List.of(),
                "injectionPoints", List.of(),
                "potentialMatches", potential(1, List.of())),
            map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "find_references to trace a specific dependency",
                "analyze_type for details on a registered component"))));
        assertPayload(mapper, new GetHttpEndpointsTool(() -> service), envelope, args, success(map(
                "endpointCount", 0,
                "endpoints", List.of(),
                "potentialMatches", potential(1, List.of())),
            map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"))));
        assertPayload(mapper, new GetJpaModelTool(() -> service), envelope, args, success(map(
                "entityCount", 0,
                "entities", List.of(),
                "potentialMatches", potential(1, List.of())),
            map("totalCount", 0, "returnedCount", 0, "truncated", false, "suggestedNextTools", List.of(
                "analyze_type for full details of an entity",
                "find_references to see where an entity is used"))));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(PotentialMatches.FIXTURE).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
