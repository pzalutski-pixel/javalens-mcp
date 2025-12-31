package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractConstantTool.
 * Tests expression extraction to static final constant.
 */
class ExtractConstantToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractConstantTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractConstantTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edits");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts expression to static final constant with complete response")
    void extractsExpressionToConstantWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);  // "PREFIX_" string literal
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify constant info
        assertEquals("DEFAULT_PREFIX", data.get("constantName"));
        assertNotNull(data.get("constantType"));

        // Verify edit structure
        assertNotNull(data.get("edits"));
        List<Map<String, Object>> edits = getEdits(data);
        assertFalse(edits.isEmpty());

        // The declaration edit should contain static final
        boolean hasStaticFinal = edits.stream()
            .anyMatch(e -> {
                String newText = (String) e.get("newText");
                return newText != null && newText.contains("static") && newText.contains("final");
            });
        assertTrue(hasStaticFinal);
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires constantName parameter")
    void requiresConstantName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        // No constantName provided

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid constant names")
    void rejectsInvalidConstantNames() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "123INVALID");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
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
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
