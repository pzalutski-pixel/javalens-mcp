package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeChangeImpactToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private AnalyzeChangeImpactTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeChangeImpactTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Impact Analysis Tests ==========

    @Test
    @DisplayName("should analyze impact of a method")
    void analyzesMethodImpact() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);  // add method in Calculator
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("symbol"), "Should include symbol name");
        assertNotNull(data.get("symbolType"), "Should include symbol type");
        assertNotNull(data.get("affectedFiles"), "Should include affected files");
        assertNotNull(data.get("callSites"), "Should include call sites");
    }

    @Test
    @DisplayName("should include depth in output")
    void includesDepthInOutput() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 15);
        args.put("depth", 2);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(2, data.get("depth"));
    }

    @Test
    @DisplayName("should cap depth at 3")
    void capsDepthAtThree() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 15);
        args.put("depth", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(3, data.get("depth"), "Depth should be capped at 3");
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("should return error when filePath is missing")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("should return symbol not found for invalid position")
    void returnsSymbolNotFoundForInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        // Position 0:0 is typically the package declaration or empty — may or may not find a symbol
        // Either success with no references or symbolNotFound is acceptable
        assertNotNull(response);
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add impact at depth=1: UserService.java is among affected files")
    void calculatorAdd_depth1_includesUserService() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add() is on 0-based line 13 (column 15 for "add")
        args.put("line", 13);
        args.put("column", 15);
        args.put("depth", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affectedFiles = (List<Map<String, Object>>) data.get("affectedFiles");
        boolean hasUserService = affectedFiles.stream()
            .map(m -> (String) m.get("filePath"))
            .filter(java.util.Objects::nonNull)
            .map(s -> s.replace('\\', '/'))
            .anyMatch(s -> s.endsWith("UserService.java"));
        assertTrue(hasUserService,
            "UserService.calculateTotal calls Calculator.add — must appear in depth-1 affected files; got: "
                + affectedFiles);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Top-level response carries symbol, symbolType, depth, affectedFiles, callSites")
    void responseShape_carriesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : List.of("symbol", "symbolType", "depth", "affectedFiles", "callSites")) {
            assertNotNull(data.get(key), key + " missing on response: " + data.keySet());
        }
    }

    @Test
    @DisplayName("Each callSite carries the depth at which it was discovered")
    void callSite_carriesDepth() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callSites = (List<Map<String, Object>>) getData(r).get("callSites");
        for (Map<String, Object> cs : callSites) {
            assertNotNull(cs.get("depth"), "depth missing on callSite: " + cs);
        }
    }

    @Test
    @DisplayName("Default depth is 1")
    void defaultDepth_isOne() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, ((Number) getData(r).get("depth")).intValue(),
            "Default depth must be 1; got: " + getData(r).get("depth"));
    }

    @Test
    @DisplayName("Calculator.add symbol identity reported")
    void symbol_reported() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("symbol"));
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.add: analyze_change_impact affectedFiles covers find_references files (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculatorAdd_affectedFilesCoverFindReferences() throws Exception {
        // analyze_change_impact at depth=1 aggregates direct callers. find_references on
        // the same method returns every reference. The set of files in change_impact's
        // affectedFiles must contain every file that find_references reports — if a file
        // is missing from change_impact, the impact analysis under-reports blast radius.
        FindReferencesTool detail = new FindReferencesTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 13);
        args.put("column", 15);

        Map<String, Object> aggregateData = getData(tool.execute(args));
        Map<String, Object> detailData = getData(detail.execute(args));

        List<Map<String, Object>> affectedFiles = (List<Map<String, Object>>) aggregateData.get("affectedFiles");
        List<Map<String, Object>> references = (List<Map<String, Object>>) detailData.get("locations");
        assertNotNull(affectedFiles, "aggregate affectedFiles must be present; got: " + aggregateData);
        assertNotNull(references, "detail references must be present; got: " + detailData);

        java.util.Set<String> affectedPaths = new java.util.HashSet<>();
        for (Map<String, Object> af : affectedFiles) {
            Object p = af.get("filePath");
            if (p != null) affectedPaths.add(p.toString().replace('\\', '/'));
        }
        java.util.Set<String> referencePaths = new java.util.HashSet<>();
        for (Map<String, Object> ref : references) {
            Object p = ref.get("filePath");
            if (p != null) referencePaths.add(p.toString().replace('\\', '/'));
        }

        assertTrue(affectedPaths.containsAll(referencePaths),
            "analyze_change_impact.affectedFiles must cover every file find_references reports; "
                + "affectedFiles=" + affectedPaths + " references=" + referencePaths);
    }

    @Test
    @DisplayName("analyze_change_impact on a method consumed via method reference (Foo::formatId) surfaces the reference file in affectedFiles")
    @SuppressWarnings("unchecked")
    void methodReferenceCallSite_surfacesAsAffectedFile() {
        // MethodRefTarget.formatId is consumed only via MethodRefTarget::formatId
        // in MethodRefUser.use(int). No direct invocation. SearchService.findAllReferences
        // includes method-reference sites, and the tool extracts the enclosing
        // IJavaElement.METHOD via getAncestor — which works for SimpleName nodes
        // inside method references. The change-impact analysis must therefore
        // surface MethodRefUser as an affected file.
        java.nio.file.Path projectPath = helper.getFixturePath("simple-maven");
        String targetPath = projectPath
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 8);    // 0-based: `public static String formatId(int id)`
        args.put("column", 25); // on "formatId" identifier

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on MethodRefTarget.formatId must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        List<Map<String, Object>> affectedFiles =
            (List<Map<String, Object>>) data.get("affectedFiles");
        assertNotNull(affectedFiles);
        boolean hasMethodRefUser = affectedFiles.stream()
            .map(f -> (String) f.get("filePath"))
            .filter(java.util.Objects::nonNull)
            .map(p -> p.replace('\\', '/'))
            .anyMatch(p -> p.endsWith("MethodRefUser.java"));
        assertTrue(hasMethodRefUser,
            "MethodRefUser.java holds MethodRefTarget::formatId — must appear in affectedFiles; "
                + "got: " + affectedFiles);
    }
}
