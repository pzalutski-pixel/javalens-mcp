package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
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
 * An anonymous class is numbered among the anonymous classes of its enclosing named type in
 * source order, whatever member, sibling or lambda body it is written in, and every tool names
 * it by that number: from a position, from a call site found by search, and from a declaration
 * found by search.
 *
 * <p>Events.java ends with {@code weigh(String text)} (line 94, name at column 15), which
 * returns {@code text.length()} (line 95), and two methods whose anonymous classes call it:
 * {@code pair()} declares two, a Comparable whose {@code compareTo(String other)} (line 100,
 * column 23) returns {@code weigh(other)} (line 101, column 23) and a Supplier whose
 * {@code get()} (line 105, column 27) returns {@code weigh("second")} (line 106, column 23);
 * {@code deferred()} declares a lambda whose body declares an Object subclass whose
 * {@code toString()} (line 114, column 26) returns {@code "deferred" + weigh("deferred")} (line
 * 115, column 36), then directly an Object subclass whose {@code hashCode()} (line 119, column
 * 23) returns {@code weigh("now")} (line 120, column 23). The anonymous classes before them are
 * listener()'s, names()'s and sink()'s, so these are Events' fourth to seventh:
 * {@code Events$4} and {@code Events$5}, the two in pair(), {@code Events$6}, the one in the
 * lambda, and {@code Events$7}, the one after the lambda. None declares a field.
 */
class AnonymousNumberingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String ANONYMOUS_4 = EVENTS_TYPE + "$4";
    private static final String ANONYMOUS_5 = EVENTS_TYPE + "$5";
    private static final String ANONYMOUS_6 = EVENTS_TYPE + "$6";
    private static final String ANONYMOUS_7 = EVENTS_TYPE + "$7";
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
    @DisplayName("get_type_at_position at compareTo names the first anonymous class of pair() Events$4")
    void typeAtPosition_firstSibling() {
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope, position(100, 23),
            success(anonymousType("Events$4", ANONYMOUS_4, "Comparable<String>", 99), typeAtPositionMeta()));
    }

    @Test
    @DisplayName("get_type_at_position at get names the second anonymous class of pair() Events$5")
    void typeAtPosition_secondSibling() {
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope, position(105, 27),
            success(anonymousType("Events$5", ANONYMOUS_5, "Supplier<Integer>", 104), typeAtPositionMeta()));
    }

    @Test
    @DisplayName("get_type_at_position at toString names the anonymous class in the lambda of deferred() Events$6")
    void typeAtPosition_inLambda() {
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope, position(114, 26),
            success(anonymousType("Events$6", ANONYMOUS_6, "Object", 113), typeAtPositionMeta()));
    }

    @Test
    @DisplayName("get_type_at_position at hashCode names the anonymous class after the lambda of deferred() Events$7")
    void typeAtPosition_afterLambda() {
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope, position(119, 23),
            success(anonymousType("Events$7", ANONYMOUS_7, "Object", 118), typeAtPositionMeta()));
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming on weigh names each calling anonymous class by its number: Events$4 to Events$7")
    void incomingCalls() {
        // Search order: pair()'s classes, then deferred()'s in source order, the lambda's first.
        Map<String, Object> data = map(
            "method", "weigh",
            "declaringClass", EVENTS_TYPE,
            "signature", "weigh(String)",
            "totalCallers", 4,
            "callers", List.of(
                caller(101, 23, "return weigh(other);", "compareTo", "compareTo(String)", ANONYMOUS_4),
                caller(106, 23, "return weigh(\"second\");", "get", "get()", ANONYMOUS_5),
                caller(115, 36, "return \"deferred\" + weigh(\"deferred\");", "toString", "toString()",
                    ANONYMOUS_6),
                caller(120, 23, "return weigh(\"now\");", "hashCode", "hashCode()", ANONYMOUS_7)));
        Map<String, Object> meta = map(
            "totalCount", 4,
            "returnedCount", 4,
            "truncated", false,
            "suggestedNextTools", List.of(
                "get_call_hierarchy_outgoing to see what this method calls",
                "go_to_definition to navigate to a caller"));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, position(94, 15),
            success(data, meta));
    }

    @Test
    @DisplayName("analyze_method on weigh names each caller's anonymous class by its number: Events$4 to Events$7")
    void analyzeMethodCallers() {
        Map<String, Object> data = map(
            "method", map(
                "name", "weigh",
                "constructor", false,
                "modifiers", List.of("static"),
                "declaringType", EVENTS_TYPE,
                "signature", "weigh(String text): int",
                "returnType", "int",
                "file", EVENTS,
                "line", 94),
            "parameters", List.of(map("type", "String", "name", "text")),
            "callers", map("count", 4, "list", List.of(
                map("method", "compareTo", "qualifiedType", ANONYMOUS_4, "file", EVENTS, "callLine", 101),
                map("method", "get", "qualifiedType", ANONYMOUS_5, "file", EVENTS, "callLine", 106),
                map("method", "toString", "qualifiedType", ANONYMOUS_6, "file", EVENTS, "callLine", 115),
                map("method", "hashCode", "qualifiedType", ANONYMOUS_7, "file", EVENTS, "callLine", 120))),
            "callees", map("count", 1, "list", List.of(map(
                "name", "length",
                "kind", "METHOD",
                "qualifiedType", "java.lang.String",
                "signature", "length()",
                "callLine", 95))),
            "overrides", map());
        // Four callers and one callee, all listed.
        Map<String, Object> meta = map("totalCount", 5, "returnedCount", 5, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_type for declaring type analysis",
                "find_references for all usages",
                "go_to_definition to navigate to a callee"));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(94, 15),
            success(data, meta));
    }

    @Test
    @DisplayName("search_symbols(compareTo, kind=method) names its container Events$4")
    void searchSymbols_firstSibling() {
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, search("compareTo"),
            page("compareTo", method("compareTo", 100, 23, "compareTo(String other)", ANONYMOUS_4)));
    }

    @Test
    @DisplayName("search_symbols(get, kind=method) names its container Events$5 and returns no lambda")
    void searchSymbols_secondSibling() {
        // The lambdas of later() and deferred() implement Supplier.get too; a lambda is no method.
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, search("get"),
            page("get", method("get", 105, 27, "get()", ANONYMOUS_5)));
    }

    @Test
    @DisplayName("search_symbols(toString, kind=method) names its container Events$6")
    void searchSymbols_inLambda() {
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, search("toString"),
            page("toString", method("toString", 114, 26, "toString()", ANONYMOUS_6)));
    }

    @Test
    @DisplayName("search_symbols(hashCode, kind=method) names its container Events$7")
    void searchSymbols_afterLambda() {
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, search("hashCode"),
            page("hashCode", method("hashCode", 119, 23, "hashCode()", ANONYMOUS_7)));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private ObjectNode search(String query) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        args.put("kind", "method");
        return args;
    }

    /** An anonymous class of Events with one method and no field, declared at {@code line}. */
    private static Map<String, Object> anonymousType(String name, String qualifiedName, String superclass,
                                                     int line) {
        return map(
            "name", name,
            "qualifiedName", qualifiedName,
            "kind", "class",
            "modifiers", List.of(),
            "superclass", superclass,
            "declaringType", EVENTS_TYPE,
            "isNested", true,
            "isAnonymous", true,
            "isLocal", true,
            "filePath", EVENTS,
            "line", line,
            "methodCount", 1,
            "fieldCount", 0,
            "nestedTypeCount", 0);
    }

    private static Map<String, Object> typeAtPositionMeta() {
        return map("suggestedNextTools", List.of(
            "get_type_hierarchy for inheritance chain",
            "get_type_members for all members",
            "find_implementations for implementors"));
    }

    private static Map<String, Object> caller(int line, int column, String context, String method,
                                              String signature, String callerClass) {
        return map("filePath", EVENTS, "line", line, "column", column, "context", context,
            "callerMethod", method, "callerSignature", signature, "callerClass", callerClass);
    }

    private static Map<String, Object> method(String name, int line, int column, String signature,
                                              String containingType) {
        return map("name", name, "kind", "method", "filePath", EVENTS, "line", line, "column", column,
            "signature", signature, "containingType", containingType);
    }

    private static Map<String, Object> page(String query, Map<String, Object> result) {
        Map<String, Object> data = map(
            "query", query,
            "kind", "method",
            "results", List.of(result),
            "pagination", map("offset", 0, "returned", 1, "hasMore", false));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", SEARCH_NEXT_TOOLS);
        return success(data, meta);
    }
}
