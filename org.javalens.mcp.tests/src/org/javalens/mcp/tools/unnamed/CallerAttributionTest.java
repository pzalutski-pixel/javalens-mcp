package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
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
import static org.javalens.mcp.tools.unnamed.UnnamedElements.without;

/**
 * A reference is attributed to its nearest enclosing named member: a method (of any class,
 * anonymous or local included), an initializer or a field, never a lambda expression. An
 * initializer or field renders as {@code <initializer>} in the place of a method name. The
 * containing type of each is its fully qualified name.
 *
 * <p>record(String) (line 16, column 16) is called six times. In search order, Events' fields
 * and initializers come first, then its methods with the classes declared in them, then its
 * member classes: the lambda initializing RESET (line 10), the static initializer (line 13),
 * add of anonymous class 2 in names() (line 45), the lambda returned by later() (line 62),
 * check of the local class Auditor in audit() (line 88), accept of the member class Recorder
 * (line 26).
 */
class CallerAttributionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

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
    @DisplayName("get_call_hierarchy_incoming attributes each call of record to its named member: lambda calls to RESET and later(), the others to their methods and initializer")
    void incomingCalls() {
        Map<String, Object> data = map(
            "method", "record",
            "declaringClass", EVENTS_TYPE,
            "signature", "record(String)",
            "totalCallers", 6,
            "callers", List.of(
                initializerCaller(10, 40, "static final Runnable RESET = () -> record(\"reset\");"),
                initializerCaller(13, 8, "record(\"loaded\");"),
                methodCaller(45, 16, "record(name);", "add", "add(String)", EVENTS_TYPE + "$2"),
                methodCaller(62, 12, "record(text);", "later", "later()", EVENTS_TYPE),
                methodCaller(88, 16, "record(\"check\");", "check", "check()", EVENTS_TYPE + "$Auditor"),
                methodCaller(26, 12, "record(event);", "accept", "accept(String)", EVENTS_TYPE + "$Recorder")));
        Map<String, Object> meta = map(
            "totalCount", 6,
            "returnedCount", 6,
            "truncated", false,
            "suggestedNextTools", List.of(
                "get_call_hierarchy_outgoing to see what this method calls",
                "go_to_definition to navigate to a caller"));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope,
            position(16, 16), success(data, meta));
    }

    @Test
    @DisplayName("analyze_method lists record's six callers by named member and qualified type only, initializer and field callers as <initializer>")
    void analyzeMethodCallers() {
        Map<String, Object> data = map(
            "method", map(
                "name", "record",
                "constructor", false,
                "modifiers", List.of("static"),
                "declaringType", EVENTS_TYPE,
                "signature", "record(String event): void",
                "returnType", "void",
                "file", EVENTS,
                "line", 16),
            "parameters", List.of(map("type", "String", "name", "event")),
            "callers", map("count", 6, "list", List.of(
                caller("<initializer>", EVENTS_TYPE, 10),
                caller("<initializer>", EVENTS_TYPE, 13),
                caller("add", EVENTS_TYPE + "$2", 45),
                caller("later", EVENTS_TYPE, 62),
                caller("check", EVENTS_TYPE + "$Auditor", 88),
                caller("accept", EVENTS_TYPE + "$Recorder", 26))),
            "callees", map("count", 1, "list", List.of(map(
                "name", "length",
                "kind", "METHOD",
                "qualifiedType", "java.lang.String",
                "signature", "length()",
                "callLine", 17))),
            "overrides", map());
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "analyze_type for declaring type analysis",
            "find_references for all usages",
            "go_to_definition to navigate to a callee"));
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(16, 16),
            success(data, meta));
    }

    @Test
    @DisplayName("analyze_change_impact attributes each call site of record to its named member and its qualified type")
    void changeImpactCallSites() {
        // Each call site's length runs from the method name to the closing parenthesis. Raw
        // offsets depend on line endings, so they are removed; line and column pin each site.
        Map<String, Object> data = map(
            "symbol", "record",
            "symbolType", "SourceMethod",
            "depth", 1,
            "directReferences", 6,
            "totalReferences", 6,
            "affectedFiles", List.of(map("filePath", EVENTS, "referenceCount", 6)),
            "callSites", List.of(
                callSite(15, 10, 40, "static final Runnable RESET = () -> record(\"reset\");",
                    "<initializer>", EVENTS_TYPE),
                callSite(16, 13, 8, "record(\"loaded\");", "<initializer>", EVENTS_TYPE),
                callSite(12, 45, 16, "record(name);", "add", EVENTS_TYPE + "$2"),
                callSite(12, 62, 12, "record(text);", "later", EVENTS_TYPE),
                callSite(15, 88, 16, "record(\"check\");", "check", EVENTS_TYPE + "$Auditor"),
                callSite(13, 26, 12, "record(event);", "accept", EVENTS_TYPE + "$Recorder")));
        Map<String, Object> meta = map(
            "totalCount", 6,
            "returnedCount", 6,
            "suggestedNextTools", List.of(
                "rename_symbol for safe renaming across all affected files",
                "find_references for simple reference list without grouping"));
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, position(16, 16),
            success(data, meta), without("offset", "data", "callSites"));
    }

    @Test
    @DisplayName("get_enclosing_element on a local variable of the lambda in later() reports later() and Events as its enclosing method and type")
    void enclosingElementInLambda() {
        Map<String, Object> data = map(
            "element", map("name", "text", "kind", "variable"),
            "enclosingMethod", map(
                "name", "later",
                "isConstructor", false,
                "signature", "later(): Supplier<String>",
                "modifiers", List.of(),
                "line", 59),
            "enclosingType", map(
                "name", "Events",
                "qualifiedName", EVENTS_TYPE,
                "kind", "class",
                "modifiers", List.of("public"),
                "line", 6),
            "enclosingPackage", "com.example.unnamed",
            "filePath", EVENTS);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "get_type_members for type members",
            "get_document_symbols for file structure"));
        assertPayload(mapper, new GetEnclosingElementTool(() -> service), envelope, position(61, 19),
            success(data, meta));
    }

    @Test
    @DisplayName("get_type_at_position on a local variable of the lambda in later() reports Events, the type the lambda is written in")
    void typeAtPositionInLambda() {
        // Events declares 10 methods (record, listener, names, sink, later, tally, audit, weigh,
        // pair, deferred), the 2 fields total and RESET, and the 2 member classes Sink and
        // Recorder; it extends nothing.
        Map<String, Object> data = map(
            "name", "Events",
            "qualifiedName", EVENTS_TYPE,
            "kind", "class",
            "modifiers", List.of("public"),
            "isAnonymous", false,
            "isLocal", false,
            "filePath", EVENTS,
            "line", 6,
            "methodCount", 10,
            "fieldCount", 2,
            "nestedTypeCount", 2);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "get_type_hierarchy for inheritance chain",
            "get_type_members for all members",
            "find_implementations for implementors"));
        assertPayload(mapper, new GetTypeAtPositionTool(() -> service), envelope, position(61, 19),
            success(data, meta));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", events);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> initializerCaller(int line, int column, String context) {
        return map("filePath", EVENTS, "line", line, "column", column, "context", context,
            "callerMethod", "<initializer>", "callerClass", EVENTS_TYPE);
    }

    private static Map<String, Object> methodCaller(int line, int column, String context, String method,
                                                    String signature, String callerClass) {
        return map("filePath", EVENTS, "line", line, "column", column, "context", context,
            "callerMethod", method, "callerSignature", signature, "callerClass", callerClass);
    }

    private static Map<String, Object> caller(String method, String qualifiedType, int callLine) {
        return map("method", method, "qualifiedType", qualifiedType, "file", EVENTS, "callLine", callLine);
    }

    private static Map<String, Object> callSite(int length, int line, int column, String context,
                                                String enclosingMethod, String enclosingType) {
        return map("filePath", EVENTS, "length", length, "line", line, "column", column, "context", context,
            "depth", 1, "enclosingMethod", enclosingMethod, "enclosingType", enclosingType);
    }
}
