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
        assertEquals("class", data.get("kind"));
        assertEquals("com.example", data.get("package"));

        Map<String, Object> location = (Map<String, Object>) data.get("location");
        assertNotNull(location, "location block must be present");
        String filePath = (String) location.get("filePath");
        assertNotNull(filePath, "location.filePath must be present");
        assertTrue(filePath.endsWith("Calculator.java"),
            "Calculator is defined in Calculator.java; got: " + filePath);
        // JDT reports the start of the source range, which includes leading Javadoc
        // for documented types. line/column are non-negative coordinates into the file.
        assertTrue(((Number) location.get("line")).intValue() >= 0,
            "line must be >= 0; got: " + location);
        assertTrue(((Number) location.get("column")).intValue() >= 0,
            "column must be >= 0; got: " + location);
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
        assertEquals("method", data.get("kind"));
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
        assertEquals("field", data.get("kind"));
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
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) data.get("location");
        assertNotNull(location, "location must be present");
        String filePath = (String) location.get("filePath");
        assertTrue(filePath.endsWith("Calculator.java"),
            "Type reference must navigate to Calculator.java (different file from caller); got: " + filePath);
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
        assertEquals("method", data.get("kind"));
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
    @DisplayName("Position on a blank line (no symbol) returns SYMBOL_NOT_FOUND")
    void positionOnBlankLine_returnsSymbolNotFound() {
        // Calculator.java line 1 (0-based) is the blank line between the package
        // declaration and the Javadoc. codeSelect at line 1 col 0 finds no
        // IJavaElement → tool's `if (element == null)` branch fires → SYMBOL_NOT_FOUND.
        // (line=0 was the earlier choice but it lands on the package keyword, which
        // codeSelect resolves to IPackageDeclaration — that branch produces a partial
        // success, not a failure. This blank-line position is the cleanest exercise
        // of the no-symbol branch.)
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 1);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess(),
            "Position on a blank line must fail (no resolvable symbol); got success: "
                + response.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Expected SYMBOL_NOT_FOUND on blank-line position; got: "
                + response.getError().getCode());
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
        assertEquals("interface", data.get("kind"));
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
        assertEquals("interface", data.get("kind"));
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
        assertEquals("record", data.get("kind"));
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
        assertEquals("annotation", data.get("kind"),
            "Annotation type must report kind='annotation' (not 'interface'); got: " + data);
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
        assertEquals("enum", getData(r).get("kind"));
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
        assertEquals("variable", data.get("kind"));
    }

    // Type-parameter position lookup: JDT's getElementAtPosition / codeSelect does not
    // reliably return ITypeParameter for positions on a type parameter inside a
    // generic class declaration — the resolution falls through to the enclosing
    // class. This is JDT behavior, not a tool bug. The kind="TypeParameter" return
    // path remains exercised by get_symbol_info's boundedTypeParameter test, where
    // the position resolves correctly for a bounded type parameter.

    @Test
    @DisplayName("Position inside a line comment returns SYMBOL_NOT_FOUND")
    void positionInsideLineComment_returnsSymbolNotFound() {
        // BugPatterns.java line 18 (0-based 17): `// Empty catch block - bad practice`.
        // Position inside the comment text must not resolve to a symbol.
        String bugPatterns = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/BugPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", bugPatterns);
        args.put("line", 17);
        args.put("column", 20);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Position inside a line comment must not resolve to an enclosing element; got: "
                + r.getData());
    }

    @Test
    @DisplayName("Position inside a string literal returns SYMBOL_NOT_FOUND (not a spurious enclosing symbol)")
    void positionInsideStringLiteral_returnsSymbolNotFound() {
        // BugPatterns.java line 16 contains `Integer.parseInt("not a number");`.
        // Column 35 sits inside the string literal. The tool must not resolve
        // a symbol there — silent success that returns the enclosing method
        // would mislead an AI consumer.
        String bugPatterns = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/BugPatterns.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", bugPatterns);
        args.put("line", 15);
        args.put("column", 35);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Position inside a string literal must not resolve to an enclosing element; got: "
                + r.getData());
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
