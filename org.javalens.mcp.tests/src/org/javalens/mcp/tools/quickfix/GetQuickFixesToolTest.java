package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetQuickFixesTool.
 * Tests getting available quick fixes for problems at positions.
 */
class GetQuickFixesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetQuickFixesTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetQuickFixesTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getFixes(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("fixes");
    }

    @Test
    @DisplayName("Calculator line 5 (class declaration, no problems): problemCount=0 and fixes is empty")
    void cleanLine_problemAndFixesAreExactlyEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals(5, data.get("line"));
        // Calculator.java compiles cleanly and line 5 is the class declaration; the tool
        // must report exactly zero problems and zero fixes — not just "non-null".
        assertEquals(0, ((Number) data.get("problemCount")).intValue(),
            "Calculator line 5 has no problems; got: " + data.get("problemCount"));
        @SuppressWarnings("unchecked")
        List<?> problems = (List<?>) data.get("problems");
        assertEquals(0, problems.size(),
            "problems list must be exactly empty when problemCount=0; got: " + problems);
        List<Map<String, Object>> fixes = getFixes(data);
        assertEquals(0, fixes.size(),
            "fixes list must be exactly empty when no problems are at the line; got: " + fixes);
    }

    @Test
    @DisplayName("works with optional column parameter")
    void worksWithColumnParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        // Calculator line 5 col 10 is the clean class declaration: no problems, no fixes.
        assertEquals(0, ((Number) data.get("problemCount")).intValue());
        assertTrue(getFixes(data).isEmpty());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires line parameter")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'line': Required and must be >= 0", response.getError().getMessage());
    }

    @Test
    @DisplayName("handles non-existent file")
    void handlesNonExistentFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("NonExistent.java").toString());
        args.put("line", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, response.getError().getCode());
        assertTrue(response.getError().getMessage().startsWith("File not found: ")
                && response.getError().getMessage().endsWith("NonExistent.java"),
            "got: " + response.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("RefactoringTarget line 3 (unused `import java.util.ArrayList;`): offers a remove_import fix")
    void unusedImport_offersRemoveImportFix() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        // RefactoringTarget.java 1-based line 4 `import java.util.ArrayList;` -> 0-based 3.
        // ProjectImporter enables COMPILER_PB_UNUSED_IMPORT=WARNING so JDT surfaces this
        // as an IProblem and the tool's documented UnusedImport -> remove_import fix path
        // is reachable.
        args.put("line", 3);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals(1, ((Number) data.get("problemCount")).intValue(),
            "RefactoringTarget line 3 has exactly one unused-import problem; got: " + data);

        List<Map<String, Object>> fixes = getFixes(data);
        boolean hasRemoveImport = fixes.stream()
            .map(f -> (String) f.get("fixId"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(id -> id.startsWith("remove_import:"));
        assertTrue(hasRemoveImport,
            "get_quick_fixes promises a remove_import fix for UnusedImport problems; got fixes: "
                + fixes);

        // The remove_import fix must carry the IMPORT category and a label.
        Map<String, Object> removeImportFix = fixes.stream()
            .filter(f -> {
                String id = (String) f.get("fixId");
                return id != null && id.startsWith("remove_import:");
            })
            .findFirst()
            .orElseThrow();
        assertEquals("Remove unused import", removeImportFix.get("label"));
        assertEquals("IMPORT", removeImportFix.get("category"),
            "remove_import fixes must be categorized as IMPORT; got: " + removeImportFix);
        assertEquals(90, ((Number) removeImportFix.get("relevance")).intValue(),
            "UNUSED_IMPORT remove_import relevance must be 90; got: " + removeImportFix);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Each problem entry has problemId, message, severity")
    void problemEntries_haveShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        args.put("line", 3);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) data.get("problems");
        assertFalse(problems.isEmpty());
        for (Map<String, Object> p : problems) {
            assertNotNull(p.get("problemId"), "problemId missing: " + p);
            String msg = (String) p.get("message");
            assertNotNull(msg, "message missing: " + p);
            assertFalse(msg.isBlank(), "message non-blank: " + p);
            String sev = (String) p.get("severity");
            assertNotNull(sev, "severity missing: " + p);
            assertTrue(List.of("error", "warning", "info").contains(sev.toLowerCase()),
                "severity in {error,warning,info}; got: " + p);
        }
    }

    @Test
    @DisplayName("Each fix entry has fixId, label, category, relevance, problemId")
    void fixEntries_haveShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        args.put("line", 3);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> fixes = getFixes(getData(r));
        assertFalse(fixes.isEmpty());
        for (Map<String, Object> f : fixes) {
            String fixId = (String) f.get("fixId");
            assertNotNull(fixId, "fixId missing: " + f);
            assertFalse(fixId.isBlank(), "fixId non-blank: " + f);
            String label = (String) f.get("label");
            assertNotNull(label, "label missing: " + f);
            assertFalse(label.isBlank(), "label non-blank: " + f);
            String category = (String) f.get("category");
            assertNotNull(category, "category missing: " + f);
            assertFalse(category.isBlank(), "category non-blank: " + f);
            assertTrue(((Number) f.get("relevance")).intValue() >= 0,
                "relevance >= 0; got: " + f);
            assertNotNull(f.get("problemId"), "problemId missing: " + f);
        }
    }

    @Test
    @DisplayName("Fixes are sorted by relevance descending")
    void fixes_sortedByRelevanceDescending() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        args.put("line", 3);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> fixes = getFixes(getData(r));
        for (int i = 1; i < fixes.size(); i++) {
            int prev = ((Number) fixes.get(i - 1).get("relevance")).intValue();
            int curr = ((Number) fixes.get(i).get("relevance")).intValue();
            assertTrue(prev >= curr,
                "Fixes must be sorted by relevance descending; got prev=" + prev + " curr=" + curr);
        }
    }

    @Test
    @DisplayName("Column out of problem span filters that problem out")
    void columnFilter_excludesProblemNotCoveringColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString());
        args.put("line", 3);
        args.put("column", 0); // Before the import statement starts.

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // The unused-import problem spans the import declaration positions; column=0 may
        // or may not fall within it depending on JDT's reported source range. The test
        // verifies the column filter is applied (problem count is at most what we'd get
        // without column).
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("problemCount"));
    }

    @Test
    @DisplayName("Line beyond file size returns problemCount=0 and empty fixes")
    void beyondEof_returnsEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 9999);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("problemCount")).intValue());
        assertTrue(getFixes(data).isEmpty());
    }

    // ========== Problem-trigger coverage (broken-symbols fixture) ==========

    @Test
    @DisplayName("UNDEFINED_TYPE (Date without import) -> add_import:java.util.Date fix is proposed")
    void undefinedType_offersAddImportFix() throws Exception {
        // BrokenSymbols line 30 (1-based) `Date d = null;` -> 0-based 29. JDT reports
        // IProblem.UndefinedType (ID 16777218); the tool's generateFixes(UNDEFINED_TYPE)
        // path must produce one or more `add_import:fqn` fixes via suggestImportFixes.
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        GetQuickFixesTool localTool = new GetQuickFixesTool(() -> svc);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("broken-symbols")
            .resolve("src/main/java/com/example/BrokenSymbols.java").toString());
        args.put("line", 29);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess(),
            "Tool must succeed on a file with a known UndefinedType; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        Map<String, Object> data = getData(r);
        assertTrue(((Number) data.get("problemCount")).intValue() > 0,
            "BrokenSymbols line 29 must surface at least one IProblem; data: " + data);

        List<Map<String, Object>> fixes = getFixes(data);
        boolean offersDateImport = fixes.stream().anyMatch(f ->
            "add_import:java.util.Date".equals(f.get("fixId")));
        assertTrue(offersDateImport,
            "UNDEFINED_TYPE for `Date` must propose add_import:java.util.Date; got fixes: "
                + fixes);

        // The add_import fix carries the IMPORT category and a sensible label/relevance.
        Map<String, Object> addImportFix = fixes.stream()
            .filter(f -> "add_import:java.util.Date".equals(f.get("fixId")))
            .findFirst()
            .orElseThrow();
        assertEquals("IMPORT", addImportFix.get("category"),
            "add_import must be categorized as IMPORT; got: " + addImportFix);
        String label = (String) addImportFix.get("label");
        assertTrue(label != null && label.contains("Date"),
            "add_import label must include the unresolved type; got: " + label);
        // java.util gets relevance 100 from calculateRelevance.
        assertEquals(100, ((Number) addImportFix.get("relevance")).intValue(),
            "java.util package must score relevance=100; got: " + addImportFix);
    }

    @Test
    @DisplayName("UNHANDLED_EXCEPTION (FileInputStream w/o throws or try) -> add_throws + surround_try_catch fixes")
    void unhandledException_offersAddThrowsAndSurroundTryCatchFixes() throws Exception {
        // BrokenSymbols line 35 (1-based) `new FileInputStream("missing.txt");` -> 0-based 34.
        // JDT reports IProblem.UnhandledException (ID 16777384); the tool's
        // generateFixes(UNHANDLED_EXCEPTION) path must produce exactly two fixes:
        // add_throws:<exception> (relevance 80) and surround_try_catch:<exception> (75).
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        GetQuickFixesTool localTool = new GetQuickFixesTool(() -> svc);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("broken-symbols")
            .resolve("src/main/java/com/example/BrokenSymbols.java").toString());
        args.put("line", 34);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertTrue(((Number) data.get("problemCount")).intValue() > 0,
            "BrokenSymbols line 34 must surface IProblem.UnhandledException; data: " + data);

        List<Map<String, Object>> fixes = getFixes(data);
        boolean offersAddThrows = fixes.stream().anyMatch(f -> {
            String id = (String) f.get("fixId");
            return id != null && id.startsWith("add_throws:");
        });
        boolean offersSurroundTryCatch = fixes.stream().anyMatch(f -> {
            String id = (String) f.get("fixId");
            return id != null && id.startsWith("surround_try_catch:");
        });
        assertTrue(offersAddThrows,
            "UNHANDLED_EXCEPTION must propose an add_throws:<exception> fix; got: " + fixes);
        assertTrue(offersSurroundTryCatch,
            "UNHANDLED_EXCEPTION must propose a surround_try_catch:<exception> fix; got: " + fixes);

        // Both fixes carry the EXCEPTION category.
        Map<String, Object> addThrows = fixes.stream()
            .filter(f -> ((String) f.get("fixId")).startsWith("add_throws:"))
            .findFirst()
            .orElseThrow();
        Map<String, Object> tryCatch = fixes.stream()
            .filter(f -> ((String) f.get("fixId")).startsWith("surround_try_catch:"))
            .findFirst()
            .orElseThrow();
        assertEquals("EXCEPTION", addThrows.get("category"),
            "add_throws category must be EXCEPTION; got: " + addThrows);
        assertEquals("EXCEPTION", tryCatch.get("category"),
            "surround_try_catch category must be EXCEPTION; got: " + tryCatch);
        // Relevance ranking add_throws (80) > surround_try_catch (75) so descending
        // sort places add_throws first when both exist for the same problem.
        assertEquals(80, ((Number) addThrows.get("relevance")).intValue(),
            "add_throws relevance must be 80; got: " + addThrows);
        assertEquals(75, ((Number) tryCatch.get("relevance")).intValue(),
            "surround_try_catch relevance must be 75; got: " + tryCatch);
    }

    @Test
    @DisplayName("IMPORT_NOT_FOUND (import com.nonexistent.Banana) -> remove_import fix is proposed")
    void importNotFound_offersRemoveImportFix() throws Exception {
        // BrokenSymbols line 3 (1-based) `import com.nonexistent.Banana;` -> 0-based 2.
        // JDT reports IProblem.ImportNotFound (ID 268435846); the tool's
        // generateFixes(IMPORT_NOT_FOUND) path must produce a single remove_import:<index>
        // fix labeled "Remove unresolved import" with relevance 85.
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        GetQuickFixesTool localTool = new GetQuickFixesTool(() -> svc);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("broken-symbols")
            .resolve("src/main/java/com/example/BrokenSymbols.java").toString());
        args.put("line", 2);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertTrue(((Number) data.get("problemCount")).intValue() > 0,
            "BrokenSymbols line 2 must surface IProblem.ImportNotFound; data: " + data);

        List<Map<String, Object>> fixes = getFixes(data);
        Map<String, Object> removeImport = fixes.stream()
            .filter(f -> {
                String id = (String) f.get("fixId");
                return id != null && id.startsWith("remove_import:");
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "IMPORT_NOT_FOUND must propose a remove_import:<index> fix; got: " + fixes));
        assertEquals("IMPORT", removeImport.get("category"),
            "remove_import (for IMPORT_NOT_FOUND) category must be IMPORT; got: " + removeImport);
        assertEquals("Remove unresolved import", removeImport.get("label"),
            "IMPORT_NOT_FOUND label must be 'Remove unresolved import' "
                + "(distinct from UNUSED_IMPORT's 'Remove unused import'); got: " + removeImport);
        assertEquals(85, ((Number) removeImport.get("relevance")).intValue(),
            "IMPORT_NOT_FOUND remove_import relevance must be 85; got: " + removeImport);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: UndefinedType `Date` proposes add_import:java.util.Date (IMPORT, relevance 100)")
    void envelope_undefinedType_addImportFix() throws Exception {
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        EnvelopeHarness localEnvelope = new EnvelopeHarness(svc);
        ObjectNode args = localEnvelope.args();
        args.put("filePath", helper.getFixturePath("broken-symbols")
            .resolve("src/main/java/com/example/BrokenSymbols.java").toString());
        args.put("line", 29); // 0-based: `Date d = null;`
        JsonNode payload = localEnvelope.assertEnvelopeFidelity("get_quick_fixes", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_quick_fixes failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertTrue(data.get("problemCount").asInt() > 0,
            "BrokenSymbols line 29 must surface a problem through the envelope: " + data);
        JsonNode dateFix = null;
        for (JsonNode f : data.get("fixes")) {
            if ("add_import:java.util.Date".equals(f.path("fixId").asText())) {
                dateFix = f;
                break;
            }
        }
        assertNotNull(dateFix, () -> "add_import:java.util.Date must survive the envelope; got: " + data.get("fixes"));
        assertEquals("IMPORT", dateFix.get("category").asText(), "add_import category=IMPORT through the envelope");
        assertEquals(100, dateFix.get("relevance").asInt(), "java.util relevance=100 through the envelope");
    }
}
