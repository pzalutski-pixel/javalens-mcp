package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
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
 * Tools that read callees from resolved bindings name each type the way the model-based tools
 * do: a declaring class by its fully qualified name without type arguments, an anonymous class
 * by its type-qualified name ({@code Events$1}) and a local class by its simple name, also as
 * a parameter type ({@code Counter[]}). The creation of an anonymous class is a call of the
 * superclass constructor its constructor runs, returning the anonymous class. A captured wildcard, which has no name, is named by the
 * wildcard it captures ({@code ? extends CharSequence}), also as a type argument
 * ({@code List<?>}).
 */
class BindingNameTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final List<String> OUTGOING_NEXT_TOOLS = List.of(
        "get_call_hierarchy_incoming to see who calls this method",
        "go_to_definition to navigate to a called method");
    private static final List<String> ANALYZE_NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

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
    @DisplayName("get_call_hierarchy_outgoing of listener() names the constructor its anonymous Runnable runs, Object(), returning the anonymous class Events$1")
    void outgoingAnonymousCreation() {
        // The anonymous class's own run() calls nothing, so its creation is the only callee. An
        // anonymous class implementing an interface extends Object, so its constructor runs
        // Object().
        Map<String, Object> data = map(
            "method", "listener",
            "declaringClass", EVENTS_TYPE,
            "signature", "listener()",
            "totalCallees", 1,
            "callees", List.of(map(
                "method", "<init>",
                "declaringClass", "java.lang.Object",
                "signature", "new Object()",
                "returnType", "Events$1",
                "callType", "CONSTRUCTOR",
                "isFromSource", false,
                "callLocation", location(31, 15))));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(30, 13),
            success(data, outgoingMeta(1)));
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of add in anonymous class 2 names its declaring class Events$2 and super.add's class java.util.ArrayList")
    void outgoingFromAnonymousMethod() {
        Map<String, Object> data = map(
            "method", "add",
            "declaringClass", EVENTS_TYPE + "$2",
            "signature", "add(String)",
            "totalCallees", 2,
            "callees", List.of(
                map(
                    "method", "record",
                    "declaringClass", EVENTS_TYPE,
                    "signature", "record(String)",
                    "returnType", "void",
                    "callType", "METHOD",
                    "isFromSource", true,
                    "callLocation", location(45, 16)),
                map(
                    "method", "add",
                    "declaringClass", "java.util.ArrayList",
                    "signature", "add(String)",
                    "returnType", "boolean",
                    "callType", "SUPER_METHOD",
                    "isFromSource", false,
                    "callLocation", location(46, 23))));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(44, 27),
            success(data, outgoingMeta(2)));
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of tally() names the local class Counter, as declaring class and as parameter type")
    void outgoingLocalClass() {
        // Counter's own methods call nothing; tally() creates a Counter and calls bump and merge.
        Map<String, Object> data = map(
            "method", "tally",
            "declaringClass", EVENTS_TYPE,
            "signature", "tally()",
            "totalCallees", 3,
            "callees", List.of(
                map(
                    "method", "<init>",
                    "declaringClass", EVENTS_TYPE + "$Counter",
                    "signature", "new Counter()",
                    "returnType", "Counter",
                    "callType", "CONSTRUCTOR",
                    "isFromSource", true,
                    "callLocation", location(79, 26)),
                map(
                    "method", "bump",
                    "declaringClass", EVENTS_TYPE + "$Counter",
                    "signature", "bump()",
                    "returnType", "void",
                    "callType", "METHOD",
                    "isFromSource", true,
                    "callLocation", location(80, 8)),
                map(
                    "method", "merge",
                    "declaringClass", EVENTS_TYPE + "$Counter",
                    "signature", "merge(Counter[])",
                    "returnType", "void",
                    "callType", "METHOD",
                    "isFromSource", true,
                    "callLocation", location(81, 8))));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(67, 8),
            success(data, outgoingMeta(3)));
    }

    @Test
    @DisplayName("analyze_method of listener() names the constructor its anonymous Runnable runs, java.lang.Object's Object()")
    void analyzeMethodAnonymousCreation() {
        Map<String, Object> data = map(
            "method", map(
                "name", "listener",
                "constructor", false,
                "modifiers", List.of(),
                "declaringType", EVENTS_TYPE,
                "signature", "listener(): Runnable",
                "returnType", "Runnable",
                "file", EVENTS,
                "line", 30),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 1, "list", List.of(map(
                "name", "Object",
                "kind", "CONSTRUCTOR",
                "qualifiedType", "java.lang.Object",
                "signature", "Object()",
                "callLine", 31))),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(30, 13),
            success(data, map("totalCount", 1, "returnedCount", 1, "truncated", false, "suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_method of tally() names the local class Counter, as qualified type and as parameter type")
    void analyzeMethodLocalClass() {
        String counter = EVENTS_TYPE + "$Counter";
        Map<String, Object> data = map(
            "method", map(
                "name", "tally",
                "constructor", false,
                "modifiers", List.of(),
                "declaringType", EVENTS_TYPE,
                "signature", "tally(): int",
                "returnType", "int",
                "file", EVENTS,
                "line", 67),
            "parameters", List.of(),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 3, "list", List.of(
                map("name", "Counter", "kind", "CONSTRUCTOR", "qualifiedType", counter, "signature", "Counter()",
                    "callLine", 79),
                map("name", "bump", "kind", "METHOD", "qualifiedType", counter, "signature", "bump()",
                    "callLine", 80),
                map("name", "merge", "kind", "METHOD", "qualifiedType", counter, "signature", "merge(Counter[])",
                    "callLine", 81))),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(67, 8),
            success(data, map("totalCount", 3, "returnedCount", 3, "truncated", false, "suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of span names each captured wildcard as the wildcard: ? extends CharSequence, ? super String, List<?>")
    void outgoingCapturedWildcards() {
        // span(List<? extends CharSequence> texts, List<? super String> sink, List<?> any): a call
        // on a parameter sees its wildcard captured, so get returns the capture of
        // ? extends CharSequence, add takes the capture of ? super String and subList returns a
        // List of the capture of ?. The size() call is visited before subList(), the call it is
        // made on; both start at any.
        Map<String, Object> data = map(
            "method", "span",
            "declaringClass", EVENTS_TYPE,
            "signature", "span(List<? extends CharSequence>, List<? super String>, List<?>)",
            "totalCallees", 5,
            "callees", List.of(
                libraryCallee("get", "java.util.List", "get(int)", "? extends CharSequence", location(131, 29)),
                libraryCallee("add", "java.util.List", "add(? super String)", "boolean", location(132, 8)),
                libraryCallee("length", "java.lang.CharSequence", "length()", "int", location(133, 15)),
                libraryCallee("size", "java.util.List", "size()", "int", location(133, 32)),
                libraryCallee("subList", "java.util.List", "subList(int, int)", "List<?>", location(133, 32))));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(130, 15),
            success(data, outgoingMeta(5)));
    }

    @Test
    @DisplayName("analyze_method of span names the captured wildcard of add's parameter ? super String")
    void analyzeMethodCapturedWildcards() {
        String list = "java.util.List";
        Map<String, Object> data = map(
            "method", map(
                "name", "span",
                "constructor", false,
                "modifiers", List.of("static"),
                "declaringType", EVENTS_TYPE,
                "signature", "span(List<? extends CharSequence> texts, List<? super String> sink, List<?> any): int",
                "returnType", "int",
                "file", EVENTS,
                "line", 130),
            "parameters", List.of(
                map("type", "List<? extends CharSequence>", "name", "texts"),
                map("type", "List<? super String>", "name", "sink"),
                map("type", "List<?>", "name", "any")),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 5, "list", List.of(
                map("name", "get", "kind", "METHOD", "qualifiedType", list, "signature", "get(int)", "callLine", 131),
                map("name", "add", "kind", "METHOD", "qualifiedType", list, "signature", "add(? super String)",
                    "callLine", 132),
                map("name", "length", "kind", "METHOD", "qualifiedType", "java.lang.CharSequence",
                    "signature", "length()", "callLine", 133),
                map("name", "size", "kind", "METHOD", "qualifiedType", list, "signature", "size()", "callLine", 133),
                map("name", "subList", "kind", "METHOD", "qualifiedType", list, "signature", "subList(int, int)",
                    "callLine", 133))),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(130, 15),
            success(data, map("totalCount", 5, "returnedCount", 5, "truncated", false, "suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    private static Map<String, Object> libraryCallee(String method, String declaringClass, String signature,
                                                     String returnType, Map<String, Object> location) {
        return map(
            "method", method,
            "declaringClass", declaringClass,
            "signature", signature,
            "returnType", returnType,
            "callType", "METHOD",
            "isFromSource", false,
            "callLocation", location);
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> location(int line, int column) {
        return map("filePath", EVENTS, "line", line, "column", column);
    }

    private static Map<String, Object> outgoingMeta(int count) {
        return map("totalCount", count, "returnedCount", count, "suggestedNextTools", OUTGOING_NEXT_TOOLS);
    }
}
