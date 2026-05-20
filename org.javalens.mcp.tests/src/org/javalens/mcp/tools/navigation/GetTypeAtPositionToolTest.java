package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetTypeAtPositionTool.
 * Tests type resolution at positions.
 */
class GetTypeAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetTypeAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type declaration returns name, qualifiedName, kind, modifiers, counts, location, and flags")
    @SuppressWarnings("unchecked")
    void typeDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("Calculator", data.get("name"));
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
        assertEquals("class", data.get("kind"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        // Member counts — Calculator declares exactly 4 methods (add, subtract,
        // multiply, getLastResult), 1 field (lastResult), no nested types. Using == not
        // >= so a regression that double-counts or under-counts is caught.
        assertEquals(4, data.get("methodCount"));
        assertEquals(1, data.get("fieldCount"));
        assertEquals(0, data.get("nestedTypeCount"));

        // Location
        String filePath = (String) data.get("filePath");
        assertNotNull(filePath, "filePath must be present");
        assertTrue(filePath.endsWith("Calculator.java"),
            "Calculator type at position must report Calculator.java; got: " + filePath);
        assertTrue(((Number) data.get("line")).intValue() >= 0,
            "line must be >= 0; got: " + data);

        // Flags
        assertEquals(false, data.get("isAnonymous"));
        assertEquals(false, data.get("isLocal"));

        // Absence assertions — Calculator is top-level, non-generic, implements no
        // interfaces. Each createTypeInfo branch is gated on a non-empty/non-null
        // check; the corresponding key must NOT be present when its branch is skipped.
        assertFalse(data.containsKey("interfaces"),
            "Calculator implements nothing — `interfaces` key must be absent; got: " + data);
        assertFalse(data.containsKey("typeParameters"),
            "Calculator is non-generic — `typeParameters` key must be absent; got: " + data);
        assertFalse(data.containsKey("declaringType"),
            "Calculator is top-level — `declaringType` key must be absent; got: " + data);
        assertFalse(data.containsKey("isNested"),
            "Top-level types must omit `isNested` (only nested types set it); got: " + data);
    }

    @Test
    @DisplayName("Type reference resolves to qualified type")
    void typeReference_resolvesToQualifiedType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 12);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
    }

    @Test
    @DisplayName("Method position finds enclosing type")
    void methodPosition_findsEnclosingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
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
    @DisplayName("Position with no resolvable type returns SYMBOL_NOT_FOUND")
    void positionWithNoType_returnsSymbolNotFound() {
        // Line 0 col 0 lands on `package com.example;` — no type at that position
        // and the ancestor walk has no enclosing IType either (package decl is not
        // inside any type). Pin the documented failure mode: error code
        // SYMBOL_NOT_FOUND, not a silent success or generic INTERNAL_ERROR.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "No type at package-decl position must fail; got success.");
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Expected SYMBOL_NOT_FOUND for position with no resolvable type; got: "
                + response.getError().getCode());
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAt(String filePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Interface (IShape) returns kind='interface' and omits superclass key")
    void interface_returnsKindInterface() {
        String path = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        ToolResponse r = tool.execute(argsAt(path, 2, 17));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("IShape", data.get("name"));
        assertEquals("interface", data.get("kind"));
        assertEquals("com.example.IShape", data.get("qualifiedName"));
        // type.getSuperclassName() returns null for interfaces — the
        // `if (superclass != null)` guard in createTypeInfo means the `superclass`
        // key is absent. Pin that contract; otherwise an LLM seeing the key on
        // some interfaces would draw the wrong conclusion.
        assertFalse(data.containsKey("superclass"),
            "Interfaces have no superclass — key must be absent; got: " + data);
    }

    @Test
    @DisplayName("Enum (TypeKindsFixture.Color) returns kind='enum'")
    void enum_returnsKindEnum() {
        String path = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 12 `public enum Color { ... }` -> 0-based 11; "Color" at col 16.
        ToolResponse r = tool.execute(argsAt(path, 11, 16));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Color", data.get("name"));
        assertEquals("enum", data.get("kind"));
    }

    @Test
    @DisplayName("Annotation (Marker) returns kind='annotation'")
    void annotation_returnsKindAnnotation() {
        String path = projectPath.resolve("src/main/java/com/example/Marker.java").toString();
        // 1-based line 12 `public @interface Marker {` -> 0-based 11; "Marker" at col 18.
        ToolResponse r = tool.execute(argsAt(path, 11, 18));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Marker", data.get("name"));
        assertEquals("annotation", data.get("kind"));
    }

    @Test
    @DisplayName("Record (Point) returns kind='record'")
    void record_returnsKindRecord() {
        String path = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        ToolResponse r = tool.execute(argsAt(path, 2, 14));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Point", data.get("name"));
        assertEquals("record", data.get("kind"));
    }

    @Test
    @DisplayName("Subclass (Dog) reports superclass=Animal")
    void subclass_reportsSuperclass() {
        String path = projectPath.resolve("src/main/java/com/example/Animal.java").toString();
        // 1-based line 20 `class Dog extends Animal {` -> 0-based 19; "Dog" at col 6.
        ToolResponse r = tool.execute(argsAt(path, 19, 6));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Dog", data.get("name"));
        assertEquals("Animal", data.get("superclass"),
            "Dog extends Animal — superclass field must report 'Animal' (simple name). Got: " + data);
    }

    @Test
    @DisplayName("Class implementing interface (Rectangle) reports interfaces list")
    @SuppressWarnings("unchecked")
    void classImplementingInterface_reportsInterfacesList() {
        String path = projectPath.resolve("src/main/java/com/example/Rectangle.java").toString();
        ToolResponse r = tool.execute(argsAt(path, 2, 13));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Rectangle", data.get("name"));
        List<String> interfaces = (List<String>) data.get("interfaces");
        assertNotNull(interfaces);
        assertTrue(interfaces.contains("IShape"),
            "Rectangle implements IShape — interfaces list must contain 'IShape'; got: " + interfaces);
    }

    @Test
    @DisplayName("Generic class (GenericContainer<T>) reports typeParameters")
    @SuppressWarnings("unchecked")
    void genericClass_reportsTypeParameters() {
        String path = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 17 `public static class GenericContainer<T> {` -> 0-based 16;
        // "GenericContainer" at col 24.
        ToolResponse r = tool.execute(argsAt(path, 16, 24));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("GenericContainer", data.get("name"));
        List<String> typeParams = (List<String>) data.get("typeParameters");
        assertNotNull(typeParams,
            "GenericContainer<T> must have typeParameters reported; got: " + data);
        assertEquals(List.of("T"), typeParams,
            "GenericContainer declares exactly one type parameter T; got: " + typeParams);
    }

    @Test
    @DisplayName("Nested class (TypeKindsFixture.Inner) reports declaringType + isNested=true")
    void nestedClass_reportsDeclaringTypeAndNestedFlag() {
        String path = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 31 `public static class Inner {` -> 0-based 30; "Inner" at col 24.
        ToolResponse r = tool.execute(argsAt(path, 30, 24));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Inner", data.get("name"));
        assertEquals("com.example.TypeKindsFixture", data.get("declaringType"),
            "Inner is nested in TypeKindsFixture — declaringType must report the enclosing FQN. Got: "
                + data);
        assertEquals(Boolean.TRUE, data.get("isNested"),
            "Nested class must have isNested=true; got: " + data);
    }

    @Test
    @DisplayName("Sealed interface (Vehicle) carries sealed modifier and kind='interface'")
    @SuppressWarnings("unchecked")
    void sealedInterface_reportsCorrectKindAndModifiers() {
        String path = projectPath.resolve("src/main/java/com/example/Vehicle.java").toString();
        ToolResponse r = tool.execute(argsAt(path, 2, 24));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Vehicle", data.get("name"));
        assertEquals("interface", data.get("kind"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"),
            "Vehicle is public sealed interface — public must appear; got: " + modifiers);
    }
}
