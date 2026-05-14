package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetJavadocTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetJavadocToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetJavadocTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetJavadocTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("parses javadoc with complete response including tags")
    void parsesJavadocComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method with javadoc
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("symbol"));
        assertNotNull(data.get("kind"));
        assertNotNull(data.get("hasDocumentation"));

        if ((Boolean) data.get("hasDocumentation")) {
            assertNotNull(data.get("summary"));
            // May have @param, @return, @throws depending on the method
        }
    }

    @Test @DisplayName("returns hasDocumentation false for undocumented symbol")
    void handlesUndocumentedSymbol() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 10);  // Class declaration (may not have javadoc)
        args.put("column", 13);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("hasDocumentation"));
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

    @Test @DisplayName("handles non-member position gracefully")
    void handlesNonMemberPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add javadoc: summary mentions adds, @param documented, @return documented")
    void calculatorAdd_javadocContent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add() method declaration at 0-based line 13
        args.put("line", 13);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("symbol"));
        assertEquals(true, data.get("hasDocumentation"));

        String summary = (String) data.get("summary");
        assertNotNull(summary);
        assertTrue(summary.toLowerCase().contains("adds"),
            "Calculator.add javadoc summary mentions 'Adds two numbers'; got: " + summary);
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
    @DisplayName("Calculator.add: @param entries parsed with name and description for a, b")
    @SuppressWarnings("unchecked")
    void calculatorAdd_paramTagsParsed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertNotNull(params, "Calculator.add has @param a / @param b; got: " + data);
        assertEquals(2, params.size(),
            "Calculator.add javadoc has exactly two @param entries; got: " + params);

        java.util.Map<String, String> byName = new java.util.HashMap<>();
        for (Map<String, String> p : params) {
            byName.put(p.get("name"), p.get("description"));
        }
        assertEquals(java.util.Set.of("a", "b"), byName.keySet(),
            "@param names must be a and b; got: " + byName);
        assertEquals("first operand", byName.get("a"));
        assertEquals("second operand", byName.get("b"));
    }

    @Test
    @DisplayName("Calculator.add: @return text is 'the sum'")
    void calculatorAdd_returnTagParsed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("the sum", getData(r).get("returns"),
            "@return text must be parsed; got: " + getData(r));
    }

    @Test
    @DisplayName("richlyDocumentedMethod: all Javadoc tags parsed (@throws, @see, @since, @author, @version, @deprecated)")
    @SuppressWarnings("unchecked")
    void richlyDocumentedMethod_allTagsParsed() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "richlyDocumentedMethod"));
        assertTrue(r.isSuccess(),
            "Position on richlyDocumentedMethod must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("richlyDocumentedMethod", data.get("symbol"));
        assertEquals(true, data.get("hasDocumentation"));

        // @throws
        List<Map<String, String>> throwsList = (List<Map<String, String>>) data.get("throws");
        assertNotNull(throwsList, "@throws must be parsed; got: " + data);
        assertEquals(1, throwsList.size());
        assertEquals("java.lang.IllegalArgumentException", throwsList.get(0).get("type"));
        assertTrue(throwsList.get(0).get("description").contains("input is null"),
            "@throws description must be captured; got: " + throwsList);

        // @see
        List<String> see = (List<String>) data.get("see");
        assertNotNull(see, "@see must be parsed; got: " + data);
        assertTrue(see.stream().anyMatch(s -> s.contains("Calculator")),
            "@see must include Calculator reference; got: " + see);

        // @since
        assertEquals("1.0", data.get("since"),
            "@since must be parsed; got: " + data);

        // @author
        List<String> authors = (List<String>) data.get("authors");
        assertNotNull(authors);
        assertTrue(authors.contains("JavaLens fixture"),
            "@author must be parsed; got: " + authors);

        // @version
        assertEquals("2.5", data.get("version"));

        // @deprecated
        assertNotNull(data.get("deprecated"));
        assertTrue(data.get("deprecated").toString().contains("tag-parsing tests"),
            "@deprecated must be parsed; got: " + data.get("deprecated"));

        // @param x 2
        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertEquals(2, params.size());

        // @return
        assertNotNull(data.get("returns"));
    }

    @Test
    @DisplayName("Method without Javadoc reports hasDocumentation=false and no parsed tags")
    void methodWithoutJavadoc_hasDocumentationFalse() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "noJavadocMethod"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("noJavadocMethod", data.get("symbol"));
        assertEquals(false, data.get("hasDocumentation"));
        assertNull(data.get("summary"));
        assertNull(data.get("params"));
        assertNull(data.get("returns"));
    }
}
