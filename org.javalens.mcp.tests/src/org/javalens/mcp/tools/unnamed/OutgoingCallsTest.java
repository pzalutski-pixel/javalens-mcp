package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.CALLS;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PACKAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A method's outgoing calls are the calls it makes itself, the inverse of incoming calls: a call
 * written in a lambda body belongs to the method the lambda is written in, and a call written in
 * the body of an anonymous or local class declared in the method belongs to that class's member.
 * The creation of an anonymous class, and the arguments it is created with, are calls of the
 * method that creates it; the creation is a call of the superclass constructor the anonymous
 * class's constructor runs, returning the anonymous class. The fixture is Calls.java in
 * unnamed-semantics ({@link UnnamedSemantics}).
 */
class OutgoingCallsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CALLS_TYPE = PACKAGE + "Calls";
    private static final String ANONYMOUS_2 = CALLS_TYPE + "$2";
    private static final String LOCAL = CALLS_TYPE + "$Local";
    private static final String PROBE = CALLS_TYPE + "$Probe";
    private static final List<String> INCOMING_NEXT_TOOLS = List.of(
        "get_call_hierarchy_outgoing to see what this method calls",
        "go_to_definition to navigate to a caller");
    private static final List<String> OUTGOING_NEXT_TOOLS = List.of(
        "get_call_hierarchy_incoming to see who calls this method",
        "go_to_definition to navigate to a called method");
    private static final List<String> ANALYZE_NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    /** The position of the name of each method declared in Calls.java. */
    private static final List<int[]> DECLARED_METHODS = List.of(
        new int[] {7, 8},    // Probe(String)
        new int[] {11, 18},  // tag(String)
        new int[] {15, 15},  // sample(String)
        new int[] {19, 15},  // weigh(String)
        new int[] {23, 15},  // audit(String)
        new int[] {27, 15},  // stamp(String)
        new int[] {31, 8},   // direct(String)
        new int[] {36, 16},  // measure() of anonymous class 1
        new int[] {41, 16},  // count() of Local
        new int[] {47, 16},  // measure() of anonymous class 2
        new int[] {54, 18}); // label(String)

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String calls;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedSemantics.FIXTURE);
        envelope = new EnvelopeHarness(service);
        calls = helper.getFixturePath(UnnamedSemantics.FIXTURE).resolve(CALLS).toString();
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of direct lists its own calls: sample in its lambda, Probe(String) for each anonymous creation, returning Calls$1 and Calls$2, tag and label as their arguments, get, count and Local's creation, and none written in the anonymous or local class bodies")
    void outgoingOfEnclosingMethod() {
        Map<String, Object> data = map(
            "method", "direct",
            "declaringClass", CALLS_TYPE,
            "signature", "direct(String)",
            "totalCallees", 8,
            "callees", List.of(
                sourceCallee("sample", CALLS_TYPE, "sample(String)", "int", "METHOD", 32, 43),
                sourceCallee("<init>", PROBE, "new Probe(String)", "Calls$1", "CONSTRUCTOR", 33, 22),
                sourceCallee("tag", CALLS_TYPE, "tag(String)", "String", "METHOD", 33, 32),
                sourceCallee("<init>", PROBE, "new Probe(String)", "Calls$2", "CONSTRUCTOR", 46, 39),
                sourceCallee("label", CALLS_TYPE, "label(String)", "String", "METHOD", 46, 49),
                callee("get", "java.util.function.Supplier", "get()", "Integer", "METHOD", false, 51, 15),
                sourceCallee("count", LOCAL, "count()", "int", "METHOD", 51, 32),
                sourceCallee("<init>", LOCAL, "new Local()", "Local", "CONSTRUCTOR", 51, 32)));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(31, 8),
            success(data, outgoingMeta(8)));
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of count in the local class Local lists audit, called in its lambda, and that lambda's get")
    void outgoingOfLocalClassMethod() {
        Map<String, Object> data = map(
            "method", "count",
            "declaringClass", LOCAL,
            "signature", "count()",
            "totalCallees", 2,
            "callees", List.of(
                sourceCallee("audit", CALLS_TYPE, "audit(String)", "int", "METHOD", 42, 48),
                callee("get", "java.util.function.Supplier", "get()", "Integer", "METHOD", false, 43, 23)));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(41, 16),
            success(data, outgoingMeta(2)));
    }

    @Test
    @DisplayName("get_call_hierarchy_outgoing of measure in anonymous class 2, created in a lambda, lists stamp")
    void outgoingOfAnonymousClassMethodInLambda() {
        Map<String, Object> data = map(
            "method", "measure",
            "declaringClass", ANONYMOUS_2,
            "signature", "measure()",
            "totalCallees", 1,
            "callees", List.of(sourceCallee("stamp", CALLS_TYPE, "stamp(String)", "int", "METHOD", 48, 23)));
        assertPayload(mapper, new GetCallHierarchyOutgoingTool(() -> service), envelope, position(47, 16),
            success(data, outgoingMeta(1)));
    }

    @Test
    @DisplayName("analyze_method of direct lists the same eight callees as its outgoing calls, Probe(String) for each anonymous creation, none written in the anonymous or local class bodies")
    void analyzeMethodCalleesOfEnclosingMethod() {
        Map<String, Object> data = map(
            "method", map(
                "name", "direct",
                "constructor", false,
                "modifiers", List.of(),
                "declaringType", CALLS_TYPE,
                "signature", "direct(String seed): int",
                "returnType", "int",
                "file", CALLS,
                "line", 31),
            "parameters", List.of(map("type", "String", "name", "seed")),
            "callers", map("count", 0, "list", List.of()),
            "callees", map("count", 8, "list", List.of(
                analyzedCallee("sample", "METHOD", CALLS_TYPE, "sample(String)", 32),
                analyzedCallee("Probe", "CONSTRUCTOR", PROBE, "Probe(String)", 33),
                analyzedCallee("tag", "METHOD", CALLS_TYPE, "tag(String)", 33),
                analyzedCallee("Probe", "CONSTRUCTOR", PROBE, "Probe(String)", 46),
                analyzedCallee("label", "METHOD", CALLS_TYPE, "label(String)", 46),
                analyzedCallee("get", "METHOD", "java.util.function.Supplier", "get()", 51),
                analyzedCallee("count", "METHOD", LOCAL, "count()", 51),
                analyzedCallee("Local", "CONSTRUCTOR", LOCAL, "Local()", 51))),
            "overrides", map());
        assertPayload(mapper, new AnalyzeMethodTool(() -> service), envelope, position(31, 8),
            success(data, map("totalCount", 8, "returnedCount", 8, "truncated", false, "suggestedNextTools", ANALYZE_NEXT_TOOLS)));
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming of label lists direct: the argument of an anonymous class created in direct's lambda is direct's call")
    void incomingOfCreationArgumentInLambda() {
        Map<String, Object> data = map(
            "method", "label",
            "declaringClass", CALLS_TYPE,
            "signature", "label(String)",
            "totalCallers", 1,
            "callers", List.of(directCaller(46, 49, "Supplier<Probe> nested = () -> new Probe(label(seed)) {")));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, position(54, 18),
            success(data, incomingMeta(1)));
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming of Probe(String) lists direct twice: it creates both anonymous Probe subclasses, one in its lambda")
    void incomingOfSuperConstructorOfAnonymousClasses() {
        // An anonymous class's constructor calls the superclass constructor with the creation's
        // arguments; the reference is placed at the type name the creation names. Search returns
        // the callers in no source order, so they are compared sorted by line.
        Map<String, Object> data = map(
            "method", "Probe",
            "declaringClass", PROBE,
            "signature", "Probe(String)",
            "totalCallers", 2,
            "callers", List.of(
                directCaller(33, 26, "Probe probe = new Probe(tag(seed)) {"),
                directCaller(46, 43, "Supplier<Probe> nested = () -> new Probe(label(seed)) {")));
        assertPayload(mapper, new GetCallHierarchyIncomingTool(() -> service), envelope, position(7, 8),
            success(data, incomingMeta(2)), UnnamedElements.sortedBy("line", "data", "callers"));
    }

    @Test
    @DisplayName("for every method m and every method c declared in Calls.java, c is an outgoing call of m exactly when the incoming calls of c list m")
    void outgoingIsInverseOfIncoming() throws Exception {
        // Each method is keyed by its declaring class and signature, as both tools render them;
        // outgoing calls write a constructor's signature after "new".
        // The declared callees: direct calls sample, tag, label and count, and Probe(String)
        // through the creation of both anonymous classes; Local's count calls audit; anonymous
        // class 2's measure calls stamp. weigh is called by anonymous class 1's field initializer,
        // no method; String.length and Supplier.get are declared in no source.
        GetCallHierarchyOutgoingTool outgoing = new GetCallHierarchyOutgoingTool(() -> service);
        GetCallHierarchyIncomingTool incoming = new GetCallHierarchyIncomingTool(() -> service);

        Set<String> declared = new LinkedHashSet<>();
        for (int[] method : DECLARED_METHODS) {
            JsonNode data = payload(incoming, method).path("data");
            declared.add(data.path("declaringClass").asText() + "#" + data.path("signature").asText());
        }

        Set<String> outgoingPairs = new LinkedHashSet<>();
        Set<String> incomingPairs = new LinkedHashSet<>();
        for (int[] method : DECLARED_METHODS) {
            JsonNode out = payload(outgoing, method).path("data");
            String caller = out.path("declaringClass").asText() + "#" + out.path("signature").asText();
            for (JsonNode callee : out.path("callees")) {
                String called = callee.path("declaringClass").asText() + "#"
                    + callee.path("signature").asText().replaceFirst("^new ", "");
                if (declared.contains(called)) {
                    outgoingPairs.add(caller + " -> " + called);
                }
            }
            JsonNode in = payload(incoming, method).path("data");
            String called = in.path("declaringClass").asText() + "#" + in.path("signature").asText();
            for (JsonNode callerNode : in.path("callers")) {
                if (callerNode.has("callerSignature")) {
                    incomingPairs.add(callerNode.path("callerClass").asText() + "#"
                        + callerNode.path("callerSignature").asText() + " -> " + called);
                }
            }
        }

        Set<String> expected = Set.of(
            CALLS_TYPE + "#direct(String) -> " + CALLS_TYPE + "#sample(String)",
            CALLS_TYPE + "#direct(String) -> " + CALLS_TYPE + "#tag(String)",
            CALLS_TYPE + "#direct(String) -> " + CALLS_TYPE + "#label(String)",
            CALLS_TYPE + "#direct(String) -> " + PROBE + "#Probe(String)",
            CALLS_TYPE + "#direct(String) -> " + LOCAL + "#count()",
            LOCAL + "#count() -> " + CALLS_TYPE + "#audit(String)",
            ANONYMOUS_2 + "#measure() -> " + CALLS_TYPE + "#stamp(String)");
        assertAll(
            () -> assertEquals(11, declared.size(), "declared methods " + declared),
            () -> assertEquals(expected, outgoingPairs, "outgoing pairs"),
            () -> assertEquals(expected, incomingPairs, "incoming pairs"));
    }

    private JsonNode payload(org.javalens.mcp.tools.Tool tool, int[] position) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(tool.execute(position(position[0], position[1]))));
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", calls);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> sourceCallee(String method, String declaringClass, String signature,
                                                    String returnType, String callType, int line, int column) {
        return callee(method, declaringClass, signature, returnType, callType, true, line, column);
    }

    private static Map<String, Object> callee(String method, String declaringClass, String signature,
                                              String returnType, String callType, boolean fromSource,
                                              int line, int column) {
        return map(
            "method", method,
            "declaringClass", declaringClass,
            "signature", signature,
            "returnType", returnType,
            "callType", callType,
            "isFromSource", fromSource,
            "callLocation", map("filePath", CALLS, "line", line, "column", column));
    }

    private static Map<String, Object> analyzedCallee(String name, String kind, String qualifiedType,
                                                      String signature, int callLine) {
        return map("name", name, "kind", kind, "qualifiedType", qualifiedType, "signature", signature,
            "callLine", callLine);
    }

    private static Map<String, Object> directCaller(int line, int column, String context) {
        return map("filePath", CALLS, "line", line, "column", column, "context", context,
            "callerMethod", "direct", "callerSignature", "direct(String)", "callerClass", CALLS_TYPE);
    }

    private static Map<String, Object> incomingMeta(int count) {
        return map("totalCount", count, "returnedCount", count, "truncated", false,
            "suggestedNextTools", INCOMING_NEXT_TOOLS);
    }

    private static Map<String, Object> outgoingMeta(int count) {
        return map("totalCount", count, "returnedCount", count, "suggestedNextTools", OUTGOING_NEXT_TOOLS);
    }
}
