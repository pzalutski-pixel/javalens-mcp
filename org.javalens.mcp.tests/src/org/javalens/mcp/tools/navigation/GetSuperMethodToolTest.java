package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.SemanticAssertions;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSuperMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetSuperMethodTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String animalPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSuperMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        animalPath = projectPath.resolve("src/main/java/com/example/Animal.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds super method with complete response")
    void findsSuperMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 22);  // Dog.speak() which overrides Animal.speak()
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify method info
        assertNotNull(data.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> methodInfo = (Map<String, Object>) data.get("method");
        assertNotNull(methodInfo.get("name"));
        assertNotNull(methodInfo.get("signature"));

        // Verify overrides info (may be null if not overriding)
        if (data.get("overrides") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) data.get("overrides");
            assertNotNull(overrides.get("declaringType"));
        }
    }

    @Test @DisplayName("returns null overrides for non-overriding method")
    void handlesNonOverridingMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 7);  // Animal.speak() - base method, not overriding anything
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // Should have null overrides or a message
        if (data.get("overrides") == null) {
            assertNotNull(data.get("message"));
        }
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 22);
        noFile.put("column", 16);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", animalPath);
        noLine.put("column", 16);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 5);  // Class declaration
        args.put("column", 13);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    private String fixturePath(String relative) {
        return projectPath.resolve(relative).toString();
    }

    private ObjectNode argsAt(String filePath, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOverrides(Map<String, Object> data) {
        return (Map<String, Object>) data.get("overrides");
    }

    @Test
    @DisplayName("Dog.speak overrides Animal.speak (class-to-class)")
    void dogSpeak_overridesAnimalSpeak() {
        // Animal.java line 22 (0-based) is `    public void speak()` in Dog class
        ToolResponse r = tool.execute(argsAt(animalPath, 22, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> overrides = getOverrides(data);
        assertNotNull(overrides, "Dog.speak overrides Animal.speak");
        assertEquals("speak", overrides.get("name"));
        assertEquals("com.example.Animal", overrides.get("declaringType"));
        assertEquals(Boolean.FALSE, data.get("implementsInterface"),
            "Animal is a class, not an interface");
    }

    @Test
    @DisplayName("Rectangle.draw overrides IShape.draw (class-to-interface, direct)")
    void rectangleDraw_overridesIShapeDraw() {
        // Rectangle.java line 13 (0-based) is `    public void draw()`
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/Rectangle.java"), 13, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> overrides = getOverrides(data);
        assertNotNull(overrides, "Rectangle.draw overrides IShape.draw");
        assertEquals("draw", overrides.get("name"));
        assertEquals("com.example.IShape", overrides.get("declaringType"));
        assertEquals(Boolean.TRUE, data.get("implementsInterface"));
    }

    @Test
    @DisplayName("FilledCircle.draw transitively overrides IShape.draw via IFillable extends IShape")
    void filledCircleDraw_overridesIShapeDrawTransitively() {
        // FilledCircle.java line 13 (0-based) is `    public void draw()`
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/FilledCircle.java"), 13, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> overrides = getOverrides(data);
        assertNotNull(overrides,
            "FilledCircle.draw must resolve to IShape.draw (declared in supertype interface chain)");
        assertEquals("draw", overrides.get("name"));
        assertEquals("com.example.IShape", overrides.get("declaringType"));
        assertEquals(Boolean.TRUE, data.get("implementsInterface"));
    }

    @Test
    @DisplayName("FilledCircle.fill overrides IFillable.fill (direct interface)")
    void filledCircleFill_overridesIFillableFill() {
        // FilledCircle.java line 18 (0-based) is `    public void fill()`
        ToolResponse r = tool.execute(argsAt(
            fixturePath("src/main/java/com/example/FilledCircle.java"), 18, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> overrides = getOverrides(data);
        assertNotNull(overrides);
        assertEquals("fill", overrides.get("name"));
        assertEquals("com.example.IFillable", overrides.get("declaringType"));
        assertEquals(Boolean.TRUE, data.get("implementsInterface"));
    }

    @Test
    @DisplayName("Animal.speak (base method) has null overrides and explanatory message")
    void animalSpeak_baseMethodHasNoOverrides() {
        // Animal.java line 7 (0-based) is `    public void speak()` in Animal class
        ToolResponse r = tool.execute(argsAt(animalPath, 7, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        assertNull(data.get("overrides"),
            "Animal.speak does not override anything; overrides must be null");
        assertNotNull(data.get("message"),
            "Tool must return an explanatory message when there is no override");
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
    @DisplayName("Method response info includes signature, modifiers, declaringType, location")
    @SuppressWarnings("unchecked")
    void methodInfo_carriesFullShape() {
        // Use Dog.speak — known override case.
        ToolResponse r = tool.execute(argsAt(animalPath, 22, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> method = (Map<String, Object>) data.get("method");
        assertNotNull(method, "method block must be present");
        assertEquals("speak", method.get("name"));
        assertEquals("com.example.Dog", method.get("declaringType"));
        assertEquals("speak(): void", method.get("signature"),
            "signature must be `name(params): returnType`; got: " + method);
        List<String> modifiers = (List<String>) method.get("modifiers");
        assertTrue(modifiers.contains("public"));
        String filePath = (String) method.get("filePath");
        assertNotNull(filePath, "filePath missing: " + method);
        assertTrue(filePath.endsWith("Animal.java"),
            "Dog.speak is declared in Animal.java (the fixture file); got: " + method);
        assertTrue(((Number) method.get("line")).intValue() >= 0, "line >= 0; got: " + method);
    }

    @Test
    @DisplayName("Override entry includes the supertype's signature and declaringType")
    @SuppressWarnings("unchecked")
    void overrideEntry_carriesFullShape() {
        ToolResponse r = tool.execute(argsAt(animalPath, 22, 16));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);

        Map<String, Object> overrides = (Map<String, Object>) data.get("overrides");
        assertNotNull(overrides);
        assertEquals("speak", overrides.get("name"));
        assertEquals("com.example.Animal", overrides.get("declaringType"));
        assertEquals("speak(): void", overrides.get("signature"));
    }

    @Test
    @DisplayName("Static method (TypeKindsFixture.staticHelper) does not override anything")
    void staticMethod_noOverride() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "staticHelper"));
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        assertNull(data.get("overrides"),
            "Static method on a class with no static super-method has null overrides; got: " + data);
    }

    @Test
    @DisplayName("Constructor (HelloWorld()) does not override anything")
    void constructor_noOverride() throws Exception {
        String hello = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(hello));
        int idx = source.indexOf("public HelloWorld(");
        idx = source.indexOf("HelloWorld(", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", hello);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        Map<String, Object> data = SemanticAssertions.assertSuccessData(r);
        assertNull(data.get("overrides"),
            "Constructors do not override any method; got: " + data);
    }
}
