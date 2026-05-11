package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindUnusedCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindUnusedCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindUnusedCodeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindUnusedCodeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds unused code comprehensively")
    void findsUnusedCodeComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("unusedItems"));
        assertNotNull(data.get("unusedFieldCount"));
        assertNotNull(data.get("unusedMethodCount"));
        assertNotNull(data.get("totalUnused"));
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        ObjectNode noFields = objectMapper.createObjectNode();
        noFields.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noFields.put("includeFields", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) getData(tool.execute(noFields)).get("unusedItems");
        assertFalse(items1.stream().anyMatch(i -> "Field".equals(i.get("kind"))));

        ObjectNode noMethods = objectMapper.createObjectNode();
        noMethods.put("filePath", "src/main/java/com/example/UnusedCode.java");
        noMethods.put("includeMethods", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) getData(tool.execute(noMethods)).get("unusedItems");
        assertFalse(items2.stream().anyMatch(i -> "Method".equals(i.get("kind"))));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalUnused"));
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    @Test
    @DisplayName("UnusedCode.java: detects unusedField, unusedStringField, unusedPrivateMethod, unusedPrivateMethodWithReturn")
    void unusedCode_detectsExactUnusedMembers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedCode.java");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) getData(r).get("unusedItems");

        java.util.Set<String> names = items.stream()
            .map(i -> (String) i.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // Positive: the four declared-unused private members must appear
        assertTrue(names.contains("unusedField"),
            "unusedField (private, never read) must be reported; got: " + names);
        assertTrue(names.contains("unusedStringField"),
            "unusedStringField (private, never read) must be reported; got: " + names);
        assertTrue(names.contains("unusedPrivateMethod"),
            "unusedPrivateMethod (private, never called) must be reported; got: " + names);
        assertTrue(names.contains("unusedPrivateMethodWithReturn"),
            "unusedPrivateMethodWithReturn (private, never called) must be reported; got: " + names);

        // Negative (isolation): used members must NOT appear
        assertFalse(names.contains("usedField"),
            "usedField is read by usedPrivateMethod — must not be reported as unused");
        assertFalse(names.contains("usedPrivateMethod"),
            "usedPrivateMethod is called by publicMethod — must not be reported as unused");
        assertFalse(names.contains("publicMethod"),
            "publicMethod is public — must not be flagged as unused (public visibility)");
        assertFalse(names.contains("publicField"),
            "publicField is public — must not be flagged as unused");
    }
}
