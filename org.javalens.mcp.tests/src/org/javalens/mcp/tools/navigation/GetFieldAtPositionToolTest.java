package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetFieldAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetFieldAtPositionTool.
 * Tests field info extraction.
 */
class GetFieldAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetFieldAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetFieldAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Field declaration returns name, type, modifiers, declaringType, filePath, line, and flags")
    @SuppressWarnings("unchecked")
    void fieldDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("lastResult", data.get("name"));
        assertEquals("int", data.get("type"));
        assertEquals("com.example.Calculator", data.get("declaringType"));

        // Location
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("line"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("private"));

        // Flags
        assertEquals(false, data.get("isConstant"));
        assertEquals(false, data.get("isEnumConstant"));
    }

    @Test
    @DisplayName("Object type field returns correct type")
    void objectTypeField_returnsCorrectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 6);
        args.put("column", 19);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("greeting", data.get("name"));
        assertEquals("String", data.get("type"));
    }

    @Test
    @DisplayName("Field reference in method body returns field info")
    void fieldReferenceInMethod_returnsFieldInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 15);
        args.put("column", 8);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 6);
        args1.put("column", 16);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 16);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 6);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position on method returns error (not a field)")
    void positionOnMethod_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    /**
     * Resolve the 0-based line/column where the given identifier first appears in the
     * file. Lets tests avoid fragile hand-counted positions.
     */
    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) {
            throw new AssertionError("Identifier `" + identifier + "` not found in " + filePath);
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
    @DisplayName("Static final field (MAX_SIZE=100) reports isConstant=true and constantValue")
    @SuppressWarnings("unchecked")
    void staticFinalField_reportsConstantValueAndIsConstant() throws Exception {
        String refTarget = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(refTarget, "MAX_SIZE"));
        assertTrue(r.isSuccess(),
            "Position on MAX_SIZE field must succeed; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("MAX_SIZE", data.get("name"));
        assertEquals("int", data.get("type"));
        assertEquals(Boolean.TRUE, data.get("isConstant"),
            "static + final → isConstant must be true; got: " + data);

        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("static"),
            "static modifier must appear; got: " + modifiers);
        assertTrue(modifiers.contains("final"),
            "final modifier must appear; got: " + modifiers);
        assertTrue(modifiers.contains("private"),
            "private modifier must appear; got: " + modifiers);

        // Constant value: MAX_SIZE is set to 100 — Eclipse compute-constant should
        // expose this via IField.getConstant().
        assertEquals("100", data.get("constantValue"),
            "constantValue must be 100; got: " + data);
    }

    @Test
    @DisplayName("Enum constant (Color.RED) reports isEnumConstant=true")
    @SuppressWarnings("unchecked")
    void enumConstant_reportsIsEnumConstantTrue() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "RED"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("RED", data.get("name"));
        assertEquals(Boolean.TRUE, data.get("isEnumConstant"),
            "Enum constant must have isEnumConstant=true; got: " + data);
        assertEquals("com.example.TypeKindsFixture.Color", data.get("declaringType"),
            "Declaring type must be the enum; got: " + data);
    }

    @Test
    @DisplayName("Protected field reports modifier 'protected'")
    @SuppressWarnings("unchecked")
    void protectedField_reportsProtectedModifier() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "protectedField"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("protectedField", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("protected"),
            "protected modifier must appear; got: " + modifiers);
    }

    @Test
    @DisplayName("Transient field reports modifier 'transient'")
    @SuppressWarnings("unchecked")
    void transientField_reportsTransientModifier() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "transientField"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("transientField", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("transient"),
            "transient modifier must appear; got: " + modifiers);
    }

    @Test
    @DisplayName("Volatile field reports modifier 'volatile'")
    @SuppressWarnings("unchecked")
    void volatileField_reportsVolatileModifier() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "volatileField"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("volatileField", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("volatile"),
            "volatile modifier must appear; got: " + modifiers);
    }
}
