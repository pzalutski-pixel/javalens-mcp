package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
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
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyIncomingTool(() -> service);
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

    @Test @DisplayName("supports maxResults parameter")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxResults", 5);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> callers = (List<?>) getData(r).get("callers");
        assertTrue(callers.size() <= 5);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);  // Class declaration, not method
        args.put("column", 13);

        assertFalse(tool.execute(args).isSuccess());
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

        assertTrue(callerFiles.contains("UserService.java"),
            "UserService.calculateTotal calls Calculator.add — must appear; got: " + callerFiles);
        assertTrue(callerFiles.contains("SearchPatterns.java"),
            "SearchPatterns.createObjects calls Calculator.add — must appear; got: " + callerFiles);
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
}
