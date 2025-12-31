package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChangeMethodSignatureTool.
 * Tests method signature changes and call site updates.
 */
class ChangeMethodSignatureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ChangeMethodSignatureTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("renames method and updates call sites with complete response")
    void renamesMethodWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);  // formatMessage method
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify rename info
        assertEquals("formatOutput", data.get("newName"));

        // Verify edit structure
        assertNotNull(data.get("editsByFile"));
        assertNotNull(data.get("totalEdits"));
        assertNotNull(data.get("filesAffected"));
        assertTrue((int) data.get("totalEdits") > 1);  // Declaration + call sites
    }

    @Test
    @DisplayName("adds new parameter with default value")
    void addsParameterWithDefaultValue() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "message");
        param1.put("type", "String");
        params.add(param1);

        ObjectNode param2 = objectMapper.createObjectNode();
        param2.put("name", "count");
        param2.put("type", "int");
        params.add(param2);

        ObjectNode param3 = objectMapper.createObjectNode();
        param3.put("name", "prefix");
        param3.put("type", "String");
        param3.put("defaultValue", "\"\"");
        params.add(param3);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("editsByFile"));
    }

    @Test
    @DisplayName("removes parameter from method signature")
    void removesParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        // Keep only first parameter (message)
        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "message");
        param1.put("type", "String");
        params.add(param1);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("reorders parameters in method signature")
    void reordersParameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        // Swap parameter order
        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "count");
        param1.put("type", "int");
        params.add(param1);

        ObjectNode param2 = objectMapper.createObjectNode();
        param2.put("name", "message");
        param2.put("type", "String");
        params.add(param2);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("changes method return type")
    void changesReturnType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newReturnType", "void");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("void", data.get("newReturnType"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires at least one change to be specified")
    void requiresAtLeastOneChange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        // No newName, newReturnType, or newParameters

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", -1);
        args.put("column", -1);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles non-method position gracefully")
    void handlesNotAMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 14);  // Field declaration
        args.put("column", 19);
        args.put("newName", "newFieldName");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
