package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new OrganizeImportsTool(() -> service);
        envelope = new EnvelopeHarness(service);
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
        String fp = (String) data.get("filePath");
        assertNotNull(fp, "filePath missing");
        assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + fp);
        assertTrue(((Number) data.get("totalImports")).intValue() >= 0,
            "totalImports >= 0; got: " + data);
        assertTrue(((Number) data.get("usedImports")).intValue() >= 0,
            "usedImports >= 0; got: " + data);
        assertTrue(data.get("hasChanges") instanceof Boolean,
            "hasChanges must be Boolean; got: " + data);

        // Verify unused imports detection
        assertTrue(data.get("unusedImports") instanceof List);

        // Verify organized import block — non-blank string of imports
        String organizedBlock = (String) data.get("organizedImportBlock");
        assertNotNull(organizedBlock, "organizedImportBlock missing");
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
            @SuppressWarnings("unchecked")
            Map<String, Object> range = (Map<String, Object>) data.get("importRange");
            assertNotNull(range, "importRange missing when imports exist");
            int startLine = ((Number) range.get("startLine")).intValue();
            int endLine = ((Number) range.get("endLine")).intValue();
            assertTrue(startLine >= 0, "startLine >= 0; got: " + range);
            assertTrue(endLine >= startLine, "endLine >= startLine; got: " + range);
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
        // RefactoringTarget imports java.util.{List,ArrayList,Map,HashMap} + java.io.IOException;
        // only List is used, so the organized block is EXACTLY the single surviving import
        // (all four unused dropped, nothing else added).
        assertEquals("import java.util.List;", block.strip(),
            "organized block must be exactly the one used import; got: " + block);
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

    @Test
    @DisplayName("Static imports survive verbatim (NOT pruned by usage check) and land at the end of the block")
    void staticImports_preservedVerbatimAtEnd() {
        // OrganizeImportsFixture imports two static methods:
        //   import static java.lang.Math.PI;   (used in usePi())
        //   import static java.lang.Math.max;  (unused)
        // The source's `if (imp.isStatic())` branch collects all static imports
        // unconditionally — `max` survives despite being unused, and they appear
        // at the END of the organized block (after regular + on-demand).
        String fixturePath = projectPath
            .resolve("src/main/java/com/example/OrganizeImportsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String block = (String) getData(r).get("organizedImportBlock");
        assertNotNull(block);
        assertTrue(block.contains("import static java.lang.Math.PI"),
            "Used static import must appear; got: " + block);
        assertTrue(block.contains("import static java.lang.Math.max"),
            "Unused static import survives (organize_imports does NOT prune static); got: " + block);

        // Static-imports-at-end ordering: every static import line must come AFTER every
        // non-static import line in the organized output.
        int firstStatic = block.indexOf("import static");
        int lastNonStatic = -1;
        for (String line : block.split("\n")) {
            if (line.startsWith("import ") && !line.startsWith("import static")) {
                int pos = block.indexOf(line);
                if (pos > lastNonStatic) lastNonStatic = pos;
            }
        }
        assertTrue(firstStatic > lastNonStatic,
            "Static imports must appear AFTER all non-static imports in the block; got: " + block);
    }

    @Test
    @DisplayName("On-demand (wildcard) imports survive verbatim and are not pruned by usage check")
    void onDemandImports_preservedVerbatim() {
        // OrganizeImportsFixture has `import java.util.concurrent.*` (used via Executor).
        // The source's `else if (imp.isOnDemand())` branch collects it as `name + ".*"`
        // and emits without usage checking. Pin both presence and the `.*` suffix.
        String fixturePath = projectPath
            .resolve("src/main/java/com/example/OrganizeImportsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String block = (String) getData(r).get("organizedImportBlock");
        assertNotNull(block);
        assertTrue(block.contains("import java.util.concurrent.*"),
            "On-demand import must appear with explicit `.*` suffix; got: " + block);
    }

    @Test
    @DisplayName("Regular usage discrimination on the fixture: ArrayList is unused, List is used")
    void regularImports_filteredByUsage() {
        // OrganizeImportsFixture imports java.util.List (used as return type) AND
        // java.util.ArrayList (never referenced). The `referencedTypes.contains(...)`
        // check must mark only ArrayList as unused.
        String fixturePath = projectPath
            .resolve("src/main/java/com/example/OrganizeImportsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fixturePath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        List<String> unused = getUnusedImports(data);
        assertTrue(unused.contains("java.util.ArrayList"),
            "ArrayList is never referenced — must be flagged unused; got: " + unused);
        assertFalse(unused.contains("java.util.List"),
            "List is used as a return type — must NOT be flagged unused; got: " + unused);
        // Static and on-demand are NOT subject to unused filtering.
        assertFalse(unused.stream().anyMatch(u -> u.contains("Math")),
            "Static imports are never in unusedImports; got: " + unused);
        assertFalse(unused.stream().anyMatch(u -> u.contains("java.util.concurrent")),
            "On-demand imports are never in unusedImports; got: " + unused);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: RefactoringTarget unused imports are exactly {ArrayList, Map, HashMap, IOException}")
    void envelope_refactoringTarget_unusedImportsExactSet() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        JsonNode payload = envelope.assertEnvelopeFidelity("organize_imports", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "organize_imports failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        java.util.Set<String> unused = new java.util.TreeSet<>();
        for (JsonNode imp : data.get("unusedImports")) unused.add(imp.asText());
        assertEquals(
            java.util.Set.of("java.util.ArrayList", "java.util.Map",
                "java.util.HashMap", "java.io.IOException"),
            unused,
            "the unused-import set must survive the envelope exactly (List excluded); got: " + unused);
    }
}
