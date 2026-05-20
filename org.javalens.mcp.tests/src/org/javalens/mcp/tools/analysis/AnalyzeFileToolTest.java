package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeFileToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;
    private AnalyzeFileTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeFileTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("analyzes file comprehensively")
    void analyzesFileComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File info
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        String path = (String) file.get("path");
        assertNotNull(path, "file.path missing");
        assertTrue(path.endsWith("Calculator.java"),
            "file.path must end with Calculator.java; got: " + file);
        assertEquals("com.example", file.get("package"));
        assertTrue((Integer) file.get("lineCount") > 0);

        // Types
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        assertFalse(types.isEmpty());
        assertEquals("Calculator", types.get(0).get("name"));
        assertEquals("class", types.get(0).get("kind"));

        // Diagnostics included by default — map with errors/warnings keys
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) data.get("diagnostics");
        assertNotNull(diagnostics, "diagnostics block missing");
        assertTrue(diagnostics.containsKey("errors") || diagnostics.containsKey("warnings"),
            "diagnostics must have errors or warnings; got: " + diagnostics);
    }

    @Test @DisplayName("controls optional output")
    void controlsOptionalOutput() {
        // Include members
        ObjectNode withMembers = objectMapper.createObjectNode();
        withMembers.put("filePath", calculatorPath);
        withMembers.put("includeMembers", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) getData(tool.execute(withMembers)).get("types");
        assertNotNull(types.get(0).get("methods"));

        // Exclude diagnostics
        ObjectNode noDiag = objectMapper.createObjectNode();
        noDiag.put("filePath", calculatorPath);
        noDiag.put("includeDiagnostics", false);
        assertNull(getData(tool.execute(noDiag)).get("diagnostics"));
    }

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(badPath).isSuccess());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertFalse(tool.execute(emptyPath).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> typesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("types");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> importsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("imports");
    }

    @Test
    @DisplayName("File block carries path, package, lineCount")
    void fileBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) getData(r).get("file");
        for (String key : List.of("path", "package", "lineCount")) {
            assertNotNull(file.get(key), key + " missing on file block: " + file);
        }
    }

    @Test
    @DisplayName("Imports: each entry has name, static, onDemand; importCount equals list size")
    void imports_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (Map<String, Object> imp : importsOf(r)) {
            for (String key : List.of("name", "static", "onDemand")) {
                assertNotNull(imp.get(key), key + " missing on import: " + imp);
            }
        }
        int count = ((Number) data.get("importCount")).intValue();
        assertEquals(count, importsOf(r).size());
    }

    @Test
    @DisplayName("Types: each entry has name, qualifiedName, kind, modifiers, line")
    void types_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> t : typesOf(r)) {
            for (String key : List.of("name", "qualifiedName", "kind", "modifiers", "line")) {
                assertNotNull(t.get(key), key + " missing on type: " + t);
            }
        }
    }

    @Test
    @DisplayName("typeCount equals types.size()")
    void typeCount_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int count = ((Number) data.get("typeCount")).intValue();
        assertEquals(count, typesOf(r).size());
    }

    @Test
    @DisplayName("Calculator qualifiedName is com.example.Calculator")
    void calculator_qualifiedName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("com.example.Calculator", typesOf(r).get(0).get("qualifiedName"));
    }

    @Test
    @DisplayName("includeMembers=true populates methods/fields lists on type entries")
    void includeMembers_populatesLists() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeMembers", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> type = typesOf(r).get(0);
        assertNotNull(type.get("methods"), "methods list missing when includeMembers=true");
        assertNotNull(type.get("fields"), "fields list missing when includeMembers=true");
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.java: analyze_file top-level type count agrees with get_document_symbols (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculator_topLevelTypeCountAgreesWithDocumentSymbols() throws Exception {
        // Both tools enumerate top-level types via cu.getTypes() on the same compilation
        // unit. If their lists differ in size, one tool dropped (or duplicated) types.
        GetDocumentSymbolsTool detail = new GetDocumentSymbolsTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        List<Map<String, Object>> aggregateTypes = typesOf(tool.execute(args));
        List<Map<String, Object>> detailSymbols =
            (List<Map<String, Object>>) getData(detail.execute(args)).get("symbols");

        assertEquals(aggregateTypes.size(), detailSymbols.size(),
            "analyze_file.types.size() must equal get_document_symbols.symbols.size() (both enumerate "
                + "top-level types in the file); aggregate=" + aggregateTypes.size()
                + " detail=" + detailSymbols.size());
    }

    @Test
    @DisplayName("Default-package file: file.package reports '(default package)' literal")
    @SuppressWarnings("unchecked")
    void defaultPackageFile_reportsParenthesizedLabel() throws Exception {
        // Source line 104 has the branch for cu.getPackageDeclarations().length == 0:
        // emits the literal string "(default package)". Use the default-package fixture
        // (NoPackage.java with no package statement) to exercise that branch.
        org.javalens.core.JdtServiceImpl defaultPkgService =
            helper.loadProject("default-package");
        AnalyzeFileTool defaultPkgTool = new AnalyzeFileTool(() -> defaultPkgService);
        String noPackagePath = helper.getFixturePath("default-package")
            .resolve("src/main/java/NoPackage.java").toString();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", noPackagePath);
        ToolResponse r = defaultPkgTool.execute(args);
        assertTrue(r.isSuccess(),
            "Default-package file must analyze without error; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> file = (Map<String, Object>) getData(r).get("file");
        assertEquals("(default package)", file.get("package"),
            "Default-package file must report file.package='(default package)'; got: " + file);
    }
}
