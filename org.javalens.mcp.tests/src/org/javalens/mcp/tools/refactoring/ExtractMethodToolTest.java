package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractMethodTool.
 * Tests code block extraction to new method.
 */
class ExtractMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractMethodTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts code block to method with complete response")
    void extractsCodeBlockWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);  // Start of sum calculation loop
        args.put("startColumn", 8);
        args.put("endLine", 47);    // End of loop
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify method info
        assertEquals("calculateSum", data.get("methodName"));
        assertNotNull(data.get("parameters"));
        assertNotNull(data.get("returnType"));

        // Verify edit structure
        assertNotNull(data.get("newMethodCode"));
        String declaration = (String) data.get("newMethodCode");
        assertTrue(declaration.contains("calculateSum"));
        assertNotNull(data.get("methodCall"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires methodName parameter")
    void requiresMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        // No methodName

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid method names and reserved words")
    void rejectsInvalidMethodNames() {
        // Test invalid identifier (starts with number)
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("startLine", 44);
        args1.put("startColumn", 8);
        args1.put("endLine", 47);
        args1.put("endColumn", 9);
        args1.put("methodName", "123invalid");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Test reserved word
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("startLine", 44);
        args2.put("startColumn", 8);
        args2.put("endLine", 47);
        args2.put("endColumn", 9);
        args2.put("methodName", "while");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    @Test
    @DisplayName("handles invalid range gracefully")
    void handlesInvalidRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", -1);
        args.put("startColumn", -1);
        args.put("endLine", -1);
        args.put("endColumn", -1);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
