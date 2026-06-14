package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetDocumentSymbolsTool.
 * Tests file symbol extraction.
 */
class GetDocumentSymbolsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDocumentSymbolsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDocumentSymbolsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSymbols(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("symbols");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getChildren(Map<String, Object> symbol) {
        return (List<Map<String, Object>>) symbol.get("children");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Document symbols returns class with methods, fields, line numbers, modifiers, signatures, and types")
    @SuppressWarnings("unchecked")
    void documentSymbols_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> symbols = getSymbols(data);
        assertFalse(symbols.isEmpty());
        // Calculator: 1 top-level class + 5 members (lastResult, add, subtract, multiply,
        // getLastResult) = 6 total symbols.
        assertEquals(6, ((Number) data.get("totalSymbols")).intValue(),
            "totalSymbols = top-level + members; got: " + data);

        Map<String, Object> calcSymbol = symbols.stream()
            .filter(s -> "Calculator".equals(s.get("name")))
            .findFirst().orElseThrow();
        assertEquals("class", calcSymbol.get("kind"));
        assertEquals(2, ((Number) calcSymbol.get("line")).intValue(),
            "Calculator 0-based line (Javadoc-inclusive source-range start); got: " + calcSymbol);
        assertEquals(List.of("public"), calcSymbol.get("modifiers"),
            "Calculator is exactly `public`; got: " + calcSymbol.get("modifiers"));

        List<Map<String, Object>> children = getChildren(calcSymbol);
        java.util.Set<String> childNames = children.stream()
            .map(c -> (String) c.get("name")).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("lastResult", "add", "subtract", "multiply", "getLastResult"),
            childNames, "Calculator's exact member set; got: " + childNames);

        Map<String, Object> addMethod = children.stream()
            .filter(c -> "add".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("add(int a, int b): int", addMethod.get("signature"),
            "exact add signature; got: " + addMethod.get("signature"));

        // Fields with types
        Map<String, Object> lastResultField = children.stream()
            .filter(c -> "lastResult".equals(c.get("name")))
            .findFirst()
            .orElse(null);
        assertNotNull(lastResultField);
        assertEquals("int", lastResultField.get("type"));
    }

    @Test
    @DisplayName("Constructors are included in symbols")
    void constructors_includedInSymbols() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> symbols = getSymbols(data);
        Map<String, Object> helloSymbol = symbols.get(0);
        List<Map<String, Object>> children = getChildren(helloSymbol);

        assertTrue(children.stream().anyMatch(c ->
            "HelloWorld".equals(c.get("name")) && "constructor".equals(c.get("kind"))));
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("includePrivate filter controls private member visibility")
    void includePrivate_controlsVisibility() {
        // Default includes private
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", calculatorPath);
        ToolResponse response1 = tool.execute(args1);
        Map<String, Object> data1 = getData(response1);
        List<Map<String, Object>> symbols1 = getSymbols(data1);
        List<Map<String, Object>> children1 = getChildren(symbols1.get(0));
        assertTrue(children1.stream().anyMatch(c -> "lastResult".equals(c.get("name"))));

        // includePrivate=false excludes private
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("includePrivate", false);
        ToolResponse response2 = tool.execute(args2);
        Map<String, Object> data2 = getData(response2);
        List<Map<String, Object>> symbols2 = getSymbols(data2);
        List<Map<String, Object>> children2 = getChildren(symbols2.get(0));
        assertFalse(children2.stream().anyMatch(c -> "lastResult".equals(c.get("name"))));
    }

    @Test
    @DisplayName("maxResults caps emitted symbols; totalSymbols still reports pre-clip total")
    void maxResults_limitsSymbols() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("maxResults", 3);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        // Calculator has 6 symbols; maxResults=3 emits exactly 3, flags truncation, but
        // totalSymbols still reports the pre-clip 6.
        assertEquals(3, response.getMeta().getReturnedCount().intValue(),
            "emitted symbol count capped at exactly 3");
        assertEquals(Boolean.TRUE, response.getMeta().getTruncated());
        assertEquals(6, ((Number) data.get("totalSymbols")).intValue(),
            "totalSymbols is the pre-clip count; got: " + data.get("totalSymbols"));
    }

    // ========== Semantic-grade tests ==========

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Static final field is reported with kind='Constant' (RefactoringTarget.MAX_SIZE)")
    @SuppressWarnings("unchecked")
    void staticFinalField_kindIsConstant() {
        String rt = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", rt);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> symbols = getSymbols(getData(r));
        Map<String, Object> rtSymbol = symbols.stream()
            .filter(s -> "RefactoringTarget".equals(s.get("name")))
            .findFirst().orElseThrow();
        Map<String, Object> maxSize = getChildren(rtSymbol).stream()
            .filter(c -> "MAX_SIZE".equals(c.get("name")))
            .findFirst().orElseThrow();
        assertEquals("constant", maxSize.get("kind"),
            "static final field must be reported with kind='constant'; got: " + maxSize);
    }

    @Test
    @DisplayName("Enum constant is reported with kind='EnumConstant'")
    @SuppressWarnings("unchecked")
    void enumConstant_kindIsEnumConstant() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> symbols = getSymbols(getData(r));
        Map<String, Object> tkfSymbol = symbols.stream()
            .filter(s -> "TypeKindsFixture".equals(s.get("name")))
            .findFirst().orElseThrow();
        // Find Color nested type
        Map<String, Object> colorEnum = getChildren(tkfSymbol).stream()
            .filter(c -> "Color".equals(c.get("name")))
            .findFirst().orElseThrow();
        assertEquals("enum", colorEnum.get("kind"),
            "Color enum nested type must be reported with kind='enum'; got: " + colorEnum);

        // RED/GREEN/BLUE are enum constants inside Color.
        Map<String, Object> red = getChildren(colorEnum).stream()
            .filter(c -> "RED".equals(c.get("name")))
            .findFirst().orElseThrow();
        assertEquals("enumConstant", red.get("kind"),
            "Enum constant RED must report kind='enumConstant'; got: " + red);
    }

    @Test
    @DisplayName("Type kinds Annotation/Record reported correctly when nested in IShape.java? — use TypeKindsFixture")
    @SuppressWarnings("unchecked")
    void typeKinds_reportedCorrectlyForNonClassTypes() {
        // Verify Annotation, Record, Interface kinds on dedicated fixtures.
        // Marker.java's top-level @interface.
        String marker = projectPath.resolve("src/main/java/com/example/Marker.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", marker);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> markerSym = getSymbols(getData(r)).stream()
            .filter(s -> "Marker".equals(s.get("name")))
            .findFirst().orElseThrow();
        assertEquals("annotation", markerSym.get("kind"));

        // Point.java record.
        String point = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        args = objectMapper.createObjectNode();
        args.put("filePath", point);
        r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> pointSym = getSymbols(getData(r)).stream()
            .filter(s -> "Point".equals(s.get("name")))
            .findFirst().orElseThrow();
        assertEquals("record", pointSym.get("kind"));

        // IShape.java interface.
        String iShape = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        args = objectMapper.createObjectNode();
        args.put("filePath", iShape);
        r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> iShapeSym = getSymbols(getData(r)).stream()
            .filter(s -> "IShape".equals(s.get("name")))
            .findFirst().orElseThrow();
        assertEquals("interface", iShapeSym.get("kind"));
    }

    @Test
    @DisplayName("Record components (x, y of Point) appear as field-kind children")
    @SuppressWarnings("unchecked")
    void recordComponents_appearAsChildren() {
        // Point(int x, int y) declares two record components. They are members
        // of the record and should appear as children of the Point symbol,
        // alongside any explicit methods. JDT models them as IRecordComponent
        // (not as IField), so a tool iterating type.getFields() only would
        // omit them.
        String point = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", point);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        Map<String, Object> pointSym = getSymbols(getData(r)).stream()
            .filter(s -> "Point".equals(s.get("name")))
            .findFirst().orElseThrow();

        List<Map<String, Object>> children = getChildren(pointSym);
        assertNotNull(children, "Point must have children (components + methods)");
        java.util.Set<String> childNames = children.stream()
            .map(c -> (String) c.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(childNames.contains("x"),
            "x is a Point record component and must appear as a child symbol; got: " + childNames);
        assertTrue(childNames.contains("y"),
            "y is a Point record component and must appear as a child symbol; got: " + childNames);
    }

    @Test
    @DisplayName("Method signature for Calculator.add: `add(int a, int b): int`")
    @SuppressWarnings("unchecked")
    void methodSignature_isExactlyFormatted() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> calcSymbol = getSymbols(getData(r)).stream()
            .filter(s -> "Calculator".equals(s.get("name")))
            .findFirst().orElseThrow();
        Map<String, Object> addMethod = getChildren(calcSymbol).stream()
            .filter(c -> "add".equals(c.get("name")))
            .findFirst().orElseThrow();
        assertEquals("add(int a, int b): int", addMethod.get("signature"),
            "Method signature must include params with names and return type; got: " + addMethod);
    }

    @Test
    @DisplayName("Nested type appears as child of its enclosing type's children")
    @SuppressWarnings("unchecked")
    void nestedType_appearsAsChild() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> tkfSymbol = getSymbols(getData(r)).stream()
            .filter(s -> "TypeKindsFixture".equals(s.get("name")))
            .findFirst().orElseThrow();
        List<Map<String, Object>> children = getChildren(tkfSymbol);
        java.util.Set<String> childNames = children.stream()
            .map(c -> (String) c.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        // Nested classes Color, GenericContainer, Inner, DefaultMethodHolder, BoundedBox.
        assertTrue(childNames.contains("Color"));
        assertTrue(childNames.contains("GenericContainer"));
        assertTrue(childNames.contains("Inner"));
        assertTrue(childNames.contains("BoundedBox"));
        assertTrue(childNames.contains("DefaultMethodHolder"));
    }

    @Test
    @DisplayName("Calculator has exactly 4 methods + 1 field as direct children")
    void calculator_exactChildrenCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> symbols = getSymbols(data);
        Map<String, Object> calc = symbols.stream()
            .filter(s -> "Calculator".equals(s.get("name")))
            .findFirst()
            .orElseThrow();

        List<Map<String, Object>> children = getChildren(calc);
        long methods = children.stream().filter(c -> "method".equals(c.get("kind"))).count();
        long fields = children.stream().filter(c -> "field".equals(c.get("kind"))).count();
        assertEquals(4L, methods,
            "Calculator declares exactly 4 methods (add, subtract, multiply, getLastResult); got: " + children);
        assertEquals(1L, fields,
            "Calculator declares exactly 1 field (lastResult); got: " + children);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid filePath returns error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Nonexistent file
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", "/nonexistent/path/File.java");
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator has exactly 4 methods + 1 field, add signature exact")
    void envelope_calculator_exactChildren() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_document_symbols", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_document_symbols failed through the envelope: " + payload);
        JsonNode calc = null;
        for (JsonNode s : payload.get("data").get("symbols")) {
            if ("Calculator".equals(s.get("name").asText())) calc = s;
        }
        assertNotNull(calc, "Calculator symbol must survive the envelope");
        int methods = 0;
        int fields = 0;
        JsonNode addMethod = null;
        for (JsonNode c : calc.get("children")) {
            String kind = c.get("kind").asText();
            if ("method".equals(kind)) methods++;
            else if ("field".equals(kind)) fields++;
            if ("add".equals(c.get("name").asText())) addMethod = c;
        }
        assertEquals(4, methods, "Calculator has exactly 4 methods through the envelope");
        assertEquals(1, fields, "Calculator has exactly 1 field through the envelope");
        assertNotNull(addMethod, "add method child must be present");
        assertEquals("add(int a, int b): int", addMethod.get("signature").asText(),
            "the exact add signature must survive the envelope");
    }
}
