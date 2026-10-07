package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_call_hierarchy_incoming on simple-maven. Every expectation is the whole payload, through
 * execute() and through the JSON-RPC envelope. Each call site is listed in search order (by file
 * path, then position) at the called name, with its trimmed source line and the member holding it.
 *
 * <p>Calculator.add is called by SearchPatterns.createObjects (line 58) and performCasts (80),
 * UserService.calculateSum (58) and SampleTest.testAddition (35).
 */
class GetCallHierarchyIncomingToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetCallHierarchyIncomingTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyIncomingTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @Test @DisplayName("finds callers with complete response: Calculator.add's four call sites")
    void findCallersComprehensively() {
        assertPayload(at(calculatorPath, 14, 15, null), addCallers(ADD_CALLERS));
    }

    @Test @DisplayName("maxResults caps add's 4 callers to exactly 2 and flags truncation")
    void supportsMaxResults() {
        assertPayload(at(calculatorPath, 14, 15, 2), addCallers(ADD_CALLERS.subList(0, 2)));
    }

    @Test @DisplayName("maxResults=0 and maxResults=1 page add's callers while totalCallers counts all four, through execute() and the envelope")
    void smallMaxResults_countEveryCaller() {
        // add's first caller in search order (SearchPatterns.java sorts before
        // service/UserService.java and the test sources) is createObjects, calling
        // calc.add(1, 2) at line 58, the name at column 26.
        assertAll(
            () -> assertPayload(at(calculatorPath, 14, 15, 0), addCallers(List.of())),
            () -> assertPayload(at(calculatorPath, 14, 15, 1), addCallers(ADD_CALLERS.subList(0, 1))));
    }

    @Test @DisplayName("missing filePath / line rejected with INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertAll(
            () -> assertPayload(noFile, refusal("Invalid parameter 'filePath': Required parameter missing")),
            () -> assertPayload(noLine, refusal("Invalid parameter 'line': Must be >= 0 (zero-based)")));
    }

    @Test @DisplayName("non-method position (class decl) rejected with INVALID_PARAMETER")
    void handlesNonMethodPosition() {
        // Line 5 is the class declaration, not a method.
        assertPayload(at(calculatorPath, 5, 13, null),
            refusal("Invalid parameter 'position': Symbol at position is not a method"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add, selected in its Javadoc: callers in SearchPatterns (x2), UserService and SampleTest")
    void calculatorAdd_callersIncludeKnownInvokers() {
        // Line 13 lies in add's Javadoc, inside its source range; "add" name column 15.
        assertPayload(at(calculatorPath, 13, 15, 100), addCallers(ADD_CALLERS));
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) throw new AssertionError("`" + identifier + "` not in " + filePath);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        return at(filePath, line, column, null);
    }

    @Test
    @DisplayName("Caller info includes filePath, line, column, context, callerMethod, callerSignature, callerClass")
    void callerInfo_carriesFullShape() {
        assertPayload(at(calculatorPath, 13, 15, 100), addCallers(ADD_CALLERS));
    }

    @Test
    @DisplayName("Method with no callers (UnusedCode.unusedPrivateMethod) returns empty callers list")
    void methodWithNoCallers_emptyList() throws Exception {
        String unusedPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/UnusedCode.java").toString();
        assertPayload(argsAtIdentifier(unusedPath, "unusedPrivateMethod"), callers("unusedPrivateMethod",
            "com.example.UnusedCode", "unusedPrivateMethod()", 0, List.of()));
    }

    @Test
    @DisplayName("Initializer call site reports callerMethod='<initializer>'")
    void initializerCaller_isReportedAsAngleBracketsInitializer() throws Exception {
        // TypeKindsFixture's static computeInitialLabel() is called by the field initializer
        // `labelFromInitializer = computeInitialLabel()` (line 134): the caller is the
        // initializer of TypeKindsFixture, which has no signature.
        String tkf = helper.getFixturePath("simple-maven").resolve(TYPE_KINDS).toString();
        Map<String, Object> initializer = new LinkedHashMap<>();
        initializer.put("filePath", TYPE_KINDS);
        initializer.put("line", 134);
        initializer.put("column", 42);
        initializer.put("context", "private String labelFromInitializer = computeInitialLabel();");
        initializer.put("callerMethod", "<initializer>");
        initializer.put("callerClass", "com.example.TypeKindsFixture");
        assertPayload(argsAtIdentifier(tkf, "computeInitialLabel"), callers("computeInitialLabel",
            "com.example.TypeKindsFixture", "computeInitialLabel()", 1, List.of(initializer)));
    }

    @Test
    @DisplayName("Recursive method shows itself among callers")
    void recursiveMethod_includesSelfInCallers() throws Exception {
        // recursiveCountdown calls itself on line 139 and is called nowhere else.
        String tkf = helper.getFixturePath("simple-maven").resolve(TYPE_KINDS).toString();
        assertPayload(argsAtIdentifier(tkf, "recursiveCountdown"), callers("recursiveCountdown",
            "com.example.TypeKindsFixture", "recursiveCountdown(int)", 1, List.of(
                caller(TYPE_KINDS, 139, 19, "return 1 + recursiveCountdown(n - 1);", "recursiveCountdown",
                    "recursiveCountdown(int)", "com.example.TypeKindsFixture"))));
    }

    @Test
    @DisplayName("Method referenced only via method reference (Foo::method) surfaces its enclosing method as a caller")
    void methodReferenceCallSite_surfacesAsCaller() {
        // MethodRefTarget.formatId is used only as `MethodRefTarget::formatId` inside
        // MethodRefUser.use(int) (line 15); the call site is the method's name.
        String targetPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        // 0-based line 8: `    public static String formatId(int id) {`; "formatId" at column 25.
        assertPayload(at(targetPath, 8, 25, null), callers("formatId", "com.example.MethodRefTarget",
            "formatId(int)", 1, List.of(caller("src/main/java/com/example/MethodRefUser.java", 15, 57,
                "IntFunction<String> formatter = MethodRefTarget::formatId;", "use", "use(int)",
                "com.example.MethodRefUser"))));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add has exactly four callers with the exact signature")
    void envelope_calculatorAdd_exactCallerCount() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        assertPayload(args, addCallers(ADD_CALLERS));
    }

    // ========== exact payload helpers ==========

    private static final String TYPE_KINDS = "src/main/java/com/example/TypeKindsFixture.java";

    private static final List<Map<String, Object>> ADD_CALLERS = List.of(
        caller("src/main/java/com/example/SearchPatterns.java", 58, 26, "int result = calc.add(1, 2);",
            "createObjects", "createObjects()", "com.example.SearchPatterns"),
        caller("src/main/java/com/example/SearchPatterns.java", 80, 17, "calc.add(1, 2);",
            "performCasts", "performCasts(Object)", "com.example.SearchPatterns"),
        caller("src/main/java/com/example/service/UserService.java", 58, 26, "return calculator.add(a, b);",
            "calculateSum", "calculateSum(int, int)", "com.example.service.UserService"),
        caller("src/test/java/com/example/SampleTest.java", 35, 32, "int result = calculator.add(2, 3);",
            "testAddition", "testAddition()", "com.example.SampleTest"));

    private ObjectNode at(String file, int line, int column, Integer maxResults) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        args.put("column", column);
        if (maxResults != null) {
            args.put("maxResults", maxResults);
        }
        return args;
    }

    private static Map<String, Object> caller(String file, int line, int column, String context, String method,
                                              String signature, String callerClass) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("filePath", file);
        caller.put("line", line);
        caller.put("column", column);
        caller.put("context", context);
        caller.put("callerMethod", method);
        caller.put("callerSignature", signature);
        caller.put("callerClass", callerClass);
        return caller;
    }

    /** Calculator.add's payload listing {@code listed} of its four callers. */
    private static Map<String, Object> addCallers(List<Map<String, Object>> listed) {
        return callers("add", "com.example.Calculator", "add(int, int)", 4, listed);
    }

    private static Map<String, Object> callers(String method, String declaringClass, String signature, int total,
                                               List<Map<String, Object>> listed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", method);
        data.put("declaringClass", declaringClass);
        data.put("signature", signature);
        data.put("totalCallers", total);
        data.put("callers", listed);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", listed.size());
        meta.put("truncated", listed.size() < total);
        meta.put("suggestedNextTools", List.of(
            "get_call_hierarchy_outgoing to see what this method calls",
            "go_to_definition to navigate to a caller"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
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
        JsonNode wire = envelope.assertEnvelopeFidelity("get_call_hierarchy_incoming", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
