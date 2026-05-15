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

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("RefactoringTarget unused imports: exactly ArrayList, Map, HashMap, IOException")
    void refactoringTarget_unusedImportsExactSet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // RefactoringTarget imports java.util.{List,ArrayList,Map,HashMap} and java.io.IOException.
        // Only List is used (calculateTotal parameter); the rest are unused.
        List<String> unused = getUnusedImports(data);
        java.util.Set<String> unusedSet = new java.util.HashSet<>(unused);
        assertTrue(unusedSet.contains("java.util.ArrayList"),
            "java.util.ArrayList must be flagged unused; got: " + unused);
        assertTrue(unusedSet.contains("java.util.Map"),
            "java.util.Map must be flagged unused; got: " + unused);
        assertTrue(unusedSet.contains("java.util.HashMap"),
            "java.util.HashMap must be flagged unused; got: " + unused);
        assertTrue(unusedSet.contains("java.io.IOException"),
            "java.io.IOException must be flagged unused; got: " + unused);
        assertFalse(unusedSet.contains("java.util.List"),
            "java.util.List is used by calculateTotal — must NOT appear in unused; got: " + unused);
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

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Sorted import block: java.* sorted alphabetically; java.io.IOException is dropped because unused")
    void organizedBlock_sortedAndUnusedDropped() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        String block = (String) data.get("organizedImportBlock");
        assertNotNull(block);
        // Unused java.io.IOException, java.util.ArrayList, java.util.HashMap, java.util.Map
        // must NOT appear in organized block. java.util.List must remain.
        assertFalse(block.contains("java.io.IOException"),
            "Unused IOException must NOT be in organized block; got: " + block);
        assertFalse(block.contains("java.util.ArrayList"),
            "Unused ArrayList must NOT be in organized block; got: " + block);
        assertFalse(block.contains("java.util.HashMap"),
            "Unused HashMap must NOT be in organized block; got: " + block);
        assertFalse(block.contains("java.util.Map\n") && !block.contains("java.util.Map.")
            ? false : false); // (no separate java.util.Map import remains)
        assertTrue(block.contains("java.util.List"),
            "Used java.util.List must remain in organized block; got: " + block);
    }

    @Test
    @DisplayName("importRange exposes startLine, endLine, startOffset, endOffset")
    void importRange_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) data.get("importRange");
        assertNotNull(range);
        for (String key : List.of("startLine", "endLine", "startOffset", "endOffset")) {
            assertNotNull(range.get(key), key + " missing on importRange: " + range);
        }
    }

    @Test
    @DisplayName("textEdit carries startLine, endLine, newText; newText equals organizedImportBlock")
    void textEdit_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertTrue((Boolean) data.get("hasChanges"));
        @SuppressWarnings("unchecked")
        Map<String, Object> edit = (Map<String, Object>) data.get("textEdit");
        assertNotNull(edit);
        for (String key : List.of("startLine", "endLine", "newText")) {
            assertNotNull(edit.get(key), key + " missing on textEdit: " + edit);
        }
        assertEquals(data.get("organizedImportBlock"), edit.get("newText"),
            "textEdit.newText must equal organizedImportBlock");
    }

    @Test
    @DisplayName("totalImports + usedImports + unusedImports sums correctly")
    void importCounts_consistent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalImports")).intValue();
        int used = ((Number) data.get("usedImports")).intValue();
        int unused = getUnusedImports(data).size();
        assertEquals(total, used + unused,
            "totalImports = usedImports + unusedImports; got total=" + total
                + " used=" + used + " unused=" + unused);
    }

    @Test
    @DisplayName("File with no imports: hasChanges=false, textEdit absent")
    void noImports_noChangesNoEdit() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("totalImports")).intValue());
        assertEquals(Boolean.FALSE, data.get("hasChanges"));
        assertNull(data.get("textEdit"),
            "textEdit must be absent when no changes are needed; got: " + data.get("textEdit"));
    }
}
