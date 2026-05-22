package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.SemanticAssertions;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindImplementationsTool;
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
 * Integration tests for FindImplementationsTool.
 * Tests finding implementations of types and methods.
 */
class FindImplementationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindImplementationsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindImplementationsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getImplementations(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("implementations");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class implementations returns symbol, isInterface, totalCount, and implementation list")
    void classImplementations_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertEquals(false, data.get("isInterface"));
        int total = ((Number) data.get("totalImplementations")).intValue();
        assertTrue(total >= 0, "totalImplementations >= 0; got: " + data);

        List<Map<String, Object>> implementations = getImplementations(data);
        assertNotNull(implementations);
        assertEquals(total, implementations.size(),
            "totalImplementations must equal implementations list size; got: " + data);
        for (Map<String, Object> impl : implementations) {
            String qn = (String) impl.get("qualifiedName");
            assertNotNull(qn, "qualifiedName missing: " + impl);
            assertTrue(qn.contains("."), "qualifiedName must include package; got: " + impl);
        }
    }

    @Test
    @DisplayName("Method position finds containing type implementations")
    void methodPosition_findsContainingTypeImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("symbol"));
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("maxResults limits number of implementations returned")
    void maxResults_limitsImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);
        args.put("maxResults", 5);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> implementations = getImplementations(data);
        assertTrue(implementations.size() <= 5);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 5);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 5);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Field position finds enclosing type implementations")
    void fieldPosition_findsEnclosingTypeImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
    }

    @Test
    @DisplayName("Position with no symbol handles gracefully")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    private ObjectNode argsAt(String filePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        args.put("maxResults", 1000);
        return args;
    }

    private String fixturePath(String relative) {
        return projectPath.resolve(relative).toString();
    }

    @Test
    @DisplayName("Vehicle (sealed interface) returns exactly its permitted subtypes Car and Truck")
    void vehicle_returnsExactPermittedSubtypes() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Vehicle.java"), 2, 24));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");
        assertEquals(Set.of("com.example.Car", "com.example.Truck"), names,
            "Vehicle's permitted subtypes are exactly Car and Truck");
    }

    @Test
    @DisplayName("Rectangle (no subclass) returns exactly empty implementations list")
    void rectangle_returnsExactlyEmpty() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Rectangle.java"), 2, 13));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        assertEquals(0, ((Number) data.get("totalImplementations")).intValue(),
            "Rectangle has no subclasses in this fixture");
        assertEquals(List.of(),
            SemanticAssertions.getList(data, "implementations"));
    }

    @Test
    @DisplayName("IFillable returns FilledCircle (direct named implementor) and excludes IShape's other implementors")
    void iFillable_returnsDirectImplementor() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IFillable.java"), 2, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");

        assertTrue(names.contains("com.example.FilledCircle"),
            "FilledCircle directly implements IFillable; got: " + names);
        // Isolation: types that implement IShape but NOT IFillable must not appear
        assertFalse(names.contains("com.example.Rectangle"),
            "Rectangle implements IShape, not IFillable");
        assertFalse(names.contains("com.example.IShape"),
            "IShape is the supertype of IFillable, not its implementor");
    }

    @Test
    @DisplayName("Animal returns exactly Dog (its only subclass)")
    void animal_returnsExactSubclasses() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Animal.java"), 5, 13));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");
        assertEquals(Set.of("com.example.Dog"), names);
    }

    @Test
    @DisplayName("IShape returns transitive implementors via sub-interface chain (FilledCircle through IFillable)")
    void iShape_returnsTransitiveImplementors() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 2, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");

        assertTrue(names.contains("com.example.IFillable"),
            "IFillable is a direct sub-interface; got: " + names);
        assertTrue(names.contains("com.example.Rectangle"),
            "Rectangle directly implements IShape; got: " + names);
        assertTrue(names.contains("com.example.FilledCircle"),
            "FilledCircle implements IFillable which extends IShape — must appear transitively; got: " + names);

        // Isolation: unrelated hierarchies must not appear
        assertFalse(names.contains("com.example.Animal"));
        assertFalse(names.contains("com.example.Dog"));
        assertFalse(names.contains("com.example.Vehicle"));
        assertFalse(names.contains("com.example.Car"));
        assertFalse(names.contains("com.example.Truck"));
    }

    @Test
    @DisplayName("IShape result includes all 7 implementors: 3 top-level, 2 anonymous, 2 inner")
    void iShape_includesAnonymousAndInnerImplementors() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 2, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");

        // JDT uses '$' as the separator for inner classes and indices for anonymous classes.
        assertEquals(
            Set.of(
                "com.example.IFillable",
                "com.example.Rectangle",
                "com.example.FilledCircle",
                "com.example.AnonymousShapeUser$InnerShape",
                "com.example.AnonymousShapeUser$NonStaticInnerShape",
                "com.example.AnonymousShapeUser$1",
                "com.example.AnonymousShapeUser$2"
            ),
            names,
            "IShape's transitive implementors are exactly the seven types above");
        assertEquals(7, ((Number) data.get("totalImplementations")).intValue());
    }

    @Test
    @DisplayName("IShape.draw() method returns transitive method overriders (Rectangle.draw, FilledCircle.draw)")
    void iShapeDraw_returnsTransitiveMethodOverriders() {
        // IShape.draw() is on line 4 (0-based); column where "draw" starts after "    void "
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 4, 9));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");
        // Both direct (Rectangle) and transitive-via-sub-interface (FilledCircle) must be in the result
        assertTrue(names.contains("com.example.Rectangle"),
            "Rectangle.draw() overrides IShape.draw() directly");
        assertTrue(names.contains("com.example.FilledCircle"),
            "FilledCircle.draw() overrides IShape.draw() via IFillable extends IShape");
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("isInterface=true when target is an interface (IShape)")
    void interfaceTarget_isInterfaceFlagTrue() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 2, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        assertEquals(Boolean.TRUE, data.get("isInterface"),
            "Target IShape is an interface; isInterface must be true; got: " + data);
    }

    @Test
    @DisplayName("Each implementation entry has a kind ('class'/'interface'/'record'/'enum'/'annotation')")
    @SuppressWarnings("unchecked")
    void implementationEntries_carryKindField() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 2, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        java.util.List<Map<String, Object>> impls = SemanticAssertions.getList(data, "implementations");

        // Find specific implementors and verify kind.
        Map<String, Object> rectangle = impls.stream()
            .filter(i -> "com.example.Rectangle".equals(i.get("qualifiedName")))
            .findFirst().orElseThrow();
        assertEquals("class", rectangle.get("kind"),
            "Rectangle is a class — kind must be 'class'; got: " + rectangle);

        Map<String, Object> iFillable = impls.stream()
            .filter(i -> "com.example.IFillable".equals(i.get("qualifiedName")))
            .findFirst().orElseThrow();
        assertEquals("interface", iFillable.get("kind"),
            "IFillable is an interface — kind must be 'interface'; got: " + iFillable);
    }

    @Test
    @DisplayName("Greeter (plain interface): enum and record implementers both surface")
    @SuppressWarnings("unchecked")
    void greeter_returnsEnumAndRecordImplementers() {
        // Greeter is implemented by GreetingMode (enum) and GreetingRecord (record).
        // find_implementations must surface both — implementer kind (class vs
        // enum vs record) must not filter results.
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Greeter.java"), 6, 17));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        java.util.Set<String> names = SemanticAssertions.fieldSet(
            SemanticAssertions.getList(data, "implementations"), "qualifiedName");
        assertEquals(Set.of("com.example.GreetingMode", "com.example.GreetingRecord"), names,
            "Greeter has exactly two implementers — an enum and a record; got: " + names);
    }

    @Test
    @DisplayName("Method-level target: each entry carries `method` field with overrider's method name")
    @SuppressWarnings("unchecked")
    void methodLevelTarget_entriesCarryMethodField() {
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/IShape.java"), 4, 9));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        java.util.List<Map<String, Object>> impls = SemanticAssertions.getList(data, "implementations");
        // Tool also reports the target's method name on the response.
        assertEquals("draw", data.get("method"),
            "Response must surface the targeted method name at the top level; got: " + data);
        // Each implementation entry must include the overriding method name too.
        for (Map<String, Object> impl : impls) {
            assertEquals("draw", impl.get("method"),
                "Each method-level impl entry must report the overriding method name; got: " + impl);
        }
    }
}
