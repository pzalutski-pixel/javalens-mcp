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
        // RefactoringTarget imports 5 (List + ArrayList/Map/HashMap/IOException); only List used.
        assertEquals(5, ((Number) data.get("totalImports")).intValue());
        assertEquals(1, ((Number) data.get("usedImports")).intValue());
        assertEquals(Boolean.TRUE, data.get("hasChanges"));

        // After organizing, the block is exactly the single surviving import.
        String organizedBlock = (String) data.get("organizedImportBlock");
        assertEquals("import java.util.List;", organizedBlock.strip(),
            "organized block is the one used import; got: " + organizedBlock);
    }

    @Test
    @DisplayName("returns import range with line numbers when imports exist")
    void returnsImportRangeWithLineNumbers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) data.get("importRange");
        assertNotNull(range, "importRange must be present when imports exist");
        // RefactoringTarget's 5 imports span 0-based lines 2-6 (offsets 24..154).
        assertEquals(2, ((Number) range.get("startLine")).intValue());
        assertEquals(6, ((Number) range.get("endLine")).intValue());
        assertEquals(24, ((Number) range.get("startOffset")).intValue());
        assertEquals(154, ((Number) range.get("endOffset")).intValue());
    }

    @Test
    @DisplayName("returns text edit when changes are needed")
    void returnsTextEditWhenChangesNeeded() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        // RefactoringTarget has 4 unused imports, so changes ARE needed.
        assertEquals(Boolean.TRUE, data.get("hasChanges"));
        assertNotNull(data.get("textEdit"), "textEdit must be present when changes are needed");
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
        // Exactly the four unused; List (used by calculateTotal) is excluded.
        assertEquals(java.util.Set.of(
            "java.util.ArrayList", "java.util.Map", "java.util.HashMap", "java.io.IOException"),
            new java.util.HashSet<>(unused), "got: " + unused);
        assertEquals(4, unused.size());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects empty filePath (INVALID_PARAMETER) and non-existent file (FILE_NOT_FOUND)")
    void rejectsInvalidFilePaths() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", "");
        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response1.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response1.getError().getMessage());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", "non/existent/File.java");
        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, response2.getError().getCode());
        assertEquals("File not found: non/existent/File.java", response2.getError().getMessage());
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
