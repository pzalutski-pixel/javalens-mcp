package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for GetQuickFixesTool: the problems at a line and the fixes for them, on
 * simple-maven's Calculator and RefactoringTarget and on the broken-symbols fixture. Every
 * expectation is the whole payload, through execute() and through the JSON-RPC envelope.
 *
 * <p>RefactoringTarget imports, in order, List, ArrayList (line 3, never used), Map, HashMap and
 * IOException. BrokenSymbols imports the unresolvable com.nonexistent.Banana (line 2), names
 * Date without importing it (line 29) and creates a FileInputStream, whose constructor throws
 * FileNotFoundException, without handling it or closing the stream (line 34). The JDK 21 types
 * named Date are java.util.Date and java.sql.Date.
 */
class GetQuickFixesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetQuickFixesTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetQuickFixesTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @Test
    @DisplayName("Calculator line 5 (class declaration, no problems): problemCount=0 and fixes is empty")
    void cleanLine_problemAndFixesAreExactlyEmpty() {
        assertPayload(tool, envelope, at(calculatorPath, 5, null), fixes(CALCULATOR, 5, List.of(), List.of()));
    }

    @Test
    @DisplayName("works with optional column parameter: Calculator line 5 column 10 has no problem")
    void worksWithColumnParameter() {
        assertPayload(tool, envelope, at(calculatorPath, 5, 10), fixes(CALCULATOR, 5, List.of(), List.of()));
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 0);
        assertPayload(tool, envelope, args, refusal("Invalid parameter 'filePath': Required"));
    }

    @Test
    @DisplayName("requires line parameter")
    void requiresLine() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        assertPayload(tool, envelope, args, refusal("Invalid parameter 'line': Required and must be >= 0"));
    }

    @Test
    @DisplayName("handles non-existent file: exact FILE_NOT_FOUND naming the path given")
    void handlesNonExistentFile() {
        String missing = projectPath.resolve("NonExistent.java").toString();
        assertPayload(tool, envelope, at(missing, 0, null), Map.of("success", false, "error", Map.of(
            "code", "FILE_NOT_FOUND",
            "message", "File not found: " + missing,
            "hint", "Verify the file path is correct and the file exists")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("RefactoringTarget line 3 (unused `import java.util.ArrayList;`): offers a remove_import fix")
    void unusedImport_offersRemoveImportFix() {
        // ProjectImporter enables COMPILER_PB_UNUSED_IMPORT=WARNING so JDT surfaces this as an
        // IProblem and the documented UnusedImport -> remove_import fix path is reachable.
        assertPayload(tool, envelope, refactoringTargetLine3(null), unusedArrayListImport());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Each problem entry has problemId, message, severity")
    void problemEntries_haveShape() {
        assertPayload(tool, envelope, refactoringTargetLine3(null), unusedArrayListImport());
    }

    @Test
    @DisplayName("Each fix entry has fixId, label, category, relevance, problemId")
    void fixEntries_haveShape() {
        assertPayload(tool, envelope, refactoringTargetLine3(null), unusedArrayListImport());
    }

    @Test
    @DisplayName("Fixes are sorted by relevance descending: BrokenSymbols line 34 lists add_throws (80) before surround_try_catch (75)")
    void fixes_sortedByRelevanceDescending() throws Exception {
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        assertPayload(new GetQuickFixesTool(() -> svc), new EnvelopeHarness(svc), brokenSymbolsLine(34),
            unhandledException());
    }

    @Test
    @DisplayName("Column out of problem span filters that problem out: column 0 lies before the unused import's name")
    void columnFilter_excludesProblemNotCoveringColumn() {
        // The unused-import problem spans the imported name, columns 7 to 25.
        assertPayload(tool, envelope, refactoringTargetLine3(0), fixes(REFACTORING_TARGET, 3, List.of(), List.of()));
    }

    @Test
    @DisplayName("Line beyond file size returns problemCount=0 and empty fixes")
    void beyondEof_returnsEmpty() {
        assertPayload(tool, envelope, at(calculatorPath, 9999, null), fixes(CALCULATOR, 9999, List.of(), List.of()));
    }

    // ========== Problem-trigger coverage (broken-symbols fixture) ==========

    @Test
    @DisplayName("UNDEFINED_TYPE (Date without import) -> add_import:java.util.Date, then add_import:java.sql.Date")
    void undefinedType_offersAddImportFix() throws Exception {
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        assertPayload(new GetQuickFixesTool(() -> svc), new EnvelopeHarness(svc), brokenSymbolsLine(29),
            undefinedDate());
    }

    @Test
    @DisplayName("UNHANDLED_EXCEPTION (FileInputStream w/o throws or try) -> add_throws + surround_try_catch fixes")
    void unhandledException_offersAddThrowsAndSurroundTryCatchFixes() throws Exception {
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        assertPayload(new GetQuickFixesTool(() -> svc), new EnvelopeHarness(svc), brokenSymbolsLine(34),
            unhandledException());
    }

    @Test
    @DisplayName("IMPORT_NOT_FOUND (import com.nonexistent.Banana) -> remove_import fix is proposed")
    void importNotFound_offersRemoveImportFix() throws Exception {
        // JDT resolves the package com (com.example exists) and reports the import at
        // com.nonexistent, the first segment it cannot resolve.
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        assertPayload(new GetQuickFixesTool(() -> svc), new EnvelopeHarness(svc), brokenSymbolsLine(2),
            fixes(BROKEN_SYMBOLS, 2,
                List.of(problem(IMPORT_NOT_FOUND, "The import com.nonexistent cannot be resolved", "error")),
                List.of(fix("remove_import:0", "Remove unresolved import", "IMPORT", 85, IMPORT_NOT_FOUND))));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: UndefinedType `Date` proposes add_import:java.util.Date (IMPORT, relevance 100)")
    void envelope_undefinedType_addImportFix() throws Exception {
        JdtServiceImpl svc = helper.loadProject("broken-symbols");
        EnvelopeHarness localEnvelope = new EnvelopeHarness(svc);
        ObjectNode args = localEnvelope.args();
        args.put("filePath", helper.getFixturePath("broken-symbols").resolve(BROKEN_SYMBOLS).toString());
        args.put("line", 29); // 0-based: `Date d = null;`
        assertPayload(new GetQuickFixesTool(() -> svc), localEnvelope, args, undefinedDate());
    }

    // ========== exact payload helpers ==========

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String REFACTORING_TARGET = "src/main/java/com/example/RefactoringTarget.java";
    private static final String BROKEN_SYMBOLS = "src/main/java/com/example/BrokenSymbols.java";

    /** IProblem.UnusedImport, ImportNotFound, UndefinedType, UnhandledException and UnclosedCloseable. */
    private static final int UNUSED_IMPORT = 268435844;
    private static final int IMPORT_NOT_FOUND = 268435846;
    private static final int UNDEFINED_TYPE = 16777218;
    private static final int UNHANDLED_EXCEPTION = 16777384;
    private static final int UNCLOSED_CLOSEABLE = 536871799;

    private ObjectNode at(String file, int line, Integer column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        if (column != null) {
            args.put("column", column);
        }
        return args;
    }

    private ObjectNode refactoringTargetLine3(Integer column) {
        return at(projectPath.resolve(REFACTORING_TARGET).toString(), 3, column);
    }

    private ObjectNode brokenSymbolsLine(int line) {
        return at(helper.getFixturePath("broken-symbols").resolve(BROKEN_SYMBOLS).toString(), line, null);
    }

    /** RefactoringTarget line 3: ArrayList, the second import, is never used. */
    private static Map<String, Object> unusedArrayListImport() {
        return fixes(REFACTORING_TARGET, 3,
            List.of(problem(UNUSED_IMPORT, "The import java.util.ArrayList is never used", "warning")),
            List.of(fix("remove_import:1", "Remove unused import", "IMPORT", 90, UNUSED_IMPORT)));
    }

    /** BrokenSymbols line 29: Date is unresolved; java.util ranks 100, java.sql 70. */
    private static Map<String, Object> undefinedDate() {
        return fixes(BROKEN_SYMBOLS, 29,
            List.of(problem(UNDEFINED_TYPE, "Date cannot be resolved to a type", "error")),
            List.of(
                fix("add_import:java.util.Date", "Import 'Date' (java.util)", "IMPORT", 100, UNDEFINED_TYPE),
                fix("add_import:java.sql.Date", "Import 'Date' (java.sql)", "IMPORT", 70, UNDEFINED_TYPE)));
    }

    /**
     * BrokenSymbols line 34: the unhandled FileNotFoundException, named qualified in the problem's
     * arguments, gets two fixes; the stream, created and never assigned, is never closed, which
     * has no fix. The flow analysis meets the exception first.
     */
    private static Map<String, Object> unhandledException() {
        String exception = "java.io.FileNotFoundException";
        return fixes(BROKEN_SYMBOLS, 34,
            List.of(
                problem(UNHANDLED_EXCEPTION, "Unhandled exception type FileNotFoundException", "error"),
                problem(UNCLOSED_CLOSEABLE, "Resource leak: '<unassigned Closeable value>' is never closed",
                    "warning")),
            List.of(
                fix("add_throws:" + exception, "Add throws declaration for " + exception, "EXCEPTION", 80,
                    UNHANDLED_EXCEPTION),
                fix("surround_try_catch:" + exception, "Surround with try-catch for " + exception, "EXCEPTION", 75,
                    UNHANDLED_EXCEPTION)));
    }

    private static Map<String, Object> problem(int problemId, String message, String severity) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("problemId", problemId);
        problem.put("message", message);
        problem.put("severity", severity);
        return problem;
    }

    private static Map<String, Object> fix(String fixId, String label, String category, int relevance, int problemId) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("fixId", fixId);
        fix.put("label", label);
        fix.put("category", category);
        fix.put("relevance", relevance);
        fix.put("problemId", problemId);
        return fix;
    }

    /** The success payload of {@code problems} at {@code line} and every one of their {@code fixes}. */
    private static Map<String, Object> fixes(String file, int line, List<Map<String, Object>> problems,
                                             List<Map<String, Object>> fixes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filePath", file);
        data.put("line", line);
        data.put("problemCount", problems.size());
        data.put("problems", problems);
        data.put("fixes", fixes);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", fixes.size());
        meta.put("returnedCount", fixes.size());
        meta.put("truncated", false);
        meta.put("suggestedNextTools", List.of(
            "apply_quick_fix with fixId to apply a fix",
            "get_diagnostics to see all problems"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    /**
     * The payload of {@code fixesTool} through execute(), read back from the JSON text it
     * serializes to as a client reads it, and through {@code harness}'s JSON-RPC envelope both
     * equal {@code expected}.
     */
    private void assertPayload(GetQuickFixesTool fixesTool, EnvelopeHarness harness, ObjectNode args,
                               Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(fixesTool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = harness.assertEnvelopeFidelity("get_quick_fixes", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
