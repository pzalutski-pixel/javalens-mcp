package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.SemanticAssertions;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetTypeHierarchyTool.
 * Tests super/subtype traversal.
 */
class GetTypeHierarchyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetTypeHierarchyTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeHierarchyTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSuperclasses(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("superclasses");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSubtypes(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("subtypes");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getInterfaces(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("interfaces");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getTypeInfo(Map<String, Object> data) {
        return (Map<String, Object>) data.get("type");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Position lookup returns type info, superclasses with Object, subtypes, interfaces, and counts")
    void positionLookup_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Type info with location
        Map<String, Object> typeInfo = getTypeInfo(data);
        assertNotNull(typeInfo, "typeInfo block must be present");
        assertEquals("Calculator", typeInfo.get("name"));
        assertEquals("com.example.Calculator", typeInfo.get("qualifiedName"));
        assertEquals("class", typeInfo.get("kind"));
        assertTrue(((Number) typeInfo.get("line")).intValue() >= 0,
            "typeInfo.line >= 0; got: " + typeInfo);
        String filePath = (String) typeInfo.get("filePath");
        assertTrue(filePath.endsWith("Calculator.java"),
            "typeInfo.filePath must point to Calculator.java; got: " + typeInfo);

        // Superclasses including Object
        List<Map<String, Object>> superclasses = getSuperclasses(data);
        assertNotNull(superclasses, "superclasses list must be present");
        assertTrue(superclasses.stream().anyMatch(s -> "Object".equals(s.get("name"))));
        int totalSuperclasses = ((Number) data.get("totalSuperclasses")).intValue();
        assertEquals(superclasses.size(), totalSuperclasses,
            "totalSuperclasses must equal superclasses list size; got: " + data);

        // Subtypes (Calculator has none — exact zero)
        List<Map<String, Object>> subtypes = getSubtypes(data);
        assertEquals(0, subtypes.size(), "Calculator has no subtypes; got: " + subtypes);
        assertEquals(0, ((Number) data.get("totalSubtypes")).intValue(),
            "totalSubtypes must equal 0; got: " + data);

        // Interfaces (Calculator implements none — exact zero)
        List<Map<String, Object>> interfaces = getInterfaces(data);
        assertEquals(0, interfaces.size(),
            "Calculator implements no interfaces; got: " + interfaces);
        assertEquals(0, ((Number) data.get("totalInterfaces")).intValue(),
            "totalInterfaces must equal 0; got: " + data);
    }

    @Test
    @DisplayName("Type name lookup returns type info")
    void typeNameLookup_returnsTypeInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        Map<String, Object> typeInfo = getTypeInfo(data);
        assertNotNull(typeInfo);
        assertEquals("Calculator", typeInfo.get("name"));
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("maxDepth limits superclass traversal")
    void maxDepth_limitsSuperclasses() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);
        args.put("maxDepth", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> superclasses = getSuperclasses(data);
        assertTrue(superclasses.size() <= 1);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Empty args returns SYMBOL_NOT_FOUND (no position, no typeName, no fallback)")
    void missingParameters_returnsSymbolNotFound() {
        // With no inputs at all: position-lookup skipped (filePath null), typeName-
        // lookup skipped (null), targetType stays null → SYMBOL_NOT_FOUND.
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Empty args must return SYMBOL_NOT_FOUND; got: " + response.getError().getCode());
    }

    @Test
    @DisplayName("Invalid type name returns SYMBOL_NOT_FOUND")
    void invalidTypeName_returnsSymbolNotFound() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.NonExistent");

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Unresolved typeName must return SYMBOL_NOT_FOUND; got: "
                + response.getError().getCode());
    }

    @Test
    @DisplayName("Negative line bypasses position lookup; with no typeName fallback returns SYMBOL_NOT_FOUND")
    void negativeLine_returnsSymbolNotFound() {
        // Source: `if (filePath != null && !filePath.isBlank() && line >= 0 && column >= 0)`
        // skips position lookup when line < 0. With no typeName fallback, targetType
        // stays null → SYMBOL_NOT_FOUND. Pin the documented failure mode instead of a
        // shape-only assertNotNull.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", -1);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Negative-line + no typeName fallback must return SYMBOL_NOT_FOUND; got: "
                + response.getError().getCode());
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    private String fixturePath(String relative) {
        return projectPath.resolve(relative).toString();
    }

    private ObjectNode argsByName(String typeName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", typeName);
        args.put("maxDepth", 50);
        return args;
    }

    @Test
    @DisplayName("IShape returns exactly its 7 subtypes (interface, classes, anonymous, inner)")
    void iShape_returnsExactTransitiveSubtypes() {
        ToolResponse r = tool.execute(argsByName("com.example.IShape"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> subNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "subtypes"), "qualifiedName");
        assertEquals(
            Set.of(
                "com.example.IFillable",
                "com.example.Rectangle",
                "com.example.FilledCircle",
                "com.example.AnonymousShapeUser$1",
                "com.example.AnonymousShapeUser$2",
                "com.example.AnonymousShapeUser$InnerShape",
                "com.example.AnonymousShapeUser$NonStaticInnerShape"
            ),
            subNames);
        assertEquals(7, ((Number) data.get("totalSubtypes")).intValue());
    }

    @Test
    @DisplayName("IFillable returns its only direct sub-interface implementor FilledCircle plus one anonymous")
    void iFillable_returnsExactSubtypes() {
        ToolResponse r = tool.execute(argsByName("com.example.IFillable"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> subNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "subtypes"), "qualifiedName");
        assertEquals(
            Set.of("com.example.FilledCircle", "com.example.AnonymousShapeUser$2"),
            subNames);
    }

    @Test
    @DisplayName("Vehicle (sealed) returns exactly Car and Truck as subtypes")
    void vehicle_returnsExactPermittedSubtypes() {
        ToolResponse r = tool.execute(argsByName("com.example.Vehicle"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> subNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "subtypes"), "qualifiedName");
        assertEquals(Set.of("com.example.Car", "com.example.Truck"), subNames);
    }

    @Test
    @DisplayName("Rectangle reports its interface IShape and superclass Object")
    void rectangle_reportsInterfacesAndSuperclasses() {
        ToolResponse r = tool.execute(argsByName("com.example.Rectangle"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> ifaceNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "interfaces"), "qualifiedName");
        assertTrue(ifaceNames.contains("com.example.IShape"),
            "Rectangle directly implements IShape; got: " + ifaceNames);

        Set<String> superNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "superclasses"), "qualifiedName");
        assertTrue(superNames.contains("java.lang.Object"),
            "Rectangle extends Object implicitly; got: " + superNames);

        assertEquals(0, ((Number) data.get("totalSubtypes")).intValue(),
            "Rectangle has no subclasses");
    }

    @Test
    @DisplayName("FilledCircle reports both IFillable (direct) and IShape (transitive) as interfaces")
    void filledCircle_reportsTransitiveInterfaces() {
        ToolResponse r = tool.execute(argsByName("com.example.FilledCircle"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> ifaceNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "interfaces"), "qualifiedName");
        assertTrue(ifaceNames.contains("com.example.IFillable"),
            "FilledCircle directly implements IFillable; got: " + ifaceNames);
        assertTrue(ifaceNames.contains("com.example.IShape"),
            "FilledCircle transitively implements IShape via IFillable; got: " + ifaceNames);
    }

    @Test
    @DisplayName("Point record reports Comparable as an interface")
    void point_recordImplementsComparable() {
        ToolResponse r = tool.execute(argsByName("com.example.Point"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> typeInfo = getTypeInfo(data);
        assertEquals("record", typeInfo.get("kind"));

        Set<String> ifaceNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "interfaces"), "qualifiedName");
        assertTrue(ifaceNames.contains("java.lang.Comparable"),
            "Point record implements Comparable<Point>; got: " + ifaceNames);
    }

    @Test
    @DisplayName("Animal returns exactly Dog as subtype")
    void animal_returnsExactSubclasses() {
        ToolResponse r = tool.execute(argsByName("com.example.Animal"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> subNames = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "subtypes"), "qualifiedName");
        assertEquals(Set.of("com.example.Dog"), subNames);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Calculator superclasses include java.lang.Object (kind=Class, qualifiedName set)")
    @SuppressWarnings("unchecked")
    void superclasses_includeObjectEntry() {
        ToolResponse r = tool.execute(argsByName("com.example.Calculator"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> superclasses = SemanticAssertions.getList(data, "superclasses");

        // java.lang.Object must appear as a Calculator superclass with kind=Class
        // and the right qualifiedName. The `external` flag is JDT-environment-dependent
        // (it fires only when getResource()/getLocation() returns null) so we don't
        // assert on it here.
        Map<String, Object> object = superclasses.stream()
            .filter(s -> "java.lang.Object".equals(s.get("qualifiedName")))
            .findFirst().orElseThrow();
        assertEquals("Object", object.get("name"));
        assertEquals("class", object.get("kind"));
    }

    @Test
    @DisplayName("Annotation Marker reports kind='annotation' when queried")
    void annotationMarker_kindIsAnnotation() {
        ToolResponse r = tool.execute(argsByName("com.example.Marker"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("annotation", type.get("kind"),
            "Marker annotation must report kind='annotation'; got: " + type);
    }

    @Test
    @DisplayName("Enum Color (nested in TypeKindsFixture) reports kind='enum'")
    void enumColor_kindIsEnum() {
        ToolResponse r = tool.execute(argsByName("com.example.TypeKindsFixture.Color"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("enum", type.get("kind"));
    }

    @Test
    @DisplayName("maxDepth limits subtypes list size and meta.truncated=true")
    @SuppressWarnings("unchecked")
    void maxDepth_limitsSubtypesAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.IShape");
        args.put("maxDepth", 2);

        ToolResponse r = tool.execute(args);
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> subtypes = SemanticAssertions.getList(data, "subtypes");
        assertTrue(subtypes.size() <= 2,
            "maxDepth=2 must cap subtypes list to 2 entries; got: " + subtypes.size());

        // IShape has 7 subtypes; meta.truncated must be true because not all are returned.
        int totalSubtypes = ((Number) data.get("totalSubtypes")).intValue();
        assertEquals(7, totalSubtypes,
            "totalSubtypes reports the true count regardless of cap; got: " + totalSubtypes);
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated(),
            "meta.truncated must be true when subtypes are capped");
    }

    @Test
    @DisplayName("maxDepth=1 caps interfaces list AND triggers meta.truncated when more interfaces exist")
    @SuppressWarnings("unchecked")
    void maxDepth_capsInterfacesListAndSetsTruncated() {
        // FilledCircle directly implements IFillable AND transitively implements
        // IShape (via IFillable extends IShape). With maxDepth=1 the interfaces
        // list must be capped at 1 element while the underlying total >= 2,
        // exercising the `if (interfaceList.size() >= maxDepth) break;` branch
        // (which has its own loop, parallel to but distinct from the superclass
        // and subtype caps that are tested elsewhere).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.FilledCircle");
        args.put("maxDepth", 1);

        ToolResponse r = tool.execute(args);
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        List<Map<String, Object>> interfaces = SemanticAssertions.getList(data, "interfaces");
        assertEquals(1, interfaces.size(),
            "maxDepth=1 must cap interfaces list to a single entry; got: " + interfaces);
        int totalInterfaces = ((Number) data.get("totalInterfaces")).intValue();
        assertTrue(totalInterfaces >= 2,
            "FilledCircle has IFillable + IShape transitively — totalInterfaces >= 2; got: "
                + totalInterfaces);
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated(),
            "meta.truncated must be true when interfaces list is capped; got: " + meta.getTruncated());
    }

    @Test
    @DisplayName("Position-based lookup falls back to typeName when position has no match")
    void positionFallbackToTypeName() {
        // Pass both: a bad position and a valid typeName. The tool should use typeName
        // as a fallback when position lookup fails.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 99999);  // out of file range — position lookup fails
        args.put("column", 99999);
        args.put("typeName", "com.example.IShape");

        ToolResponse r = tool.execute(args);
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("com.example.IShape", type.get("qualifiedName"),
            "When position fails, the tool must use the typeName fallback; got: " + data);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: IShape returns its exact seven transitive subtypes")
    void envelope_iShape_exactSubtypes() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.IShape");
        args.put("maxDepth", 50);
        JsonNode payload = envelope.payload("get_type_hierarchy", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_type_hierarchy failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        Set<String> subs = new java.util.TreeSet<>();
        for (JsonNode s : data.get("subtypes")) subs.add(s.get("qualifiedName").asText());
        assertEquals(Set.of(
            "com.example.IFillable", "com.example.Rectangle", "com.example.FilledCircle",
            "com.example.AnonymousShapeUser$1", "com.example.AnonymousShapeUser$2",
            "com.example.AnonymousShapeUser$InnerShape", "com.example.AnonymousShapeUser$NonStaticInnerShape"),
            subs,
            "IShape's exact transitive subtype set must survive the JSON-RPC envelope");
        assertEquals(7, data.get("totalSubtypes").asInt());
    }
}
