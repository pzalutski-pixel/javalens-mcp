package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
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
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeHierarchyTool(() -> service);
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
        assertNotNull(typeInfo);
        assertEquals("Calculator", typeInfo.get("name"));
        assertEquals("com.example.Calculator", typeInfo.get("qualifiedName"));
        assertEquals("Class", typeInfo.get("kind"));
        assertNotNull(typeInfo.get("line"));
        assertNotNull(typeInfo.get("filePath"));

        // Superclasses including Object
        List<Map<String, Object>> superclasses = getSuperclasses(data);
        assertNotNull(superclasses);
        assertTrue(superclasses.stream().anyMatch(s -> "Object".equals(s.get("name"))));

        // Subtypes (Calculator has none)
        List<Map<String, Object>> subtypes = getSubtypes(data);
        assertNotNull(subtypes);
        assertEquals(0, subtypes.size());

        // Interfaces (Calculator has none)
        List<Map<String, Object>> interfaces = getInterfaces(data);
        assertNotNull(interfaces);

        // Counts
        assertNotNull(data.get("totalSuperclasses"));
        assertNotNull(data.get("totalInterfaces"));
        assertNotNull(data.get("totalSubtypes"));
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
    @DisplayName("Missing position and typeName returns error")
    void missingParameters_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("Invalid type name returns error")
    void invalidTypeName_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.NonExistent");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("Negative line handled gracefully")
    void negativeLine_handledGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", -1);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
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
        assertEquals("Record", typeInfo.get("kind"));

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
}
