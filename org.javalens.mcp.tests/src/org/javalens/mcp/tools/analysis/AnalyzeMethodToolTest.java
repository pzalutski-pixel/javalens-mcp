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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMethod(Map<String, Object> d) { return (Map<String, Object>) d.get("method"); }

    @Test @DisplayName("analyzes method comprehensively")
    void analyzesMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, Object> method = getMethod(data);

        // Method info — exact contract
        assertEquals("add", method.get("name"));
        assertEquals("add(int a, int b): int", method.get("signature"));
        assertEquals("com.example.Calculator", method.get("declaringType"));
        assertEquals("int", method.get("returnType"));

        // Parameters: Calculator.add(int a, int b) has exactly 2
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) data.get("parameters");
        assertNotNull(parameters, "parameters list missing");
        assertEquals(2, parameters.size(),
            "Calculator.add has 2 parameters; got: " + parameters);

        // Call hierarchy — each block must be a structured map with a `list`
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) data.get("callers");
        assertNotNull(callers, "callers block missing");
        assertNotNull(callers.get("list"), "callers.list missing; got: " + callers);
        @SuppressWarnings("unchecked")
        Map<String, Object> callees = (Map<String, Object>) data.get("callees");
        assertNotNull(callees, "callees block missing");
        assertNotNull(callees.get("list"), "callees.list missing; got: " + callees);
        // overrides may be a single map (if it overrides) or null/empty
        // marker (if it doesn't); presence is the contract.
        assertNotNull(data.get("overrides"), "overrides field missing (may be null/empty marker)");
    }

    @Test @DisplayName("respects max limits")
    void respectsMaxLimits() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 1);
        args.put("maxCallees", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<?> callerList = (List<?>) callers.get("list");
        assertTrue(callerList.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column")
    void requiresParameters() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());

        ObjectNode noColumn = objectMapper.createObjectNode();
        noColumn.put("filePath", calculatorPath);
        noColumn.put("line", 14);
        assertFalse(tool.execute(noColumn).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        // Non-existent file
        ObjectNode badFile = objectMapper.createObjectNode();
        badFile.put("filePath", "/nonexistent/File.java");
        badFile.put("line", 14);
        badFile.put("column", 15);
        assertFalse(tool.execute(badFile).isSuccess());

        // Position not on method
        ObjectNode notMethod = objectMapper.createObjectNode();
        notMethod.put("filePath", calculatorPath);
        notMethod.put("line", 0);
        notMethod.put("column", 0);
        assertFalse(tool.execute(notMethod).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add aggregate: callers include UserService and SearchPatterns files")
    void calculatorAdd_callersIncludeKnownInvokers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) callers.get("list");

        // AnalyzeMethodTool emits the per-caller file as `file` (not `filePath`).
        java.util.Set<String> callerFiles = list.stream()
            .map(c -> (String) c.get("file"))
            .filter(java.util.Objects::nonNull)
            .map(s -> s.replace('\\', '/'))
            .map(s -> s.substring(s.lastIndexOf('/') + 1))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(callerFiles.contains("UserService.java"),
            "UserService.calculateTotal calls Calculator.add — analyze_method aggregate must surface it; got: " + callerFiles);
        assertTrue(callerFiles.contains("SearchPatterns.java"),
            "SearchPatterns.createObjects calls Calculator.add — analyze_method aggregate must surface it; got: " + callerFiles);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Method info has name, signature, declaringType, returnType, modifiers")
    @SuppressWarnings("unchecked")
    void method_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> method = getMethod(getData(r));
        for (String key : List.of("name", "signature", "declaringType", "returnType", "modifiers")) {
            assertNotNull(method.get(key), key + " missing on method: " + method);
        }
    }

    @Test
    @DisplayName("Calculator.add has 2 parameters: int a, int b")
    void calculatorAdd_parameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) getData(r).get("parameters");
        assertEquals(2, params.size(),
            "Calculator.add(int a, int b) has 2 parameters; got: " + params);
        // Exact parameter shape: first is (int a), second is (int b).
        assertEquals("a", params.get(0).get("name"), "first param name; got: " + params.get(0));
        assertEquals("int", params.get(0).get("type"), "first param type; got: " + params.get(0));
        assertEquals("b", params.get(1).get("name"), "second param name; got: " + params.get(1));
        assertEquals("int", params.get(1).get("type"), "second param type; got: " + params.get(1));
    }

    @Test
    @DisplayName("callers block has list + count; count equals list.size()")
    void callersBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<?> list = (List<?>) callers.get("list");
        assertNotNull(list);
        Object count = callers.get("count");
        if (count != null) {
            assertEquals(((Number) count).intValue(), list.size(),
                "callers.count must equal callers.list.size(); got: " + callers);
        }
    }

    @Test
    @DisplayName("callees block present; entries reference target methods")
    void calleesBlock_present() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callees = (Map<String, Object>) getData(r).get("callees");
        assertNotNull(callees);
        assertNotNull(callees.get("list"));
    }

    @Test
    @DisplayName("overrides block present (may be empty for top-level methods)")
    void overridesBlock_present() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("overrides"));
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.add: analyze_method callers count agrees with get_call_hierarchy_incoming (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculatorAdd_callerCountAgreesWithCallHierarchy() throws Exception {
        // If analyze_method's callers count drifts from get_call_hierarchy_incoming,
        // one of the tools is wrong about what calls Calculator.add. This test pins
        // them together.
        GetCallHierarchyIncomingTool detail = new GetCallHierarchyIncomingTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);

        Map<String, Object> aggregateData = getData(tool.execute(args));
        Map<String, Object> detailData = getData(detail.execute(args));

        Map<String, Object> aggregateCallers = (Map<String, Object>) aggregateData.get("callers");
        List<?> aggregateList = (List<?>) aggregateCallers.get("list");
        List<?> detailCallers = (List<?>) detailData.get("callers");

        assertNotNull(aggregateList, "aggregate callers.list must be present; got: " + aggregateCallers);
        assertNotNull(detailCallers, "detail callers list must be present; got: " + detailData);
        assertEquals(detailCallers.size(), aggregateList.size(),
            "analyze_method.callers.list.size() must equal get_call_hierarchy_incoming.callers.size(); "
                + "aggregate=" + aggregateList.size() + " detail=" + detailCallers.size());
    }

    @Test
    @DisplayName("Method with throws clause: exceptions list is present with the declared exception types")
    @SuppressWarnings("unchecked")
    void methodWithThrows_exceptionsBlockPresent() {
        // SearchPatterns.riskyOperation declares `throws IOException, IllegalArgumentException`.
        // Source line 131-137: emits the `exceptions` list only when exceptions.length > 0.
        // Position on the method name `riskyOperation` (0-based line 123, col 16 for the name).
        String searchPatternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/SearchPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("line", 123);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "riskyOperation analyze must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<String> exceptions = (List<String>) getData(r).get("exceptions");
        assertNotNull(exceptions,
            "exceptions list must be present for a method declaring throws; got null");
        assertTrue(exceptions.contains("IOException"),
            "exceptions list must include IOException; got: " + exceptions);
        assertTrue(exceptions.contains("IllegalArgumentException"),
            "exceptions list must include IllegalArgumentException; got: " + exceptions);
    }

    @Test
    @DisplayName("Method without throws clause: exceptions list is omitted (not emitted as empty)")
    @SuppressWarnings("unchecked")
    void methodWithoutThrows_exceptionsBlockOmitted() {
        // Calculator.add() declares no throws clause. Source line 131-137 emits the
        // `exceptions` field ONLY when exceptions.length > 0. Pin the omission contract.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertFalse(data.containsKey("exceptions"),
            "Methods without throws must omit the exceptions field entirely (not emit []); got: "
                + data.get("exceptions"));
    }

    @Test
    @DisplayName("Method-reference callee: analyzing MethodRefUser.use must list MethodRefTarget.formatId among callees")
    @SuppressWarnings("unchecked")
    void methodReferenceInBody_appearsAsCallee() {
        // MethodRefUser.use(int) captures `MethodRefTarget::formatId` as an IntFunction.
        // The reference is a deferred dispatch — when the functional interface is
        // applied, formatId runs. The callees list must therefore include formatId
        // alongside any direct invocations.
        String userPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefUser.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userPath);
        // 0-based line 14: `    public String use(int id) {` — "use" at column 18.
        args.put("line", 14);
        args.put("column", 18);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on MethodRefUser.use must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> callees = (Map<String, Object>) getData(r).get("callees");
        assertNotNull(callees);
        List<Map<String, Object>> calleeList = (List<Map<String, Object>>) callees.get("list");
        boolean hasFormatId = calleeList.stream()
            .anyMatch(c -> "formatId".equals(c.get("name"))
                && c.get("qualifiedType") != null
                && c.get("qualifiedType").toString().endsWith("MethodRefTarget"));
        assertTrue(hasFormatId,
            "MethodRefTarget::formatId in MethodRefUser.use's body must appear in callees " +
                "(deferred-invocation dispatch site); got: " + calleeList);
    }

    @Test
    @DisplayName("Generic method analysis: method.typeParameters includes the method-level type parameter")
    @SuppressWarnings("unchecked")
    void genericMethod_typeParameters_reported() throws Exception {
        // GenericInterfaceExtractTarget.identity is declared as
        // `<U extends Comparable<U>> U identity(U input)`. analyze_method
        // must report U as a method type parameter; without it a consumer
        // sees the method as non-generic.
        String generic = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
        int idx = source.indexOf("identity(");
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int col = idx - (source.lastIndexOf('\n', idx) + 1);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", line);
        args.put("column", col);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> method = getMethod(getData(r));
        assertEquals("identity", method.get("name"));

        List<String> typeParameters = (List<String>) method.get("typeParameters");
        assertNotNull(typeParameters,
            "Method analysis must report typeParameters for a generic method; got: " + method);
        assertEquals(List.of("U"), typeParameters,
            "identity declares <U ...>; method.typeParameters must list U; got: " + typeParameters);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add has exact signature and two int parameters")
    void envelope_calculatorAdd_exactSignatureAndParams() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        JsonNode payload = envelope.payload("analyze_method", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "analyze_method failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        JsonNode method = data.get("method");
        assertEquals("add", method.get("name").asText());
        assertEquals("add(int a, int b): int", method.get("signature").asText(),
            "exact method signature must survive the envelope");
        assertEquals("int", method.get("returnType").asText());
        JsonNode params = data.get("parameters");
        assertEquals(2, params.size(), "Calculator.add has two parameters");
        assertEquals("a", params.get(0).get("name").asText());
        assertEquals("int", params.get(0).get("type").asText());
        assertEquals("b", params.get(1).get("name").asText());
        assertEquals("int", params.get(1).get("type").asText());
    }
}
