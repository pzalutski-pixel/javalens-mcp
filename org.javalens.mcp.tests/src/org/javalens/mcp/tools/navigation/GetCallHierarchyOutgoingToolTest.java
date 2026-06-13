package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetCallHierarchyOutgoingToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetCallHierarchyOutgoingTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyOutgoingTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds callees with complete response")
    void findCalleesComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 20);  // processInput method that calls other methods
        args.put("column", 18);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        String method = (String) data.get("method");
        assertNotNull(method, "method missing");
        assertFalse(method.isBlank(), "method non-blank; got: " + data);
        String declaringClass = (String) data.get("declaringClass");
        assertNotNull(declaringClass, "declaringClass missing");
        assertTrue(declaringClass.contains("."), "declaringClass FQN; got: " + data);
        assertNotNull(data.get("signature"), "signature missing");

        int totalCallees = ((Number) data.get("totalCallees")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callees = (List<Map<String, Object>>) data.get("callees");
        assertNotNull(callees, "callees list missing");
        assertEquals(totalCallees, callees.size(),
            "totalCallees must equal callees list size; got: " + data);
        if (!callees.isEmpty()) {
            Map<String, Object> callee = callees.get(0);
            String calleeMethod = (String) callee.get("method");
            assertNotNull(calleeMethod, "callee method missing: " + callee);
            assertFalse(calleeMethod.isBlank(), "callee method non-blank: " + callee);
            assertNotNull(callee.get("declaringClass"), "callee declaringClass missing: " + callee);
            assertNotNull(callee.get("callType"), "callee callType missing: " + callee);
        }
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 20);
        noFile.put("column", 18);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", refactoringTargetPath);
        noLine.put("column", 18);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);  // Field declaration
        args.put("column", 19);

        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("RefactoringTarget.printMessages: callees include formatMessage")
    void printMessages_calleesIncludeFormatMessage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        // printMessages declaration at 1-based line 79 -> 0-based 78; method name "printMessages" at column 17.
        args.put("line", 78);
        args.put("column", 17);
        args.put("maxResults", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("printMessages", data.get("method"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callees = (List<Map<String, Object>>) data.get("callees");
        java.util.Set<String> calleeNames = callees.stream()
            .map(c -> (String) c.get("method"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(calleeNames.contains("formatMessage"),
            "printMessages calls formatMessage twice — must appear in callees; got: " + calleeNames);
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
    @DisplayName("SearchPatterns.createObjects: callees include CONSTRUCTOR calls (new ArrayList, new HashMap, new Calculator)")
    @SuppressWarnings("unchecked")
    void createObjects_includesConstructorCalls() throws Exception {
        String sp = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/SearchPatterns.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(sp, "createObjects"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callees = (List<Map<String, Object>>) getData(r).get("callees");

        // Find CONSTRUCTOR callType entries.
        java.util.Set<String> constructorTargets = callees.stream()
            .filter(c -> "CONSTRUCTOR".equals(c.get("callType")))
            .map(c -> (String) c.get("method"))
            .collect(java.util.stream.Collectors.toSet());
        assertFalse(constructorTargets.isEmpty(),
            "createObjects has multiple `new` calls — at least one CONSTRUCTOR callee expected; got: " + callees);
    }

    @Test
    @DisplayName("TypeKindsFixture.labelWithSuper: callees include SUPER_METHOD invocation of toString")
    @SuppressWarnings("unchecked")
    void labelWithSuper_includesSuperMethodCall() throws Exception {
        String tkf = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "labelWithSuper"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callees = (List<Map<String, Object>>) getData(r).get("callees");
        boolean hasSuper = callees.stream()
            .anyMatch(c -> "SUPER_METHOD".equals(c.get("callType")));
        assertTrue(hasSuper,
            "labelWithSuper calls super.toString() — SUPER_METHOD callType must appear; got: " + callees);
    }

    @Test
    @DisplayName("Recursive method shows itself among callees")
    @SuppressWarnings("unchecked")
    void recursiveMethod_listsSelfAsCallee() throws Exception {
        String tkf = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "recursiveCountdown"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> callees = (List<Map<String, Object>>) getData(r).get("callees");
        boolean hasSelf = callees.stream()
            .anyMatch(c -> "recursiveCountdown".equals(c.get("method")));
        assertTrue(hasSelf,
            "Recursive method must list itself as callee; got: " + callees);
    }

    @Test
    @DisplayName("Method with no callees (Calculator.getLastResult — just returns a field) has empty callees")
    void methodWithNoCallees_emptyList() {
        String calc = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        // Calculator.getLastResult() body is just `return lastResult;` — no method calls.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calc);
        // 1-based line 46 `public int getLastResult()` -> 0-based 45. Column 15 on "getLastResult".
        args.put("line", 45);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("totalCallees")).intValue(),
            "Calculator.getLastResult has no method/constructor calls; got: " + data);
    }

    @Test
    @DisplayName("Method reference (MethodRefTarget::formatId) in body must appear as a callee of MethodRefUser.use")
    @SuppressWarnings("unchecked")
    void methodReferenceInBody_surfacesAsCallee() {
        // MethodRefUser.use(int id) captures `MethodRefTarget::formatId` as an
        // IntFunction. The reference is the dispatch target — any code that
        // invokes the function will run formatId. The outgoing call hierarchy
        // for `use` must list formatId as a callee, otherwise the dispatch
        // graph is incomplete.
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String userPath = projectPath
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
        Map<String, Object> data = getData(r);
        List<Map<String, Object>> callees = (List<Map<String, Object>>) data.get("callees");
        assertNotNull(callees);
        boolean hasFormatId = callees.stream()
            .anyMatch(c -> "formatId".equals(c.get("method"))
                && c.get("declaringClass") != null
                && c.get("declaringClass").toString().endsWith("MethodRefTarget"));
        assertTrue(hasFormatId,
            "MethodRefTarget::formatId reference in MethodRefUser.use's body must surface " +
                "as a callee — method references are deferred-invocation dispatch sites; " +
                "got: " + callees);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.getLastResult has zero callees")
    void envelope_getLastResult_zeroCallees() {
        String calc = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = envelope.args();
        args.put("filePath", calc);
        args.put("line", 45);
        args.put("column", 15);
        JsonNode payload = envelope.payload("get_call_hierarchy_outgoing", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_call_hierarchy_outgoing failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("getLastResult", data.get("method").asText());
        assertEquals(0, data.get("totalCallees").asInt(),
            "getLastResult just returns a field — its zero-callee count must survive the envelope");
    }
}
