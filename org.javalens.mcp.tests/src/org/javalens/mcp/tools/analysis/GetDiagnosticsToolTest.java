package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_diagnostics on simple-maven's Calculator and RefactoringTarget, and project-wide on the
 * capped-lists and legacy-compliance-maven projects, whose every diagnostic can be read from
 * their source. Every expectation is the whole payload, through execute() and through the
 * JSON-RPC envelope. Offsets are read from the fixture as checked out, so line endings do not
 * matter; JDT reports an unused import on the imported name, an unused field on its name, an
 * unresolved type on its name and a missing semicolon on the token before it, each end offset
 * inclusive. A missing semicolon is a recovery of the enclosing BlockStatements scope.
 *
 * <p>RefactoringTarget never uses its imports of ArrayList, Map, HashMap and IOException (lines
 * 3 to 6) nor reads its private constant MAX_SIZE (line 19). capped-lists: Assembly.java names
 * the unresolved type Widget (line 7) in Assembly and never reads Spare's private field spare
 * (line 15); its other 18 files have no diagnostic.
 */
class GetDiagnosticsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDiagnosticsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiagnosticsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        refactoringTargetPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @Test
    @DisplayName("a syntax-broken file is reported with its syntax error, single-file and project-wide")
    void syntaxError_reportedAsError() throws Exception {
        JdtServiceImpl mutableService = helper.loadProjectCopy("capped-lists");
        Path hot = mutableService.getProjectRoot().resolve(HOT);
        Files.writeString(hot, Files.readString(hot).replace("return 1;", "return 1"));
        String hotSource = Files.readString(hot);
        int one = hotSource.indexOf("return 1") + "return ".length();
        Map<String, Object> syntaxError = diagnostic(HOT, 8, one, one, "error",
            "Syntax error, insert \";\" to complete BlockStatements", INSERT_TO_COMPLETE, "SYNTAX");

        GetDiagnosticsTool mutableTool = new GetDiagnosticsTool(() -> mutableService);
        EnvelopeHarness mutableEnvelope = new EnvelopeHarness(mutableService);
        String assembly = Files.readString(mutableService.getProjectRoot().resolve(ASSEMBLY));

        ObjectNode fileArgs = objectMapper.createObjectNode();
        fileArgs.put("filePath", hot.toString());
        ObjectNode projectArgs = objectMapper.createObjectNode();
        assertAll(
            () -> assertPayload(mutableTool, mutableEnvelope, fileArgs,
                diagnostics(1, 0, 1, List.of(syntaxError)), UnaryOperator.identity()),
            () -> assertPayload(mutableTool, mutableEnvelope, projectArgs,
                diagnostics(2, 1, 19, List.of(syntaxError, widgetError(assembly), spareWarning(assembly))),
                byFile()));
    }

    @Test
    @DisplayName("a project declaring a pre-1.8 compiler level still reports syntax errors, and the load says it analyzes at 1.8")
    void legacyCompliance_syntaxErrorStillReported() throws Exception {
        // Modern JDT does not support compliance below 1.8. Legacy enterprise poms still
        // declare 1.6/1.7; applying that level verbatim must not silently disable problem
        // detection, so the load clamps it to 1.8 and says so.
        JdtServiceImpl legacyService = helper.loadProjectCopy("legacy-compliance-maven");
        Path old = legacyService.getProjectRoot().resolve("src/main/java/com/legacy/Old.java");
        Files.writeString(old, Files.readString(old).replace("+ name;", "+ name"));
        String oldSource = Files.readString(old);
        int name = oldSource.indexOf("+ name") + "+ ".length();

        Map<String, Object> syntaxError = diagnostic("src/main/java/com/legacy/Old.java", 5, name,
            name + "name".length() - 1, "error", "Syntax error, insert \";\" to complete BlockStatements",
            INSERT_TO_COMPLETE, "SYNTAX");
        assertPayload(new GetDiagnosticsTool(() -> legacyService), new EnvelopeHarness(legacyService),
            objectMapper.createObjectNode(), diagnostics(1, 0, 1, List.of(syntaxError)), UnaryOperator.identity());

        List<List<String>> warnings = legacyService.getWarnings().stream()
            .map(w -> List.of(w.code(), w.message(), w.remediation()))
            .toList();
        assertEquals(List.of(List.of(LoadWarning.COMPLIANCE_LEVEL_UNSUPPORTED,
                "Declared Java source level 1.6 is outside JDT's supported range; analyzing at 1.8 instead",
                "JDT supports compliance 1.8 through " + JavaCore.latestSupportedJavaVersion()
                    + ". Diagnostics reflect level 1.8; code valid only under the older level may show "
                    + "additional errors.")),
            warnings);
    }

    @Test @DisplayName("Calculator.java has no diagnostic")
    void getsDiagnosticsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        assertPayload(args, diagnostics(0, 0, 1, List.of()));
    }

    @Test @DisplayName("severity=error on Calculator gives no diagnostic; maxResults=1 on RefactoringTarget lists its first warning and reports the cut")
    void supportsSeverityFilterAndMaxResults() throws Exception {
        ObjectNode withSeverity = objectMapper.createObjectNode();
        withSeverity.put("filePath", calculatorPath);
        withSeverity.put("severity", "error");

        ObjectNode withMax = objectMapper.createObjectNode();
        withMax.put("filePath", refactoringTargetPath);
        withMax.put("maxResults", 1);
        assertAll(
            () -> assertPayload(withSeverity, diagnostics(0, 0, 1, List.of())),
            () -> assertCut(withMax, List.of(refactoringTargetWarnings().get(0))));
    }

    @Test @DisplayName("Without filePath every file of capped-lists is checked: the unresolved Widget and the unused field")
    void analyzesWholeProject() throws Exception {
        JdtServiceImpl capped = helper.loadProject("capped-lists");
        String assembly = Files.readString(capped.getProjectRoot().resolve(ASSEMBLY));
        assertPayload(new GetDiagnosticsTool(() -> capped), new EnvelopeHarness(capped),
            objectMapper.createObjectNode(),
            diagnostics(1, 1, 19, List.of(widgetError(assembly), spareWarning(assembly))), byFile());
    }

    @Test @DisplayName("non-existent file -> exact FILE_NOT_FOUND")
    void handlesInvalidFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        assertPayload(args, Map.of("success", false, "error", Map.of(
            "code", "FILE_NOT_FOUND",
            "message", "File not found: /nonexistent/File.java",
            "hint", "Verify the file path is correct and the file exists")));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("RefactoringTarget.java with severity=warning: its four unused imports and its unused constant")
    void refactoringTarget_unusedImportWarnings() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("severity", "warning");
        args.put("maxResults", 100);
        assertPayload(args, diagnostics(0, 5, 1, refactoringTargetWarnings()));
    }

    @Test
    @DisplayName("Unused-import warnings sit at exact 0-based lines 3 to 6 (severity given as Warning)")
    void refactoringTarget_unusedImports_exactZeroBasedLines() throws Exception {
        // Imports (0-based): List (2, used), ArrayList (3), Map (4), HashMap (5), IOException (6).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("severity", "Warning");
        args.put("maxResults", 100);
        assertPayload(args, diagnostics(0, 5, 1, refactoringTargetWarnings()));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: maxResults=5, exactly RefactoringTarget's five warnings, lists them all, not truncated")
    void envelope_unusedImports_exactLines() throws Exception {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("severity", "warning");
        args.put("maxResults", 5);
        assertPayload(args, diagnostics(0, 5, 1, refactoringTargetWarnings()));
    }

    @Test
    @DisplayName("Each diagnostic entry carries filePath, line, startOffset, endOffset, severity, message, problemId, category")
    void diagnosticEntry_includesFullShape() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("maxResults", 100);
        assertPayload(args, diagnostics(0, 5, 1, refactoringTargetWarnings()));
    }

    @Test
    @DisplayName("severity='error' over capped-lists returns only the unresolved Widget")
    void severityErrorOnly() throws Exception {
        JdtServiceImpl capped = helper.loadProject("capped-lists");
        String assembly = Files.readString(capped.getProjectRoot().resolve(ASSEMBLY));
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "error");
        args.put("maxResults", 100);
        assertPayload(new GetDiagnosticsTool(() -> capped), new EnvelopeHarness(capped), args,
            diagnostics(1, 0, 19, List.of(widgetError(assembly))), UnaryOperator.identity());
    }

    @Test
    @DisplayName("severity='warning' over capped-lists returns only the unused field")
    void severityWarningOnly() throws Exception {
        JdtServiceImpl capped = helper.loadProject("capped-lists");
        String assembly = Files.readString(capped.getProjectRoot().resolve(ASSEMBLY));
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "warning");
        args.put("maxResults", 100);
        assertPayload(new GetDiagnosticsTool(() -> capped), new EnvelopeHarness(capped), args,
            diagnostics(0, 1, 19, List.of(spareWarning(assembly))), UnaryOperator.identity());
    }

    @Test
    @DisplayName("totalDiagnostics == diagnostics.size(); errorCount + warningCount == totalDiagnostics for severity=all over capped-lists")
    void counts_consistent() throws Exception {
        JdtServiceImpl capped = helper.loadProject("capped-lists");
        String assembly = Files.readString(capped.getProjectRoot().resolve(ASSEMBLY));
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "all");
        args.put("maxResults", 1000);
        assertPayload(new GetDiagnosticsTool(() -> capped), new EnvelopeHarness(capped), args,
            diagnostics(1, 1, 19, List.of(widgetError(assembly), spareWarning(assembly))), byFile());
    }

    @Test
    @DisplayName("maxResults=1 on RefactoringTarget lists exactly its first warning and meta.truncated is true")
    void maxResults_capsAndTruncated() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("maxResults", 1);
        assertCut(args, List.of(refactoringTargetWarnings().get(0)));
    }

    // ========== exact payload helpers ==========

    private static final String HOT = "src/main/java/org/capped/impact/Hot.java";
    private static final String ASSEMBLY = "src/main/java/org/capped/use/Assembly.java";
    private static final String REFACTORING_TARGET = "src/main/java/com/example/RefactoringTarget.java";

    /** IProblem.UnusedImport, UnusedPrivateField, UndefinedType and ParsingErrorInsertToComplete. */
    private static final int UNUSED_IMPORT = 268435844;
    private static final int UNUSED_PRIVATE_FIELD = 570425421;
    private static final int UNDEFINED_TYPE = 16777218;
    private static final int INSERT_TO_COMPLETE = 1610612976;

    private static final List<String> NEXT_TOOLS = List.of(
        "go_to_definition to navigate to error location",
        "get_document_symbols to see file structure");

    /** RefactoringTarget's five warnings, in source order. */
    private List<Map<String, Object>> refactoringTargetWarnings() throws Exception {
        String source = Files.readString(Path.of(refactoringTargetPath));
        List<Map<String, Object>> warnings = new ArrayList<>();
        int line = 3;
        for (String imported : List.of("java.util.ArrayList", "java.util.Map", "java.util.HashMap",
                "java.io.IOException")) {
            int start = source.indexOf("import " + imported + ";") + "import ".length();
            warnings.add(diagnostic(REFACTORING_TARGET, line++, start, start + imported.length() - 1, "warning",
                "The import " + imported + " is never used", UNUSED_IMPORT, "UNNECESSARY_CODE"));
        }
        int maxSize = source.indexOf("MAX_SIZE");
        warnings.add(diagnostic(REFACTORING_TARGET, 19, maxSize, maxSize + "MAX_SIZE".length() - 1, "warning",
            "The value of the field RefactoringTarget.MAX_SIZE is not used", UNUSED_PRIVATE_FIELD,
            "UNNECESSARY_CODE"));
        return warnings;
    }

    /** The unresolved type Widget in {@code assembly}, the text of Assembly.java. */
    private static Map<String, Object> widgetError(String assembly) {
        int start = assembly.indexOf("Widget part;");
        return diagnostic(ASSEMBLY, 7, start, start + "Widget".length() - 1, "error",
            "Widget cannot be resolved to a type", UNDEFINED_TYPE, "TYPE");
    }

    /** Spare's unused private field in {@code assembly}, the text of Assembly.java. */
    private static Map<String, Object> spareWarning(String assembly) {
        int start = assembly.indexOf("int spare;") + "int ".length();
        return diagnostic(ASSEMBLY, 15, start, start + "spare".length() - 1, "warning",
            "The value of the field Spare.spare is not used", UNUSED_PRIVATE_FIELD, "UNNECESSARY_CODE");
    }

    private static Map<String, Object> diagnostic(String file, int line, int start, int end, String severity,
                                                  String message, int problemId, String category) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("filePath", file);
        diagnostic.put("line", line);
        diagnostic.put("startOffset", start);
        diagnostic.put("endOffset", end);
        diagnostic.put("severity", severity);
        diagnostic.put("message", message);
        diagnostic.put("problemId", problemId);
        diagnostic.put("category", category);
        return diagnostic;
    }

    /** The success payload listing every one of {@code diagnostics}, none cut. */
    private static Map<String, Object> diagnostics(int errors, int warnings, int filesChecked,
                                                   List<Map<String, Object>> diagnostics) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalDiagnostics", diagnostics.size());
        data.put("errorCount", errors);
        data.put("warningCount", warnings);
        data.put("filesChecked", filesChecked);
        data.put("diagnostics", diagnostics);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", diagnostics.size());
        meta.put("returnedCount", diagnostics.size());
        meta.put("truncated", false);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The one-file payload for {@code args} lists exactly {@code listed}, checks one file and
     * reports the cut. Exact counts are impossible here: the scan stops one diagnostic past
     * maxResults, so totalDiagnostics, errorCount, warningCount and meta.totalCount count the
     * diagnostics listed, not every diagnostic of the file; they are left out and every other
     * field is pinned.
     */
    private void assertCut(ObjectNode args, List<Map<String, Object>> listed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filesChecked", 1);
        data.put("diagnostics", listed);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("returnedCount", listed.size());
        meta.put("truncated", true);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("success", true);
        expected.put("data", data);
        expected.put("meta", meta);
        assertPayload(tool, envelope, args, expected, payload -> {
            JsonNode copy = payload.deepCopy();
            ((ObjectNode) copy.path("data")).remove(List.of("totalDiagnostics", "errorCount", "warningCount"));
            ((ObjectNode) copy.path("meta")).remove("totalCount");
            return copy;
        });
    }

    /**
     * The payload with its diagnostics in file order: a project-wide scan visits the files in
     * the project's package order, and the sort is stable, so each file's diagnostics keep their
     * source order.
     */
    private static UnaryOperator<JsonNode> byFile() {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            if (copy.path("data") instanceof ObjectNode data && data.get("diagnostics") instanceof ArrayNode list) {
                List<JsonNode> sorted = new ArrayList<>();
                list.forEach(sorted::add);
                sorted.sort(Comparator.comparing(diagnostic -> diagnostic.path("filePath").asText()));
                data.putArray("diagnostics").addAll(sorted);
            }
            return copy;
        };
    }

    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        assertPayload(tool, envelope, args, expected, UnaryOperator.identity());
    }

    /**
     * The payload of {@code diagnosticsTool} through execute(), read back from the JSON text it
     * serializes to as a client reads it, and through {@code harness}'s JSON-RPC envelope both
     * equal {@code expected} after {@code normalize}.
     */
    private void assertPayload(GetDiagnosticsTool diagnosticsTool, EnvelopeHarness harness, ObjectNode args,
                               Map<String, Object> expected, UnaryOperator<JsonNode> normalize) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = normalize.apply(objectMapper.readTree(
                objectMapper.writeValueAsString(diagnosticsTool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = normalize.apply(harness.assertEnvelopeFidelity("get_diagnostics", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
