package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * analyze_method on simple-maven. Every expectation is the whole payload, derived from the
 * fixture source and asserted through execute() and through the JSON-RPC envelope. A method's
 * line is where its source range starts, its Javadoc or annotations included, zero-based; callers
 * come in search order (by file path, then position) and callees in source order, each listed
 * once.
 *
 * <p>Calculator.add is called by SearchPatterns.createObjects (line 58) and performCasts (80),
 * UserService.calculateSum (58) and SampleTest.testAddition (35), and calls nothing.
 */
class AnalyzeMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;
    private AnalyzeMethodTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeMethodTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== exact payload helpers ==========

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String SEARCH_PATTERNS = "src/main/java/com/example/SearchPatterns.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    /** Calculator.add: its source range, and so its line, starts at its Javadoc on line 8. */
    private static final Map<String, Object> CALCULATOR_ADD =
        methodInfo("add", "com.example.Calculator", "add(int a, int b): int", "int", CALCULATOR, 8);
    private static final List<Map<String, Object>> ADD_PARAMETERS =
        List.of(parameter("int", "a"), parameter("int", "b"));

    /** A public method's info; its line is where its source range, Javadoc included, starts. */
    private static Map<String, Object> methodInfo(String name, String declaringType, String signature,
                                                  String returnType, String file, int line) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("name", name);
        info.put("constructor", false);
        info.put("modifiers", List.of("public"));
        info.put("declaringType", declaringType);
        info.put("signature", signature);
        info.put("returnType", returnType);
        info.put("file", file);
        info.put("line", line);
        return info;
    }

    private static Map<String, Object> parameter(String type, String name) {
        Map<String, Object> parameter = new java.util.LinkedHashMap<>();
        parameter.put("type", type);
        parameter.put("name", name);
        return parameter;
    }

    /** A callers or callees block: {@code count} of them in all, {@code list} the ones listed. */
    private static Map<String, Object> calls(int count, List<Map<String, Object>> list) {
        Map<String, Object> calls = new java.util.LinkedHashMap<>();
        calls.put("count", count);
        calls.put("list", list);
        return calls;
    }

    private static Map<String, Object> caller(String method, String qualifiedType, String file, int callLine) {
        Map<String, Object> caller = new java.util.LinkedHashMap<>();
        caller.put("method", method);
        caller.put("qualifiedType", qualifiedType);
        caller.put("file", file);
        caller.put("callLine", callLine);
        return caller;
    }

    private static Map<String, Object> callee(String name, String qualifiedType, String signature, int callLine) {
        Map<String, Object> callee = new java.util.LinkedHashMap<>();
        callee.put("name", name);
        callee.put("kind", "METHOD");
        callee.put("qualifiedType", qualifiedType);
        callee.put("signature", signature);
        callee.put("callLine", callLine);
        return callee;
    }

    /**
     * The success payload of a method without declared exceptions; meta counts its callers and
     * callees, all of them and those listed, and reports whether either list is cut.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> analysis(Map<String, Object> method, List<Map<String, Object>> parameters,
                                                Map<String, Object> callers, Map<String, Object> callees,
                                                Map<String, Object> overrides) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("method", method);
        data.put("parameters", parameters);
        data.put("callers", callers);
        data.put("callees", callees);
        data.put("overrides", overrides);
        int total = (int) callers.get("count") + (int) callees.get("count");
        int returned = ((List<Object>) callers.get("list")).size() + ((List<Object>) callees.get("list")).size();
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", returned);
        meta.put("truncated", returned < total);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = envelope.assertEnvelopeFidelity("analyze_method", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static final String ANIMAL = "src/main/java/com/example/Animal.java";
    private static final String USER_SERVICE = "src/main/java/com/example/service/UserService.java";
    private static final String SAMPLE_TEST = "src/test/java/com/example/SampleTest.java";

    private static final Map<String, Object> CREATE_OBJECTS =
        caller("createObjects", "com.example.SearchPatterns", SEARCH_PATTERNS, 58);

    /** Calculator.add with every caller listed. */
    private static final Map<String, Object> ADD_FULL = analysis(CALCULATOR_ADD, ADD_PARAMETERS,
        calls(4, List.of(
            CREATE_OBJECTS,
            caller("performCasts", "com.example.SearchPatterns", SEARCH_PATTERNS, 80),
            caller("calculateSum", "com.example.service.UserService", USER_SERVICE, 58),
            caller("testAddition", "com.example.SampleTest", SAMPLE_TEST, 35))),
        calls(0, List.of()), Map.of());

    private ObjectNode at(String file, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private static Map<String, Object> callee(String name, String kind, String qualifiedType, String signature,
                                              int callLine) {
        Map<String, Object> callee = callee(name, qualifiedType, signature, callLine);
        callee.put("kind", kind);
        return callee;
    }

    /** {@code payload}'s data with the declared exceptions {@code exceptions}. */
    @SuppressWarnings("unchecked")
    private static void withExceptions(Map<String, Object> payload, List<String> exceptions) {
        ((Map<String, Object>) payload.get("data")).put("exceptions", exceptions);
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    @Test @DisplayName("analyzes method comprehensively: Calculator.add's info, parameters, four callers, no callees, no overrides")
    void analyzesMethodComprehensively() {
        assertPayload(at(calculatorPath, 14, 15), ADD_FULL);
    }

    @Test @DisplayName("maxCallers=1 lists the first of Calculator.add's 4 distinct callers, counts all 4 and reports the cut")
    void respectsMaxLimits() {
        ObjectNode args = at(calculatorPath, 14, 15);
        args.put("maxCallers", 1);
        args.put("maxCallees", 1);
        // Callers in search order: SearchPatterns.createObjects, SearchPatterns.performCasts,
        // UserService.calculateSum, SampleTest.testAddition. add calls nothing.
        assertPayload(args, analysis(CALCULATOR_ADD, ADD_PARAMETERS, calls(4, List.of(CREATE_OBJECTS)),
            calls(0, List.of()), Map.of()));
    }

    @Test @DisplayName("maxCallees=1 lists the first of performCasts' 4 distinct callees in source order, counts all 4 and reports the cut")
    void maxCalleesOne_countsEveryCallee() {
        ObjectNode args = at(helper.getFixturePath("simple-maven").resolve(SEARCH_PATTERNS).toString(), 64, 16);
        args.put("maxCallees", 1);
        // performCasts calls println(String) on line 68, println(int) and size() on line 74 and
        // add(int, int) on line 80; nothing calls performCasts.
        Map<String, Object> method = methodInfo("performCasts", "com.example.SearchPatterns",
            "performCasts(Object obj): void", "void", SEARCH_PATTERNS, 61);
        assertPayload(args, analysis(method, List.of(parameter("Object", "obj")), calls(0, List.of()),
            calls(4, List.of(callee("println", "java.io.PrintStream", "println(String)", 68))), Map.of()));
    }

    @Test @DisplayName("missing filePath/line/column each yield exact INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        ObjectNode noColumn = objectMapper.createObjectNode();
        noColumn.put("filePath", calculatorPath);
        noColumn.put("line", 14);
        assertAll(
            () -> assertPayload(objectMapper.createObjectNode(),
                refusal("Invalid parameter 'filePath': Required parameter missing")),
            () -> assertPayload(noLine, refusal("Invalid parameter 'line': Must be >= 0 (zero-based)")),
            () -> assertPayload(noColumn, refusal("Invalid parameter 'column': Must be >= 0 (zero-based)")));
    }

    @Test @DisplayName("a negative maxCallers or maxCallees is INVALID_PARAMETER")
    void negativeLimits_areRefused() {
        ObjectNode callers = at(calculatorPath, 14, 15);
        callers.put("maxCallers", -1);
        ObjectNode callees = at(calculatorPath, 14, 15);
        callees.put("maxCallees", -1);
        assertAll(
            () -> assertPayload(callers, refusal("Invalid parameter 'maxCallers': Must be >= 0; got: -1")),
            () -> assertPayload(callees, refusal("Invalid parameter 'maxCallees': Must be >= 0; got: -1")));
    }

    @Test @DisplayName("non-existent file -> FILE_NOT_FOUND; non-method position -> INVALID_PARAMETER")
    void handlesInvalidInputs() {
        // Line 0 col 0 of Calculator is the package declaration, not a method.
        assertAll(
            () -> assertPayload(at("/nonexistent/File.java", 14, 15), Map.of("success", false, "error", Map.of(
                "code", "FILE_NOT_FOUND",
                "message", "File not found: /nonexistent/File.java",
                "hint", "Verify the file path is correct and the file exists"))),
            () -> assertPayload(at(calculatorPath, 0, 0),
                refusal("Invalid parameter 'position': Position is not on a method")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add aggregate: exact caller files and count with maxCallers=100")
    void calculatorAdd_callersExactFilesAndCount() {
        ObjectNode args = at(calculatorPath, 14, 15);
        args.put("maxCallers", 100);
        assertPayload(args, ADD_FULL);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Method info has name, constructor, modifiers, declaringType, signature, returnType, file, line")
    void method_shape() {
        assertPayload(at(calculatorPath, 14, 15), ADD_FULL);
    }

    @Test
    @DisplayName("Calculator.add has 2 parameters: int a, int b")
    void calculatorAdd_parameters() {
        assertPayload(at(calculatorPath, 14, 15), ADD_FULL);
    }

    @Test
    @DisplayName("callers block has list + count; nothing cut, count equals the list's size")
    void callersBlock_shape() {
        ObjectNode args = at(calculatorPath, 14, 15);
        args.put("maxCallers", 100);
        assertPayload(args, ADD_FULL);
    }

    @Test
    @DisplayName("Calculator.add calls nothing: callees list is empty with count 0")
    void calculatorAdd_calleesEmpty() {
        assertPayload(at(calculatorPath, 14, 15), ADD_FULL);
    }

    @Test
    @DisplayName("Dog.speak override info: overrides Animal.speak, by name and qualified type")
    void dogSpeak_overridesSuperMethod() {
        // Dog.speak (0-based line 22, col 16 = "speak") starts at its @Override on line 21.
        Map<String, Object> method = methodInfo("speak", "com.example.Dog", "speak(): void", "void", ANIMAL, 21);
        assertPayload(at(helper.getFixturePath("simple-maven").resolve(ANIMAL).toString(), 22, 16),
            analysis(method, List.of(), calls(0, List.of()),
                calls(1, List.of(callee("println", "java.io.PrintStream", "println(String)", 23))),
                Map.of("overrides", Map.of("method", "speak", "qualifiedType", "com.example.Animal"))));
    }

    @Test
    @DisplayName("Animal.speak override info: overriddenBy lists Dog.speak, no super method")
    void animalSpeak_overriddenByDog() {
        Map<String, Object> method = methodInfo("speak", "com.example.Animal", "speak(): void", "void", ANIMAL, 7);
        assertPayload(at(helper.getFixturePath("simple-maven").resolve(ANIMAL).toString(), 7, 16),
            analysis(method, List.of(), calls(0, List.of()),
                calls(1, List.of(callee("println", "java.io.PrintStream", "println(String)", 8))),
                Map.of("overriddenBy", List.of(Map.of("method", "speak", "qualifiedType", "com.example.Dog")))));
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.add: analyze_method callers are exactly get_call_hierarchy_incoming's callers (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculatorAdd_callerCountAgreesWithCallHierarchy() {
        // Both tools name the same four calling methods, in the same order.
        ObjectNode args = at(calculatorPath, 14, 15);
        args.put("maxCallers", 100);
        List<Map<String, Object>> detailCallers = (List<Map<String, Object>>)
            getData(new GetCallHierarchyIncomingTool(() -> service).execute(args.deepCopy())).get("callers");
        List<Object> detailMethods = detailCallers.stream().map(caller -> caller.get("callerMethod")).toList();
        assertAll(
            () -> assertPayload(args, ADD_FULL),
            () -> assertEquals(List.of("createObjects", "performCasts", "calculateSum", "testAddition"),
                detailMethods));
    }

    @Test
    @DisplayName("Method with throws clause: exceptions lists IOException and IllegalArgumentException")
    void methodWithThrows_exceptionsBlockPresent() {
        // SearchPatterns.riskyOperation (0-based line 123, col 16 for the name; its Javadoc starts
        // on line 120) declares `throws IOException, IllegalArgumentException`. handleExceptions
        // calls it on line 161; it creates an IllegalArgumentException (125), calls isEmpty()
        // (127) and creates an IOException (128).
        Map<String, Object> method = methodInfo("riskyOperation", "com.example.SearchPatterns",
            "riskyOperation(String input): void", "void", SEARCH_PATTERNS, 120);
        Map<String, Object> expected = analysis(method, List.of(parameter("String", "input")),
            calls(1, List.of(caller("handleExceptions", "com.example.SearchPatterns", SEARCH_PATTERNS, 161))),
            calls(3, List.of(
                callee("IllegalArgumentException", "CONSTRUCTOR", "java.lang.IllegalArgumentException",
                    "IllegalArgumentException(String)", 125),
                callee("isEmpty", "java.lang.String", "isEmpty()", 127),
                callee("IOException", "CONSTRUCTOR", "java.io.IOException", "IOException(String)", 128))),
            Map.of());
        withExceptions(expected, List.of("IOException", "IllegalArgumentException"));
        assertPayload(at(helper.getFixturePath("simple-maven").resolve(SEARCH_PATTERNS).toString(), 123, 16),
            expected);
    }

    @Test
    @DisplayName("Method without throws clause: exceptions list is omitted (not emitted as empty)")
    void methodWithoutThrows_exceptionsBlockOmitted() {
        assertPayload(at(calculatorPath, 14, 15), ADD_FULL);
    }

    @Test
    @DisplayName("Method-reference callee: MethodRefUser.use lists MethodRefTarget::formatId, then IntFunction.apply")
    void methodReferenceInBody_appearsAsCallee() {
        // MethodRefUser.use(int) (0-based line 14, "use" at column 18) captures
        // `MethodRefTarget::formatId` (line 15), a deferred dispatch, and applies it (line 16).
        String user = "src/main/java/com/example/MethodRefUser.java";
        Map<String, Object> method = methodInfo("use", "com.example.MethodRefUser", "use(int id): String", "String",
            user, 14);
        assertPayload(at(helper.getFixturePath("simple-maven").resolve(user).toString(), 14, 18),
            analysis(method, List.of(parameter("int", "id")), calls(0, List.of()), calls(2, List.of(
                callee("formatId", "METHOD_REFERENCE", "com.example.MethodRefTarget", "formatId(int)", 15),
                callee("apply", "java.util.function.IntFunction", "apply(int)", 16))), Map.of()));
    }

    @Test
    @DisplayName("Generic method analysis: method.typeParameters lists U and its bound Comparable<U>")
    void genericMethod_typeParameters_reported() throws Exception {
        // GenericInterfaceExtractTarget.identity is declared `<U extends Comparable<U>> U identity(U input)`
        // on 0-based line 35; its Javadoc starts on line 31.
        String file = "src/main/java/com/example/GenericInterfaceExtractTarget.java";
        String generic = helper.getFixturePath("simple-maven").resolve(file).toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
        int idx = source.indexOf("identity(");
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int col = idx - (source.lastIndexOf('\n', idx) + 1);

        Map<String, Object> method = new java.util.LinkedHashMap<>();
        method.put("name", "identity");
        method.put("constructor", false);
        method.put("modifiers", List.of("public"));
        method.put("declaringType", "com.example.GenericInterfaceExtractTarget");
        method.put("signature", "identity(U input): U");
        method.put("returnType", "U");
        method.put("typeParameters", List.of("U"));
        method.put("typeParameterBounds", Map.of("U", List.of("Comparable<U>")));
        method.put("file", file);
        method.put("line", 31);
        assertPayload(at(generic, line, col), analysis(method, List.of(parameter("U", "input")),
            calls(0, List.of()), calls(0, List.of()), Map.of()));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add has exact signature and two int parameters")
    void envelope_calculatorAdd_exactSignatureAndParams() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        assertPayload(args, ADD_FULL);
    }
}
