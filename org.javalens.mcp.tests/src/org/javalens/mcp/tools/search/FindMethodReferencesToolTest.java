package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindMethodReferencesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindMethodReferencesTool tool;
    private ObjectMapper objectMapper;
    private String searchPatternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindMethodReferencesTool(() -> service);
        objectMapper = new ObjectMapper();
        searchPatternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/SearchPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("valid method position returns success with methodName, declaringType, methodReferences list")
    void validMethodPosition_returnsExpectedShape() {
        // Position on Calculator.add — a real method declaration.
        String calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);   // 1-based line 15: `public int add(int a, int b)`
        args.put("column", 15); // on "add"

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on Calculator.add must succeed; got error: "
                + (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("methodName"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        assertNotNull(data.get("methodReferences"),
            "methodReferences must always be a list (possibly empty), not null");
        assertNotNull(data.get("totalMethodReferences"));
    }

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires line")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("column", 5);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires column")
    void requiresColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPatternsPath);
        args.put("line", 10);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("handles non-existent file")
    void handlesNonExistentFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        args.put("line", 10);
        args.put("column", 5);
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("MethodRefTarget.formatId: exactly one method reference at MethodRefUser line 11")
    void methodRefTarget_formatId_hasOneReference() {
        String targetPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", targetPath);
        // 1-based line 9 `public static String formatId(int id) {` -> 0-based 8;
        // identifier "formatId" begins at column 25.
        args.put("line", 8);
        args.put("column", 25);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("formatId", data.get("methodName"));
        assertEquals("com.example.MethodRefTarget", data.get("declaringType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("methodReferences");
        assertEquals(1, ((Number) data.get("totalMethodReferences")).intValue(),
            "MethodRefUser uses `MethodRefTarget::formatId` exactly once; got: "
                + data.get("totalMethodReferences") + " (" + refs + ")");

        // The single match must come from MethodRefUser.java.
        Map<String, Object> ref = refs.get(0);
        String filePath = (String) ref.get("filePath");
        assertNotNull(filePath);
        String normalized = filePath.replace('\\', '/');
        assertTrue(normalized.endsWith("MethodRefUser.java"),
            "Method reference must be located in MethodRefUser.java; got: " + filePath);
    }

    @Test
    @DisplayName("Calculator.add has no method-reference usages in fixtures (isolation)")
    void calculatorAdd_hasNoMethodReferences() {
        String calcPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calcPath);
        // Calculator.add() declared at line 14 (0-based 13). Position on "add" identifier.
        args.put("line", 13);
        args.put("column", 15);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // No project code uses `Calculator::add` as a method reference. SearchPatterns
        // uses other JDK method references (String::valueOf, ArrayList::new, etc.) but
        // not Calculator::add.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("methodReferences");
        assertNotNull(refs);
        assertEquals(0, refs.size(),
            "Calculator.add is never used as a method reference; got: " + refs);
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
    @DisplayName("Bound instance method ref: MethodRefTarget.greet used via `instance::greet`")
    @SuppressWarnings("unchecked")
    void boundInstanceMethodRef_isFound() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tgt));
        int idx = source.indexOf("public String greet");
        idx = source.indexOf("greet", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tgt);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("greet", data.get("methodName"));
        List<Map<String, Object>> refs = (List<Map<String, Object>>) data.get("methodReferences");
        // MethodRefUser uses `instance::greet` once.
        assertEquals(1, refs.size(),
            "greet should have exactly one bound method-reference usage; got: " + refs);
    }

    @Test
    @DisplayName("Constructor reference: MethodRefTarget::new is found when positioning on the constructor")
    @SuppressWarnings("unchecked")
    void constructorRef_isFound() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tgt));
        int idx = source.indexOf("public MethodRefTarget()");
        idx = source.indexOf("MethodRefTarget(", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tgt);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on constructor must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<Map<String, Object>> refs = (List<Map<String, Object>>) getData(r).get("methodReferences");
        assertNotNull(refs);
        // MethodRefUser uses `MethodRefTarget::new` once. The tool's method-reference
        // search may or may not match constructors depending on JDT semantics — if
        // implemented correctly, at least one match should appear.
        assertTrue(refs.size() >= 1,
            "MethodRefTarget::new must surface as a method reference; got: " + refs);
    }

    @Test
    @DisplayName("Reference info includes filePath, line, column")
    @SuppressWarnings("unchecked")
    void referenceInfo_includesLocation() throws Exception {
        String tgt = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tgt, "formatId"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> refs = (List<Map<String, Object>>) getData(r).get("methodReferences");
        assertFalse(refs.isEmpty());
        Map<String, Object> ref = refs.get(0);
        assertNotNull(ref.get("filePath"));
        assertNotNull(ref.get("line"));
        assertNotNull(ref.get("column"));
    }
}
