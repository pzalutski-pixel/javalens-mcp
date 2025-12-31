package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OrganizeImportsTool.
 * Tests import sorting and unused import removal.
 */
class OrganizeImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private OrganizeImportsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new OrganizeImportsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<String> getUnusedImports(Map<String, Object> data) {
        return (List<String>) data.get("unusedImports");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("organizes imports and returns complete response with all fields")
    void organizeImports_returnsCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify basic info
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("totalImports"));
        assertNotNull(data.get("usedImports"));
        assertNotNull(data.get("hasChanges"));

        // Verify unused imports detection
        List<String> unused = getUnusedImports(data);
        assertNotNull(unused);
        assertTrue(data.get("unusedImports") instanceof List);

        // Verify organized import block
        assertNotNull(data.get("organizedImportBlock"));
        String organizedBlock = (String) data.get("organizedImportBlock");
        // java.* imports should come before other imports
        if (organizedBlock.contains("java.") && organizedBlock.contains("com.")) {
            assertTrue(organizedBlock.indexOf("java.") < organizedBlock.indexOf("com."));
        }
    }

    @Test
    @DisplayName("returns import range with line numbers when imports exist")
    void returnsImportRangeWithLineNumbers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        if ((int) data.get("totalImports") > 0) {
            assertNotNull(data.get("importRange"));
            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) data.get("importRange");
            assertNotNull(range.get("startLine"));
            assertNotNull(range.get("endLine"));
        }
    }

    @Test
    @DisplayName("returns text edit when changes are needed")
    void returnsTextEditWhenChangesNeeded() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        if ((boolean) data.get("hasChanges")) {
            assertNotNull(data.get("textEdit"));
        }
    }

    // ========== File with No Imports Test ==========

    @Test
    @DisplayName("handles file with no imports correctly")
    void handlesFileWithNoImports() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(0, data.get("totalImports"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects empty filePath and non-existent file")
    void rejectsInvalidFilePaths() {
        // Test empty file path
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", "");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Test non-existent file
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", "non/existent/File.java");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }
}
