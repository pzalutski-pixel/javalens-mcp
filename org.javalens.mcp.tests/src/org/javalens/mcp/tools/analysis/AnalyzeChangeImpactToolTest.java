package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * analyze_change_impact (default mode) on simple-maven: Calculator.add, the Calculator type, the
 * field lastResult and MethodRefTarget.formatId. Every expectation is the whole payload, derived
 * from the fixture source and asserted through execute() and through the JSON-RPC envelope.
 * Call sites come in search order: by file path, then position. A call's match runs from the
 * method name to its closing parenthesis, a method reference's over the method name and a type
 * reference's over the name as written, its package included when written. Raw offsets depend on
 * line endings, so they are removed; line and column pin each site.
 *
 * <p>No method holding a reference to add or to Calculator is itself referenced, so at depths 2
 * and 3 the call sites are those of depth 1.
 */
class AnalyzeChangeImpactToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private AnalyzeChangeImpactTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeChangeImpactTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Impact Analysis Tests ==========

    @Test
    @DisplayName("Calculator.add impact: its four call sites, two in SearchPatterns, one in UserService, one in SampleTest")
    void analyzesMethodImpact() {
        // Calculator.add is selected at its Javadoc-inclusive range; col 15 = "add".
        assertPayload(at(13, 15, null), addImpact(1));
    }

    @Test
    @DisplayName("depth=2 on the Calculator type is echoed; the call sites are depth 1's")
    void includesDepthInOutput() {
        assertPayload(at(5, 15, 2), typeImpact(2));
    }

    @Test
    @DisplayName("depth=10 is capped at 3")
    void capsDepthAtThree() {
        assertPayload(at(5, 15, 10), typeImpact(3));
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("missing filePath is an exact INVALID_PARAMETER error")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 15);
        assertPayload(args, Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'filePath': Required parameter missing")));
    }

    @Test
    @DisplayName("a blank line resolves to no symbol: exact SYMBOL_NOT_FOUND")
    void returnsSymbolNotFoundForBlankLine() {
        // Line 1 is the blank line between the package declaration and Calculator's Javadoc.
        assertPayload(at(1, 0, null), Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: No symbol found at " + calculatorPath + ":1:0",
            "hint", "Use search_symbols to find available symbols")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add impact at depth=1: exact per-file reference blast radius")
    void calculatorAdd_depth1_exactAffectedFiles() {
        assertPayload(at(13, 15, 1), addImpact(1));
    }

    @Test
    @DisplayName("default mode on a TYPE reports its full reference blast radius: 17 references in 4 files")
    void typeSelection_defaultMode_exactBlastRadius() {
        assertPayload(at(5, 13, null), typeImpact(1));
    }

    @Test
    @DisplayName("default mode on a FIELD reports its access blast radius: lastResult's 7 accesses in Calculator")
    void fieldSelection_defaultMode_exactBlastRadius() {
        // add, subtract and multiply each write lastResult and then return it; getLastResult
        // returns it.
        String type = "com.example.Calculator";
        List<Map<String, Object>> sites = List.of(
            site(CALCULATOR, 10, 15, 8, "lastResult = a + b;", "add", type),
            site(CALCULATOR, 10, 16, 15, "return lastResult;", "add", type),
            site(CALCULATOR, 10, 26, 8, "lastResult = a - b;", "subtract", type),
            site(CALCULATOR, 10, 27, 15, "return lastResult;", "subtract", type),
            site(CALCULATOR, 10, 37, 8, "lastResult = a * b;", "multiply", type),
            site(CALCULATOR, 10, 38, 15, "return lastResult;", "multiply", type),
            site(CALCULATOR, 10, 46, 15, "return lastResult;", "getLastResult", type));
        assertPayload(at(6, 16, null), impact("lastResult", "SourceField", 1,
            List.of(file(CALCULATOR, 7)), sites));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Top-level response carries symbol, symbolType, depth, directReferences, totalReferences, affectedFiles, callSites")
    void responseShape_carriesAllFields() {
        assertPayload(at(13, 15, null), addImpact(1));
    }

    @Test
    @DisplayName("Each callSite carries the depth at which it was discovered")
    void callSite_carriesDepth() {
        assertPayload(at(13, 15, null), addImpact(1));
    }

    @Test
    @DisplayName("Default depth is 1")
    void defaultDepth_isOne() {
        assertPayload(at(13, 15, null), addImpact(1));
    }

    @Test
    @DisplayName("Calculator.add symbol identity reported")
    void symbol_reported() {
        assertPayload(at(13, 15, null), addImpact(1));
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.add: analyze_change_impact affectedFiles are exactly the files find_references reports")
    @SuppressWarnings("unchecked")
    void calculatorAdd_affectedFilesCoverFindReferences() {
        // analyze_change_impact at depth=1 aggregates the direct references by file; find_references
        // on the same method lists every reference. Both name the same three files.
        ObjectNode args = at(13, 15, null);
        List<Map<String, Object>> references = (List<Map<String, Object>>)
            getData(new FindReferencesTool(() -> service).execute(args.deepCopy())).get("locations");
        List<String> referenceFiles = references.stream()
            .map(reference -> (String) reference.get("filePath"))
            .distinct()
            .toList();
        assertAll(
            () -> assertPayload(args, addImpact(1)),
            () -> assertEquals(List.of(SEARCH_PATTERNS, USER_SERVICE, SAMPLE_TEST), referenceFiles));
    }

    @Test
    @DisplayName("analyze_change_impact on a method consumed via method reference (Foo::formatId) reports the reference in MethodRefUser.use")
    void methodReferenceCallSite_surfacesAsAffectedFile() {
        // MethodRefTarget.formatId is consumed only via MethodRefTarget::formatId in
        // MethodRefUser.use(int); the match covers the method name.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString());
        args.put("line", 8);    // 0-based: `public static String formatId(int id)`
        args.put("column", 25); // on "formatId" identifier
        String user = "src/main/java/com/example/MethodRefUser.java";
        assertPayload(args, impact("formatId", "SourceMethod", 1, List.of(file(user, 1)), List.of(
            site(user, 8, 15, 57, "IntFunction<String> formatter = MethodRefTarget::formatId;", "use",
                "com.example.MethodRefUser"))));
    }

    // ========== MCP envelope seam (real registerTools() wiring through processMessage) ==========

    @Test
    @DisplayName("Through the real registerTools() wiring: Calculator type selection reports the exact 17-reference blast radius")
    void envelope_typeSelection_exactBlastRadius() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 5);   // Calculator class declaration (0-based)
        args.put("column", 13);
        assertPayload(args, typeImpact(1));
    }

    // ========== exact payload helpers ==========

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String SEARCH_PATTERNS = "src/main/java/com/example/SearchPatterns.java";
    private static final String TYPE_KINDS = "src/main/java/com/example/TypeKindsFixture.java";
    private static final String USER_SERVICE = "src/main/java/com/example/service/UserService.java";
    private static final String SAMPLE_TEST = "src/test/java/com/example/SampleTest.java";

    private ObjectNode at(int line, int column, Integer depth) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", line);
        args.put("column", column);
        if (depth != null) {
            args.put("depth", depth);
        }
        return args;
    }

    /** Calculator.add's four calls, each `add(..., ...)`, 9 characters. */
    private static Map<String, Object> addImpact(int depth) {
        List<Map<String, Object>> sites = List.of(
            site(SEARCH_PATTERNS, 9, 58, 26, "int result = calc.add(1, 2);", "createObjects",
                "com.example.SearchPatterns"),
            site(SEARCH_PATTERNS, 9, 80, 17, "calc.add(1, 2);", "performCasts", "com.example.SearchPatterns"),
            site(USER_SERVICE, 9, 58, 26, "return calculator.add(a, b);", "calculateSum",
                "com.example.service.UserService"),
            site(SAMPLE_TEST, 9, 35, 32, "int result = calculator.add(2, 3);", "testAddition",
                "com.example.SampleTest"));
        return impact("add", "SourceMethod", depth,
            List.of(file(SEARCH_PATTERNS, 2), file(USER_SERVICE, 1), file(SAMPLE_TEST, 1)), sites);
    }

    /**
     * The Calculator type's 17 references. A reference in a field's declaration is attributed
     * to the field's initializer, one in a Javadoc to the member it documents, and the import has
     * no enclosing member.
     */
    private static Map<String, Object> typeImpact(int depth) {
        String searchPatterns = "com.example.SearchPatterns";
        String inner = "com.example.SearchPatterns$InnerClass";
        String userService = "com.example.service.UserService";
        String sampleTest = "com.example.SampleTest";
        List<Map<String, Object>> sites = List.of(
            site(SEARCH_PATTERNS, 10, 26, 17, "private List<Calculator> calculatorList;", "<initializer>", searchPatterns),
            site(SEARCH_PATTERNS, 10, 57, 8, "Calculator calc = new Calculator();", "createObjects", searchPatterns),
            site(SEARCH_PATTERNS, 10, 57, 30, "Calculator calc = new Calculator();", "createObjects", searchPatterns),
            site(SEARCH_PATTERNS, 10, 78, 27, "if (obj instanceof Calculator) {", "performCasts", searchPatterns),
            site(SEARCH_PATTERNS, 10, 79, 12, "Calculator calc = (Calculator) obj;", "performCasts", searchPatterns),
            site(SEARCH_PATTERNS, 10, 79, 31, "Calculator calc = (Calculator) obj;", "performCasts", searchPatterns),
            site(SEARCH_PATTERNS, 10, 99, 27, "if (obj instanceof Calculator) {", "checkTypes", searchPatterns),
            site(SEARCH_PATTERNS, 10, 210, 13, "List<Calculator> calcs = new ArrayList<>();", "useGenerics",
                searchPatterns),
            site(SEARCH_PATTERNS, 10, 211, 22, "calcs.add(new Calculator());", "useGenerics", searchPatterns),
            site(SEARCH_PATTERNS, 10, 225, 15, "public Calculator createCalculator() {", "createCalculator", inner),
            site(SEARCH_PATTERNS, 10, 226, 23, "return new Calculator();", "createCalculator", inner),
            site(TYPE_KINDS, 22, 109, 12, "* @see com.example.Calculator", "richlyDocumentedMethod",
                "com.example.TypeKindsFixture"),
            importSite(USER_SERVICE, 22, 2, 7, "import com.example.Calculator;"),
            site(USER_SERVICE, 10, 12, 18, "private final Calculator calculator;", "<initializer>", userService),
            site(USER_SERVICE, 10, 19, 30, "this.calculator = new Calculator();", "UserService", userService),
            site(SAMPLE_TEST, 10, 21, 12, "private Calculator calculator;", "<initializer>", sampleTest),
            site(SAMPLE_TEST, 10, 25, 25, "calculator = new Calculator();", "setUp", sampleTest));
        return impact("Calculator", "SourceType", depth, List.of(file(SEARCH_PATTERNS, 11), file(TYPE_KINDS, 1),
            file(USER_SERVICE, 3), file(SAMPLE_TEST, 2)), sites);
    }

    private static Map<String, Object> site(String file, int length, int line, int column, String context,
                                            String enclosingMethod, String enclosingType) {
        Map<String, Object> site = importSite(file, length, line, column, context);
        site.put("enclosingMethod", enclosingMethod);
        site.put("enclosingType", enclosingType);
        return site;
    }

    /** A depth-1 call site with no enclosing member. */
    private static Map<String, Object> importSite(String file, int length, int line, int column, String context) {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("filePath", file);
        site.put("length", length);
        site.put("line", line);
        site.put("column", column);
        site.put("context", context);
        site.put("depth", 1);
        return site;
    }

    private static Map<String, Object> file(String path, int referenceCount) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("filePath", path);
        file.put("referenceCount", referenceCount);
        return file;
    }

    /** The success payload of a default-mode analysis listing {@code sites}, all at depth 1. */
    private static Map<String, Object> impact(String symbol, String symbolType, int depth,
                                              List<Map<String, Object>> affectedFiles,
                                              List<Map<String, Object>> sites) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("symbolType", symbolType);
        data.put("depth", depth);
        data.put("directReferences", sites.size());
        data.put("totalReferences", sites.size());
        data.put("affectedFiles", affectedFiles);
        data.put("callSites", sites);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", sites.size());
        meta.put("returnedCount", sites.size());
        meta.put("suggestedNextTools", List.of(
            "rename_symbol for safe renaming across all affected files",
            "find_references for simple reference list without grouping"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each without its call sites' raw offsets.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = withoutOffsets(objectMapper.readTree(
                objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = withoutOffsets(envelope.assertEnvelopeFidelity("analyze_change_impact", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("callSites").forEach(site -> ((ObjectNode) site).remove("offset"));
        return copy;
    }
}
