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
}
