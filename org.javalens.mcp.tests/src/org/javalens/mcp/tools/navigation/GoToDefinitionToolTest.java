package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GoToDefinitionTool.
 * Tests navigation to class, method, and field definitions.
 */
class GoToDefinitionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GoToDefinitionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GoToDefinitionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class definition returns symbol, kind, package, and location")
    @SuppressWarnings("unchecked")
    void classDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertEquals("Class", data.get("kind"));
        assertEquals("com.example", data.get("package"));

        Map<String, Object> location = (Map<String, Object>) data.get("location");
        assertNotNull(location);
        assertNotNull(location.get("filePath"));
        assertNotNull(location.get("line"));
        assertNotNull(location.get("column"));
    }

    @Test
    @DisplayName("Method definition returns symbol, kind, and containingType")
    void methodDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("Method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("containingType"));
    }

    @Test
    @DisplayName("Field definition returns symbol and kind")
    void fieldDefinition_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("symbol"));
        assertEquals("Field", data.get("kind"));
    }

    // ========== Cross-File Navigation Tests ==========

    @Test
    @DisplayName("Type reference navigates to definition in another file")
    void typeReference_navigatesToDefinition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 12);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertNotNull(data.get("location"));
    }

    @Test
    @DisplayName("Method call navigates to method definition")
    void methodCall_navigatesToDefinition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 58);
        args.put("column", 27);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("symbol"));
        assertEquals("Method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("containingType"));
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
    @DisplayName("Position with no symbol handles gracefully")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }

    // ========== Semantic-grade tests (kind reported for interface/sealed/record) ==========

    @Test
    @DisplayName("IShape interface definition reports kind=Interface")
    void iShape_kindIsInterface() {
        String path = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", path);
        args.put("line", 2);  // public interface IShape
        args.put("column", 17);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("IShape", data.get("symbol"));
        assertEquals("Interface", data.get("kind"));
        assertEquals("com.example", data.get("package"));
    }

    @Test
    @DisplayName("Vehicle sealed-interface definition reports kind=Interface")
    void vehicle_kindIsInterface() {
        String path = projectPath.resolve("src/main/java/com/example/Vehicle.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", path);
        args.put("line", 2);  // public sealed interface Vehicle permits Car, Truck
        args.put("column", 24);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Vehicle", data.get("symbol"));
        assertEquals("Interface", data.get("kind"));
    }

    @Test
    @DisplayName("Point record definition reports kind=Record")
    void point_kindIsRecord() {
        String path = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", path);
        args.put("line", 2);  // public record Point(...)
        args.put("column", 14);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Point", data.get("symbol"));
        assertEquals("Record", data.get("kind"));
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) throw new AssertionError("`" + identifier + "` not in " + filePath);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Annotation type (Marker) definition reports kind=Annotation")
    void annotation_kindIsAnnotation() throws Exception {
        String marker = projectPath.resolve("src/main/java/com/example/Marker.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(marker));
        int idx = source.indexOf("@interface Marker");
        idx = source.indexOf("Marker", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", marker);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Marker", data.get("symbol"));
        assertEquals("Annotation", data.get("kind"),
            "Annotation type must report kind='Annotation' (not 'Interface'); got: " + data);
    }

    @Test
    @DisplayName("Enum type (Color) definition reports kind=Enum")
    void enumType_kindIsEnum() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("enum Color");
        idx = source.indexOf("Color", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("Color", getData(r).get("symbol"));
        assertEquals("Enum", getData(r).get("kind"));
    }

    @Test
    @DisplayName("Local variable position resolves to kind=Variable")
    void localVariable_kindIsVariable() throws Exception {
        String rt = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(rt, "trimmed"));
        assertTrue(r.isSuccess(),
            "Position on local variable must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("trimmed", data.get("symbol"));
        assertEquals("Variable", data.get("kind"));
    }

    @Test
    @DisplayName("Type parameter (T in GenericContainer<T>) reports kind=TypeParameter")
    @SuppressWarnings("unchecked")
    void typeParameter_kindIsTypeParameter() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("GenericContainer<T>");
        // Position on the `T` after `<`.
        int tIdx = source.indexOf("T>", idx);
        int line = (int) source.substring(0, tIdx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', tIdx) + 1;
        int column = tIdx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on type parameter T must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("T", data.get("symbol"));
        assertEquals("TypeParameter", data.get("kind"));
    }

    @Test
    @DisplayName("Method on a class with same name across files: containingType is exact FQN")
    @SuppressWarnings("unchecked")
    void methodDefinition_containingTypeIsExactFqn() {
        // Calculator.add. Its containingType must be "com.example.Calculator", not
        // just "Calculator", to disambiguate from any other class named Calculator.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("com.example.Calculator", getData(r).get("containingType"),
            "containingType must be the fully-qualified type name; got: " + getData(r));
    }
}
