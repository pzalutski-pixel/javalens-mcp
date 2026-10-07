package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.javalens.mcp.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS_TYPE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * A member's containing type is reported by its fully qualified name, whether the container
 * is a member class, a local class or an anonymous class: search_symbols and find_references
 * {@code containingType}, find_field_writes {@code declaringType}.
 */
class ContainingTypeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> SEARCH_NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String events;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedElements.FIXTURE);
        envelope = new EnvelopeHarness(service);
        events = helper.getFixturePath(UnnamedElements.FIXTURE).resolve(EVENTS).toString();
    }

    @Test
    @DisplayName("search_symbols names each accept method's container fully: anonymous class 3, the member classes Sink and Recorder")
    void searchSymbols_methodContainers() {
        // Search order: the members of Events' methods (anonymous class 3 in sink()) come
        // before Events' member classes, Sink then Recorder.
        List<Map<String, Object>> results = List.of(
            method("accept", 53, 17, "accept(String event)", EVENTS_TYPE + "$3"),
            method("accept", 21, 22, "accept(String event)", EVENTS_TYPE + "$Sink"),
            method("accept", 25, 13, "accept(String event)", EVENTS_TYPE + "$Recorder"));
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope,
            search("accept", "method"), page("accept", "method", results));
    }

    @Test
    @DisplayName("search_symbols names each field's container fully: Events, anonymous classes 1 and 2, the local class Counter")
    void searchSymbols_fieldContainers() {
        // Events' own fields first, then the fields of classes declared in its methods in
        // method order: listener() (anonymous class 1), names() (anonymous class 2), tally()
        // (local class Counter).
        List<Map<String, Object>> results = List.of(
            field("total", "field", 8, 15, "int", EVENTS_TYPE),
            field("RESET", "constant", 10, 26, "Runnable", EVENTS_TYPE),
            field("calls", "field", 32, 16, "int", EVENTS_TYPE + "$1"),
            field("serialVersionUID", "constant", 42, 38, "long", EVENTS_TYPE + "$2"),
            field("value", "field", 69, 16, "int", EVENTS_TYPE + "$Counter"));
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope,
            search("*", "field"), page("*", "field", results));
    }

    @Test
    @DisplayName("find_references on Counter.value names its local container fully and lists its three accesses")
    void findReferences_localContainer() {
        // References inside the local class come first (bump, merge), then the one in tally().
        Map<String, Object> data = map(
            "symbol", "value",
            "symbolKind", "field",
            "containingType", EVENTS_TYPE + "$Counter",
            "totalCount", 3,
            "locations", List.of(
                reference(72, 16, "value++;"),
                reference(76, 16, "value += others.length;"),
                reference(82, 23, "return counter.value;")));
        Map<String, Object> meta = map(
            "totalCount", 3,
            "returnedCount", 3,
            "truncated", false,
            "suggestedNextTools", List.of(
                "go_to_definition to see the symbol definition",
                "get_type_hierarchy for type symbols"));
        assertPayload(mapper, new FindReferencesTool(() -> service), envelope,
            position(69, 16), success(data, meta));
    }

    @Test
    @DisplayName("find_field_writes on calls names anonymous class 1 fully as its declaring type")
    void findFieldWrites_anonymousContainer() {
        Map<String, Object> data = map(
            "field", "calls",
            "declaringType", EVENTS_TYPE + "$1",
            "fieldType", "I",
            "totalWriteLocations", 1,
            "writeLocations", List.of(map(
                "filePath", EVENTS,
                "line", 35,
                "column", 16,
                "context", "calls++;",
                "accessType", "WRITE")));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "find_references to see all usages (reads and writes)",
                "get_call_hierarchy_incoming to find callers of methods that modify this field"));
        Tool tool = new FindFieldWritesTool(() -> service);
        assertPayload(mapper, tool, envelope, position(32, 16), success(data, meta));
    }

    private ObjectNode search(String query, String kind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        args.put("kind", kind);
        return args;
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> page(String query, String kind, List<Map<String, Object>> results) {
        Map<String, Object> data = map(
            "query", query,
            "kind", kind,
            "results", results,
            "pagination", map("offset", 0, "returned", results.size(), "hasMore", false));
        Map<String, Object> meta = map(
            "totalCount", results.size(),
            "returnedCount", results.size(),
            "truncated", false,
            "suggestedNextTools", SEARCH_NEXT_TOOLS);
        return success(data, meta);
    }

    private static Map<String, Object> method(String name, int line, int column, String signature,
                                              String containingType) {
        return map("name", name, "kind", "method", "filePath", EVENTS, "line", line, "column", column,
            "signature", signature, "containingType", containingType);
    }

    private static Map<String, Object> field(String name, String kind, int line, int column, String type,
                                             String containingType) {
        return map("name", name, "kind", kind, "filePath", EVENTS, "line", line, "column", column,
            "type", type, "containingType", containingType);
    }

    private static Map<String, Object> reference(int line, int column, String context) {
        return map("filePath", EVENTS, "line", line, "column", column, "context", context,
            "referenceKind", "FIELD_ACCESS");
    }
}
