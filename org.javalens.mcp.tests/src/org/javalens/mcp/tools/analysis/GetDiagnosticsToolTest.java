package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDiagnosticsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDiagnosticsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiagnosticsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("a syntax-broken file is reported with errors, single-file and project-wide")
    void syntaxError_reportedAsError() throws Exception {
        JdtServiceImpl mutableService = helper.loadProjectCopy("simple-maven");
        java.nio.file.Path projectCopy = mutableService.getProjectRoot();
        java.nio.file.Path calculator =
            projectCopy.resolve("src/main/java/com/example/Calculator.java");
        java.nio.file.Files.writeString(calculator,
            java.nio.file.Files.readString(calculator).replace("public class", "public clazz"));

        GetDiagnosticsTool mutableTool = new GetDiagnosticsTool(() -> mutableService);

        // Single file
        ObjectNode fileArgs = objectMapper.createObjectNode();
        fileArgs.put("filePath", calculator.toString());
        ToolResponse single = mutableTool.execute(fileArgs);
        assertTrue(single.isSuccess(), () -> "single-file: " + single.getError());
        Map<String, Object> singleData = getData(single);
        assertTrue(((Number) singleData.get("errorCount")).intValue() > 0,
            () -> "a file with a syntax error must report errors; got: " + singleData);

        // Whole project
        ToolResponse project = mutableTool.execute(objectMapper.createObjectNode());
        assertTrue(project.isSuccess());
        Map<String, Object> projectData = getData(project);
        assertTrue(((Number) projectData.get("errorCount")).intValue() > 0,
            () -> "the project-wide scan must include the broken file's errors; got "
                + "errorCount=" + projectData.get("errorCount")
                + " filesChecked=" + projectData.get("filesChecked"));
    }

    @Test
    @DisplayName("a project declaring a pre-1.8 compiler level still reports syntax errors")
    void legacyCompliance_syntaxErrorStillReported() throws Exception {
        // Modern JDT (Eclipse 2025-12) does not support compliance below 1.8.
        // Legacy enterprise poms still declare 1.6/1.7; applying that level
        // verbatim must not silently disable problem detection.
        JdtServiceImpl legacyService = helper.loadProjectCopy("legacy-compliance-maven");
        java.nio.file.Path projectCopy = legacyService.getProjectRoot();
        java.nio.file.Path old = projectCopy.resolve("src/main/java/com/legacy/Old.java");
        java.nio.file.Files.writeString(old,
            java.nio.file.Files.readString(old).replace("public class", "public clazz"));

        GetDiagnosticsTool legacyTool = new GetDiagnosticsTool(() -> legacyService);
        ToolResponse r = legacyTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "project-wide: " + r.getError());
        Map<String, Object> data = getData(r);
        assertTrue(((Number) data.get("errorCount")).intValue() > 0,
            () -> "a syntax error must be reported even when the declared compliance (1.6) "
                + "predates JDT's supported range; got: " + data);

        assertTrue(legacyService.getWarnings().stream()
                .anyMatch(w -> "COMPLIANCE_LEVEL_UNSUPPORTED".equals(w.code())),
            () -> "the clamp must be surfaced as a load warning; got: " + legacyService.getWarnings());
    }

    @Test @DisplayName("gets diagnostics for clean file")
    void getsDiagnosticsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, data.get("totalDiagnostics"));
        assertEquals(0, data.get("errorCount"));
        assertEquals(0, data.get("warningCount"));
        assertEquals(1, data.get("filesChecked"));
    }

    @Test @DisplayName("supports severity filter and maxResults")
    void supportsSeverityFilterAndMaxResults() {
        ObjectNode withSeverity = objectMapper.createObjectNode();
        withSeverity.put("filePath", calculatorPath);
        withSeverity.put("severity", "error");
        assertTrue(tool.execute(withSeverity).isSuccess());

        ObjectNode withMax = objectMapper.createObjectNode();
        withMax.put("maxResults", 1);
        @SuppressWarnings("unchecked")
        List<?> diags = (List<?>) getData(tool.execute(withMax)).get("diagnostics");
        assertTrue(diags.size() <= 1);
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        assertTrue((Integer) getData(r).get("filesChecked") > 0);
    }

    @Test @DisplayName("handles invalid file path")
    void handlesInvalidFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diagsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("diagnostics");
    }

    @Test
    @DisplayName("RefactoringTarget.java surfaces unused-import warnings (4 unused imports)")
    void refactoringTarget_unusedImportWarnings() {
        String refactoringPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringPath);
        args.put("severity", "warning");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // ArrayList, Map, HashMap, IOException are imported but never used in this file.
        // JDT categorizes unused-import as UNNECESSARY_CODE; assert each by message content.
        java.util.Set<String> unusedImports = new java.util.HashSet<>();
        for (Map<String, Object> d : diagsOf(r)) {
            String msg = (String) d.get("message");
            if (msg != null && msg.contains("import") && msg.contains("never used")) {
                unusedImports.add(msg);
            }
        }
        assertTrue(unusedImports.size() >= 4,
            "Expected at least 4 unused-import warnings; got: " + unusedImports.size()
                + " unused-import messages out of " + diagsOf(r));
    }

    @Test
    @DisplayName("Each diagnostic entry carries filePath, line, startOffset, endOffset, severity, message, problemId, category")
    void diagnosticEntry_includesFullShape() {
        String refactoringPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringPath);
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> diags = diagsOf(r);
        assertFalse(diags.isEmpty(), "RefactoringTarget has known warnings; list must be non-empty");
        for (Map<String, Object> d : diags) {
            for (String key : List.of("filePath", "line", "startOffset", "endOffset",
                    "severity", "message", "problemId", "category")) {
                assertNotNull(d.get(key), key + " missing on diagnostic: " + d);
            }
        }
    }

    @Test
    @DisplayName("severity='error' returns only error-severity diagnostics")
    void severityErrorOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "error");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> d : diagsOf(r)) {
            assertEquals("error", d.get("severity"),
                "All entries must be errors when severity=error; got: " + d);
        }
    }

    @Test
    @DisplayName("severity='warning' returns only warning-severity diagnostics")
    void severityWarningOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "warning");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> d : diagsOf(r)) {
            assertEquals("warning", d.get("severity"),
                "All entries must be warnings when severity=warning; got: " + d);
        }
    }

    @Test
    @DisplayName("totalDiagnostics == diagnostics.size(); errorCount + warningCount == totalDiagnostics for severity=all")
    void counts_consistent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("severity", "all");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalDiagnostics")).intValue();
        int errors = ((Number) data.get("errorCount")).intValue();
        int warnings = ((Number) data.get("warningCount")).intValue();
        assertEquals(total, diagsOf(r).size(),
            "totalDiagnostics must equal diagnostics.size()");
        assertEquals(total, errors + warnings,
            "errorCount + warningCount must equal totalDiagnostics; got total=" + total
                + " errors=" + errors + " warnings=" + warnings);
    }

    @Test
    @DisplayName("maxResults=1 caps to 1 and meta.truncated reflects truncation when more diagnostics exist")
    void maxResults_capsAndTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertTrue(diagsOf(r).size() <= 1,
            "maxResults=1 must cap list to at most 1; got: " + diagsOf(r));
        // Meta.truncated semantics: if the cap was hit, truncated=true.
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        if (diagsOf(r).size() == 1) {
            assertEquals(Boolean.TRUE, meta.getTruncated(),
                "When the cap (1) is hit, meta.truncated must be true");
        }
    }
}
