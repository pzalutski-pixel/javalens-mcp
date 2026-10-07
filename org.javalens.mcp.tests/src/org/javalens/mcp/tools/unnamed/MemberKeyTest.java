package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindUnreachableCodeTool;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
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
 * Every member key, {@code Type#member(params)}, and every graph type key names its type fully
 * qualified with {@code $} between nested types, as the containing-type fields do: a member
 * class ({@code pkg.Outer$Inner}), a local class ({@code pkg.Outer$Local}), an anonymous class
 * ({@code pkg.Outer$1}) and a nested library type ({@code java.util.Map$Entry}) alike. A key
 * computed from a model element and the key of the same member in the project graph are equal,
 * so a test method in a nested test class is a reachability root.
 *
 * <p>The member-keys fixture (package com.example.keys; positions zero-based):
 * <ul>
 *   <li>Api.java: {@code Api} (line 5) declares the member class {@code Orders} (line 8, name at
 *       column 24, {@code @Path("/orders")}) with the fields {@code served} (line 10, column 12)
 *       and {@code note} (line 12), {@code open()} (line 16, {@code @GET @Path("/open")},
 *       incrementing {@code served}) and {@code closed()} (line 21, reading {@code note}); and
 *       the empty member class {@code Archive} (line 26).</li>
 *   <li>Dispatch.java: {@code Dispatch} (line 6); {@code send(String message)} (line 8, name at
 *       column 9) declares the local class {@code Courier}, whose {@code deliver(String text)}
 *       prints {@code text} (line 11), and calls {@code new Courier().deliver(message)} (line
 *       14); {@code parser()} (line 17) returns an anonymous Function, the only anonymous class
 *       of Dispatch, whose {@code apply(String text)} (line 19, name at column 27) returns
 *       {@code Integer.valueOf(text)} (line 20); {@code store(String value)} (line 25, name at
 *       column 9) declares {@code Map.Entry<String, String> entry} (line 26) and calls
 *       {@code entry.setValue(value)} (line 27).</li>
 *   <li>ApiTest.java (test sources): {@code ApiTest} (line 5) declares the {@code @Nested} class
 *       {@code OrdersTest} (line 8) whose {@code @Test opens()} (line 11) calls
 *       {@code new Api.Orders().open()}.</li>
 *   <li>Annotation declarations stand in for jakarta.ws.rs (GET, Path) and JUnit (Test,
 *       Nested); the project graph holds no node for an annotation type.</li>
 * </ul>
 */
class MemberKeyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "member-keys";
    private static final String API = "src/main/java/com/example/keys/Api.java";
    private static final String DISPATCH = "src/main/java/com/example/keys/Dispatch.java";
    private static final String API_TEST = "src/test/java/com/example/keys/ApiTest.java";
    private static final String ORDERS = "com.example.keys.Api$Orders";
    private static final String ORDERS_TEST = "com.example.keys.ApiTest$OrdersTest";
    private static final String DISPATCH_TYPE = "com.example.keys.Dispatch";
    private static final List<String> DATA_FLOW_NEXT_TOOLS = List.of(
        "analyze_control_flow for branching and loop analysis",
        "extract_method to extract code with understood data dependencies");
    private static final List<String> CHANGE_IMPACT_NEXT_TOOLS = List.of(
        "find_affected_tests for the tests covering this symbol",
        "find_references for the direct call sites with locations");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("analyze_change_impact transitive on record names the member class Recorder's caller Events$Recorder#accept(String)")
    void transitiveImpact_nestedTypeMethodKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(UnnamedElements.FIXTURE);
        // record's callers in the project graph: names() (the call in anonymous class 2 belongs
        // to it), later() (its lambda), audit() (its local class Auditor), and the member class
        // Recorder's accept. The field initializer of RESET and the static initializer belong to
        // the type Events, which is no method. Keys sort with # before $.
        Map<String, Object> data = map(
            "symbol", "record",
            "symbolType", "SourceMethod",
            "transitive", true,
            "totalAffectedMethods", 4,
            "affectedMethods", List.of(
                EVENTS_TYPE + "#audit()",
                EVENTS_TYPE + "#later()",
                EVENTS_TYPE + "#names()",
                EVENTS_TYPE + "$Recorder#accept(String)"),
            "affectedFiles", List.of(map("filePath", EVENTS, "methodCount", 4)));
        Map<String, Object> meta = map(
            "totalCount", 4,
            "returnedCount", 4,
            "truncated", false,
            "suggestedNextTools", CHANGE_IMPACT_NEXT_TOOLS);
        ObjectNode args = position(UnnamedElements.FIXTURE, EVENTS, 16, 16);
        args.put("transitive", true);
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), new EnvelopeHarness(service), args,
            success(data, meta));
    }

    @Test
    @DisplayName("analyze_change_impact transitive on the field Api.Orders.served names Api$Orders#open() and ApiTest$OrdersTest#opens()")
    void transitiveImpact_nestedTypeFieldKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // served is written by open(), which opens() in the nested test class calls.
        Map<String, Object> data = map(
            "symbol", "served",
            "symbolType", "SourceField",
            "transitive", true,
            "totalAffectedMethods", 2,
            "affectedMethods", List.of(ORDERS + "#open()", ORDERS_TEST + "#opens()"),
            "affectedFiles", List.of(
                map("filePath", API, "methodCount", 1),
                map("filePath", API_TEST, "methodCount", 1)));
        Map<String, Object> meta = map(
            "totalCount", 2,
            "returnedCount", 2,
            "truncated", false,
            "suggestedNextTools", CHANGE_IMPACT_NEXT_TOOLS);
        ObjectNode args = position(FIXTURE, API, 10, 12);
        args.put("transitive", true);
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), new EnvelopeHarness(service), args,
            success(data, meta));
    }

    @Test
    @DisplayName("find_affected_tests on the member class Api.Orders finds ApiTest$OrdersTest#opens() in the nested test class")
    void affectedTests_nestedTypeKeys() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // opens() creates an Orders and calls its open(); the test carries no framework-qualified
        // annotation and no lifecycle annotation, so no framework is named.
        Map<String, Object> data = map(
            "symbol", "Orders",
            "symbolType", "SourceType",
            "testMethodCount", 1,
            "testMethods", List.of(map(
                "className", ORDERS_TEST,
                "methodName", "opens",
                "key", ORDERS_TEST + "#opens()",
                "filePath", API_TEST,
                "line", 11)));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_change_impact with transitive=true for the full blast radius",
                "find_tests for the project's whole test inventory"));
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), new EnvelopeHarness(service),
            position(FIXTURE, API, 8, 24), success(data, meta));
    }

    @Test
    @DisplayName("find_unreachable_code keys nested types, their methods and fields with $ and roots the nested test method")
    void unreachable_nestedTypeKeys() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // The one root, ApiTest$OrdersTest#opens(), reaches OrdersTest, Orders (created), open()
        // and served. Api, Archive, Dispatch and ApiTest have no reachable method or field; the
        // local Courier and the anonymous Function have no graph node.
        Map<String, Object> data = map(
            "roots", map("mainMethods", List.of(), "testMethodCount", 1),
            "unreachableCount", 9,
            "unreachable", List.of(
                unreachable("type", "com.example.keys.Api", "public", API, 5),
                unreachable("field", ORDERS + "#note", "package", API, 12),
                unreachable("method", ORDERS + "#closed()", "package", API, 21),
                unreachable("type", "com.example.keys.Api$Archive", "package", API, 26),
                unreachable("type", DISPATCH_TYPE, "public", DISPATCH, 6),
                unreachable("method", DISPATCH_TYPE + "#send(String)", "package", DISPATCH, 8),
                unreachable("method", DISPATCH_TYPE + "#parser()", "package", DISPATCH, 17),
                unreachable("method", DISPATCH_TYPE + "#store(String)", "package", DISPATCH, 25),
                unreachable("type", "com.example.keys.ApiTest", "package", API_TEST, 5)));
        Map<String, Object> meta = map(
            "totalCount", 9,
            "returnedCount", 9,
            "truncated", false,
            "suggestedNextTools", List.of(
                "find_references to double-check a member before removing it",
                "find_affected_tests to see what tests cover surviving code",
                "analyze_change_impact for the blast radius of a removal"));
        assertPayload(mapper, new FindUnreachableCodeTool(() -> service), new EnvelopeHarness(service),
            mapper.createObjectNode(), success(data, meta));
    }

    @Test
    @DisplayName("get_http_endpoints names the handler in the member class Api.Orders com.example.keys.Api$Orders#open()")
    void httpEndpoint_nestedTypeKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        Map<String, Object> data = map(
            "endpointCount", 1,
            "endpoints", List.of(map(
                "httpMethod", "GET",
                "path", "/orders/open",
                "handler", ORDERS + "#open()",
                "framework", "jaxrs",
                "filePath", API,
                "line", 16)));
        Map<String, Object> meta = map(
            "totalCount", 1,
            "returnedCount", 1,
            "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"));
        assertPayload(mapper, new GetHttpEndpointsTool(() -> service), new EnvelopeHarness(service),
            mapper.createObjectNode(), success(data, meta));
    }

    @Test
    @DisplayName("analyze_data_flow followCalls from send names the local class Courier's method Dispatch$Courier#deliver(String)")
    void dataFlow_localClassKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // message flows into Courier.deliver's text, which escapes into PrintStream.println.
        String deliver = DISPATCH_TYPE + "$Courier#deliver(String)";
        Map<String, Object> flow = map(
            "fact", "taint",
            "sourceVariable", "message",
            "sourceMethod", DISPATCH_TYPE + "#send(String)",
            "sourceLine", 8,
            "steps", List.of(map("kind", "argument", "callee", deliver, "parameter", "text", "argIndex", 0,
                "line", 14)),
            "sink", map("kind", "escape", "callee", "java.io.PrintStream#println(String)",
                "expression", "System.out.println(text)", "method", deliver, "line", 11));
        Map<String, Object> data = map(
            "method", "send",
            "parameterCount", 1,
            "variables", List.of(
                readOnce("message", "String", "parameter", true),
                readOnce("text", "String", "parameter", true),
                readOnce("out", "PrintStream", "field", false)),
            "returnStatements", 0,
            "followCalls", true,
            "interproceduralFlows", List.of(flow));
        ObjectNode args = position(FIXTURE, DISPATCH, 8, 9);
        args.put("followCalls", true);
        assertPayload(mapper, new AnalyzeDataFlowTool(() -> service), new EnvelopeHarness(service), args,
            success(data, map("suggestedNextTools", DATA_FLOW_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_data_flow followCalls from apply of the anonymous Function names it Dispatch$1#apply(String)")
    void dataFlow_anonymousClassKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // text escapes into Integer.valueOf(String) in apply itself.
        String apply = DISPATCH_TYPE + "$1#apply(String)";
        Map<String, Object> flow = map(
            "fact", "taint",
            "sourceVariable", "text",
            "sourceMethod", apply,
            "sourceLine", 19,
            "steps", List.of(),
            "sink", map("kind", "escape", "callee", "java.lang.Integer#valueOf(String)",
                "expression", "Integer.valueOf(text)", "method", apply, "line", 20));
        Map<String, Object> data = map(
            "method", "apply",
            "parameterCount", 1,
            "variables", List.of(readOnce("text", "String", "parameter", true)),
            "returnStatements", 1,
            "followCalls", true,
            "interproceduralFlows", List.of(flow));
        ObjectNode args = position(FIXTURE, DISPATCH, 19, 27);
        args.put("followCalls", true);
        assertPayload(mapper, new AnalyzeDataFlowTool(() -> service), new EnvelopeHarness(service), args,
            success(data, map("suggestedNextTools", DATA_FLOW_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("analyze_data_flow followCalls from store names the library member Map.Entry.setValue java.util.Map$Entry#setValue(V)")
    void dataFlow_nestedLibraryTypeKey() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        // value escapes into Map.Entry's setValue, declared with the type variable V; entry is
        // written by its initializer and read as the receiver.
        String store = DISPATCH_TYPE + "#store(String)";
        Map<String, Object> flow = map(
            "fact", "taint",
            "sourceVariable", "value",
            "sourceMethod", store,
            "sourceLine", 25,
            "steps", List.of(),
            "sink", map("kind", "escape", "callee", "java.util.Map$Entry#setValue(V)",
                "expression", "entry.setValue(value)", "method", store, "line", 27));
        Map<String, Object> data = map(
            "method", "store",
            "parameterCount", 1,
            "variables", List.of(
                readOnce("value", "String", "parameter", true),
                map("name", "entry", "type", "Map.Entry<String,String>", "kind", "local", "declared", true,
                    "read", true, "written", true, "readCount", 1, "writeCount", 1)),
            "returnStatements", 0,
            "followCalls", true,
            "interproceduralFlows", List.of(flow));
        ObjectNode args = position(FIXTURE, DISPATCH, 25, 9);
        args.put("followCalls", true);
        assertPayload(mapper, new AnalyzeDataFlowTool(() -> service), new EnvelopeHarness(service), args,
            success(data, map("suggestedNextTools", DATA_FLOW_NEXT_TOOLS)));
    }

    private ObjectNode position(String fixture, String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(fixture).resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> unreachable(String kind, String key, String visibility, String filePath,
                                                   int line) {
        return map("kind", kind, "key", key, "visibility", visibility, "filePath", filePath, "line", line);
    }

    /** A variable read once and never written. */
    private static Map<String, Object> readOnce(String name, String type, String kind, boolean declared) {
        return map("name", name, "type", type, "kind", kind, "declared", declared, "read", true,
            "written", false, "readCount", 1, "writeCount", 0);
    }
}
