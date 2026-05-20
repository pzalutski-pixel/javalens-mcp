package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetSymbolInfoTool.
 * Tests symbol metadata extraction.
 */
class GetSymbolInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetSymbolInfoTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSymbolInfoTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Type symbol returns name, kind, qualifiedName, typeKind, filePath, line, and column")
    void typeSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("class", data.get("kind"),
            "kind delegates to TypeKindResolver for type elements — lowercase per B-6 fix");
        assertEquals("com.example.Calculator", data.get("qualifiedName"));
        assertEquals("class", data.get("typeKind"));
        String filePath = (String) data.get("filePath");
        assertNotNull(filePath, "filePath must be present");
        assertTrue(filePath.endsWith("Calculator.java"),
            "filePath must point to Calculator.java; got: " + filePath);
        assertTrue(((Number) data.get("line")).intValue() >= 0,
            "line must be >= 0; got: " + data);
        assertTrue(((Number) data.get("column")).intValue() >= 0,
            "column must be >= 0; got: " + data);
    }

    @Test
    @DisplayName("Method symbol returns name, kind, returnType, signature, parameters, modifiers, and declaringType")
    @SuppressWarnings("unchecked")
    void methodSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("name"));
        assertEquals("method", data.get("kind"));
        assertEquals("int", data.get("returnType"));
        assertEquals("com.example.Calculator", data.get("declaringType"));

        assertNotNull(data.get("signature"));
        assertTrue(data.get("signature").toString().contains("add"));

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("parameters");
        assertNotNull(params);
        assertEquals(2, params.size());
        assertEquals("int", params.get(0).get("type"));
        assertEquals("a", params.get(0).get("name"));

        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));
    }

    @Test
    @DisplayName("Field symbol returns name, kind, type, and isEnumConstant")
    void fieldSymbol_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
        assertEquals("field", data.get("kind"));
        assertEquals("int", data.get("type"));
        assertEquals(false, data.get("isEnumConstant"));
    }

    @Test
    @DisplayName("Constructor is marked with isConstructor flag")
    void constructor_markedCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 11);
        args.put("column", 11);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(true, data.get("isConstructor"));
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
    @DisplayName("Position with no symbol handles gracefully")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) {
            throw new AssertionError("`" + identifier + "` not found in " + filePath);
        }
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
    @DisplayName("Interface symbol reports typeKind='interface' (lowercase)")
    void interfaceSymbol_typeKindIsLowercaseInterface() throws Exception {
        String iShape = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(iShape, "IShape"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("IShape", data.get("name"));
        assertEquals("interface", data.get("kind"),
            "kind delegates to TypeKindResolver — IShape is interface");
        assertEquals("interface", data.get("typeKind"),
            "typeKind must be lowercase 'interface'; got: " + data);
    }

    @Test
    @DisplayName("Annotation symbol reports typeKind='annotation'")
    void annotationSymbol_typeKindIsAnnotation() throws Exception {
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
        assertEquals("annotation", getData(r).get("typeKind"),
            "Annotation type must report typeKind='annotation'; got: " + getData(r));
    }

    @Test
    @DisplayName("Enum symbol reports typeKind='enum'")
    void enumSymbol_typeKindIsEnum() throws Exception {
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
        assertEquals("enum", getData(r).get("typeKind"));
    }

    @Test
    @DisplayName("Record symbol reports typeKind='record'")
    void recordSymbol_typeKindIsRecord() throws Exception {
        String point = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(point, "Point"));
        assertTrue(r.isSuccess());
        assertEquals("record", getData(r).get("typeKind"));
    }

    @Test
    @DisplayName("Generic class symbol reports typeParameters=[T]")
    @SuppressWarnings("unchecked")
    void genericClassSymbol_reportsTypeParameters() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("class GenericContainer<T>");
        idx = source.indexOf("GenericContainer", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<String> typeParams = (List<String>) getData(r).get("typeParameters");
        assertEquals(List.of("T"), typeParams);
    }

    @Test
    @DisplayName("Class with implements: interfaces list surfaced (Rectangle implements IShape)")
    @SuppressWarnings("unchecked")
    void classWithImplements_reportsInterfaces() throws Exception {
        String rect = projectPath.resolve("src/main/java/com/example/Rectangle.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(rect));
        int idx = source.indexOf("class Rectangle");
        idx = source.indexOf("Rectangle", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", rect);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<String> interfaces = (List<String>) getData(r).get("interfaces");
        assertNotNull(interfaces);
        assertTrue(interfaces.contains("IShape"),
            "interfaces list must contain IShape; got: " + interfaces);
    }

    @Test
    @DisplayName("Method with throws clause: exceptions list surfaced")
    @SuppressWarnings("unchecked")
    void methodWithThrows_reportsExceptionsList() throws Exception {
        String sp = projectPath.resolve("src/main/java/com/example/SearchPatterns.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(sp));
        int idx = source.indexOf("void readFile");
        idx = source.indexOf("readFile", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", sp);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<String> exceptions = (List<String>) getData(r).get("exceptions");
        assertNotNull(exceptions);
        assertEquals(List.of("IOException"), exceptions);
    }

    @Test
    @DisplayName("Static-final field reports constantValue")
    void staticFinalField_reportsConstantValue() throws Exception {
        String rt = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(rt, "MAX_SIZE"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("MAX_SIZE", data.get("name"));
        assertEquals("100", data.get("constantValue"));
    }

    @Test
    @DisplayName("Enum constant reports isEnumConstant=true")
    void enumConstant_isEnumConstantTrue() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "RED"));
        assertTrue(r.isSuccess());
        assertEquals(Boolean.TRUE, getData(r).get("isEnumConstant"));
    }

    @Test
    @DisplayName("Local variable (RefactoringTarget.processData `trimmed`): kind=LocalVariable, isParameter=false")
    void localVariable_kindAndIsParameter() throws Exception {
        String rt = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        // RefactoringTarget.processData has `String trimmed = input.trim();`.
        // Position on `trimmed` identifier.
        ToolResponse r = tool.execute(argsAtIdentifier(rt, "trimmed"));
        assertTrue(r.isSuccess(),
            "Position on local variable must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("trimmed", data.get("name"));
        assertEquals("variable", data.get("kind"));
        assertEquals(Boolean.FALSE, data.get("isParameter"),
            "trimmed is a local var, not a parameter; got: " + data);
        assertEquals("String", data.get("type"));
    }

    @Test
    @DisplayName("Method parameter: kind=LocalVariable, isParameter=true")
    void parameter_isParameterTrue() throws Exception {
        // Calculator.add has parameter `a`. Position at the parameter in the body
        // `lastResult = a + b;`. Find the `+ ` to position on `a`.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(calculatorPath));
        int eq = source.indexOf("lastResult = a + b");
        int aIdx = source.indexOf("a + b", eq);
        int line = (int) source.substring(0, aIdx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', aIdx) + 1;
        int column = aIdx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on parameter `a` must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("a", data.get("name"));
        assertEquals("variable", data.get("kind"));
        assertEquals(Boolean.TRUE, data.get("isParameter"),
            "a is a parameter; got: " + data);
    }

    @Test
    @DisplayName("No-arg method (Calculator.getLastResult) omits the `parameters` key entirely")
    void noArgMethod_omitsParametersKey() {
        // Calculator.getLastResult takes no parameters. addMethodInfo guards
        // `if (!params.isEmpty()) info.put("parameters", params)` — so a no-arg method
        // must NOT include the `parameters` key (vs. including an empty list). This
        // pins the shape contract for the LLM consumer.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 45);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on Calculator.getLastResult must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        Map<String, Object> data = getData(r);
        assertEquals("getLastResult", data.get("name"));
        assertEquals("method", data.get("kind"));
        assertEquals("int", data.get("returnType"));
        assertFalse(data.containsKey("parameters"),
            "No-arg method must NOT carry the `parameters` key; got: " + data);
        // exceptions key must also be absent (no throws clause) — pins the
        // parallel `if (exceptions.length > 0)` guard.
        assertFalse(data.containsKey("exceptions"),
            "Method with no throws clause must NOT carry the `exceptions` key; got: " + data);
        // signature follows MethodFormatter convention: `name(): returnType`.
        assertEquals("getLastResult(): int", data.get("signature"),
            "No-arg signature must be `name(): returnType`; got: " + data.get("signature"));
    }

    @Test
    @DisplayName("Bounded type parameter (BoundedBox<N extends Number>) reports bounds=[Number]")
    @SuppressWarnings("unchecked")
    void boundedTypeParameter_reportsBounds() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // Find the `<N extends Number>` declaration and position on `N`.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("BoundedBox<N");
        idx = source.indexOf("N", idx + "BoundedBox<".length() - 1);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on type parameter N must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("typeParameter", data.get("kind"));
        List<String> bounds = (List<String>) data.get("bounds");
        assertNotNull(bounds, "Type parameter with bounds must report bounds; got: " + data);
        assertEquals(List.of("Number"), bounds);
    }
}
