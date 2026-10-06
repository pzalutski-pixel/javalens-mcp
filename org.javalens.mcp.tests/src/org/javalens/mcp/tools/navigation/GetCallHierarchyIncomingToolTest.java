package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds callers with complete response")
    void findCallersComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("method"));
        assertEquals("com.example.Calculator", data.get("declaringClass"),
            "Calculator.add must report fully-qualified declaring class; got: " + data);
        assertEquals("add(int, int)", data.get("signature"),
            "signature must match the exact method signature; got: " + data.get("signature"));
        // Calculator.add has 4 known call sites in the fixtures
        // (SearchPatterns.createObjects, SearchPatterns.performCasts,
        //  UserService.calculateSum, SampleTest.testAddition).
        assertEquals(4, ((Number) data.get("totalCallers")).intValue(),
            "Calculator.add has exactly 4 callers in fixtures; got: " + data.get("totalCallers"));
        assertTrue(data.get("callers") instanceof List<?>,
            "callers must be a List; got: " + data.get("callers"));
    }

    @Test @DisplayName("maxResults caps add's 4 callers to exactly 2 and flags truncation")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> callers = (List<?>) getData(r).get("callers");
        assertEquals(2, callers.size(), "maxResults=2 caps add's 4 callers to exactly 2");
        assertEquals(4, ((Number) getData(r).get("totalCallers")).intValue(),
            "totalCallers is the pre-clip count");
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    @Test @DisplayName("maxResults=0 and maxResults=1 page add's callers while totalCallers counts all four, through execute() and the envelope")
    void smallMaxResults_countEveryCaller() {
        // add's first caller in search order (SearchPatterns.java sorts before
        // service/UserService.java and the test sources) is createObjects, calling
        // calc.add(1, 2) at line 58, the name at column 26.
        Map<String, Object> firstCaller = new java.util.LinkedHashMap<>();
        firstCaller.put("filePath", "src/main/java/com/example/SearchPatterns.java");
        firstCaller.put("line", 58);
        firstCaller.put("column", 26);
        firstCaller.put("context", "int result = calc.add(1, 2);");
        firstCaller.put("callerMethod", "createObjects");
        firstCaller.put("callerSignature", "createObjects()");
        firstCaller.put("callerClass", "com.example.SearchPatterns");
        assertAll(
            () -> assertCallers(0, List.of()),
            () -> assertCallers(1, List.of(firstCaller)));
    }

    /** add's payload with {@code maxResults}: the given callers, of four in total. */
    private void assertCallers(int maxResults, List<Map<String, Object>> callers) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxResults", maxResults);

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("method", "add");
        data.put("declaringClass", "com.example.Calculator");
        data.put("signature", "add(int, int)");
        data.put("totalCallers", 4);
        data.put("callers", callers);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", 4);
        meta.put("returnedCount", callers.size());
        meta.put("truncated", true);
        meta.put("suggestedNextTools", List.of(
            "get_call_hierarchy_outgoing to see what this method calls",
            "go_to_definition to navigate to a caller"));
        JsonNode expected = objectMapper.valueToTree(Map.of("success", true, "data", data, "meta", meta));

        assertEquals(expected, objectMapper.valueToTree(tool.execute(args.deepCopy())),
            "execute() payload for maxResults=" + maxResults);
        assertEquals(expected, envelope.assertEnvelopeFidelity("get_call_hierarchy_incoming", args),
            "envelope payload for maxResults=" + maxResults);
    }

    @Test @DisplayName("missing filePath / line rejected with INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        ToolResponse rNoFile = tool.execute(noFile);
        assertFalse(rNoFile.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, rNoFile.getError().getCode());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        ToolResponse rNoLine = tool.execute(noLine);
        assertFalse(rNoLine.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, rNoLine.getError().getCode());
    }

    @Test @DisplayName("non-method position (class decl) rejected with INVALID_PARAMETER")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);  // Class declaration, not method
        args.put("column", 13);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("method"),
            "message must explain the position is not a method; got: " + r.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add: callers include UserService.calculateTotal and SearchPatterns.createObjects")
    void calculatorAdd_callersIncludeKnownInvokers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add() at 0-based line 13; "add" name column 15.
        args.put("line", 13);
        args.put("column", 15);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) getData(r).get("callers");

        java.util.Set<String> callerFiles = callers.stream()
            .map(c -> (String) c.get("filePath"))
            .filter(java.util.Objects::nonNull)
            .map(s -> s.replace('\\', '/'))
            .map(s -> s.substring(s.lastIndexOf('/') + 1))
            .collect(java.util.stream.Collectors.toSet());

        // Calculator.add's 4 callers span exactly 3 files: SearchPatterns.java (×2),
        // UserService.java, SampleTest.java.
        assertEquals(java.util.Set.of("SearchPatterns.java", "UserService.java", "SampleTest.java"),
            callerFiles, "exact caller file set; got: " + callerFiles);
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) throw new AssertionError("`" + identifier + "` not in " + filePath);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Caller info includes callerMethod, callerSignature, callerClass, context")
    @SuppressWarnings("unchecked")
    void callerInfo_carriesFullShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callers = (List<Map<String, Object>>) getData(r).get("callers");
        assertFalse(callers.isEmpty());

        Map<String, Object> caller = callers.get(0);
        String callerMethod = (String) caller.get("callerMethod");
        assertNotNull(callerMethod, "Caller info must include callerMethod; got: " + caller);
        assertFalse(callerMethod.isBlank(), "callerMethod must be non-blank; got: " + caller);
        String callerSignature = (String) caller.get("callerSignature");
        assertNotNull(callerSignature, "callerSignature missing: " + caller);
        assertTrue(callerSignature.contains(callerMethod + "("),
            "callerSignature must start with `<callerMethod>(`; got: " + caller);
        String callerClass = (String) caller.get("callerClass");
        assertNotNull(callerClass, "callerClass missing: " + caller);
        assertFalse(callerClass.isBlank(), "callerClass non-blank; got: " + caller);
        String context = (String) caller.get("context");
        assertNotNull(context, "Caller info must include the context line; got: " + caller);
        assertFalse(context.isBlank(), "context must be non-blank source line; got: " + caller);
        assertTrue(((Number) caller.get("line")).intValue() >= 0, "line >= 0; got: " + caller);
        assertTrue(((Number) caller.get("column")).intValue() >= 0, "column >= 0; got: " + caller);
        String filePath = (String) caller.get("filePath");
        assertNotNull(filePath, "filePath missing: " + caller);
        assertTrue(filePath.endsWith(".java"), "filePath must point to .java; got: " + caller);
    }

    @Test
    @DisplayName("Method with no callers (UnusedCode.unusedPrivateMethod) returns empty callers list")
    @SuppressWarnings("unchecked")
    void methodWithNoCallers_emptyList() throws Exception {
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String unusedPath = projectPath.resolve("src/main/java/com/example/UnusedCode.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(unusedPath, "unusedPrivateMethod"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("unusedPrivateMethod", data.get("method"));
        assertEquals(0, ((Number) data.get("totalCallers")).intValue(),
            "Private method that is never called must have 0 callers; got: " + data);
        List<?> callers = (List<?>) data.get("callers");
        assertEquals(0, callers.size());
    }

    @Test
    @DisplayName("Initializer call site reports callerMethod='<initializer>'")
    @SuppressWarnings("unchecked")
    void initializerCaller_isReportedAsAngleBracketsInitializer() throws Exception {
        // TypeKindsFixture has a static `computeInitialLabel()` invoked from a field
        // initializer (`labelFromInitializer = computeInitialLabel()`). The caller
        // must be reported as "<initializer>".
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "computeInitialLabel"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callers = (List<Map<String, Object>>) getData(r).get("callers");
        boolean hasInit = callers.stream()
            .anyMatch(c -> "<initializer>".equals(c.get("callerMethod")));
        assertTrue(hasInit,
            "Field-initializer caller must surface as `<initializer>`; got: " + callers);
    }

    @Test
    @DisplayName("Recursive method shows itself among callers")
    @SuppressWarnings("unchecked")
    void recursiveMethod_includesSelfInCallers() throws Exception {
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "recursiveCountdown"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callers = (List<Map<String, Object>>) getData(r).get("callers");
        boolean hasSelf = callers.stream()
            .anyMatch(c -> "recursiveCountdown".equals(c.get("callerMethod")));
        assertTrue(hasSelf,
            "Recursive method must list itself among callers; got: " + callers);
    }

    @Test
    @DisplayName("Method referenced only via method reference (Foo::method) surfaces its enclosing method as a caller")
    @SuppressWarnings("unchecked")
    void methodReferenceCallSite_surfacesAsCaller() {
        // MethodRefTarget.formatId is used only as `MethodRefTarget::formatId` inside
        // MethodRefUser.use(int). No direct invocation. The incoming-call hierarchy
        // must include MethodRefUser.use as a caller, otherwise method-reference
        // dispatch sites are missing from the call graph.
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String targetPath = projectPath
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", targetPath);
        // 0-based line 8: `    public static String formatId(int id) {`
        // "formatId" starts at column 25.
        args.put("line", 8);
        args.put("column", 25);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on MethodRefTarget.formatId must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        List<Map<String, Object>> callers = (List<Map<String, Object>>) data.get("callers");
        assertNotNull(callers);
        boolean hasUseCaller = callers.stream()
            .anyMatch(c -> "use".equals(c.get("callerMethod"))
                && c.get("callerClass") != null
                && c.get("callerClass").toString().endsWith("MethodRefUser"));
        assertTrue(hasUseCaller,
            "MethodRefUser.use uses MethodRefTarget::formatId — must surface as a caller " +
                "in the incoming hierarchy; got: " + callers);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add has exactly four callers with the exact signature")
    void envelope_calculatorAdd_exactCallerCount() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_call_hierarchy_incoming", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_call_hierarchy_incoming failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("add", data.get("method").asText());
        assertEquals("com.example.Calculator", data.get("declaringClass").asText());
        assertEquals("add(int, int)", data.get("signature").asText());
        assertEquals(4, data.get("totalCallers").asInt(),
            "Calculator.add has exactly four callers — the count must survive the envelope");
    }
}
