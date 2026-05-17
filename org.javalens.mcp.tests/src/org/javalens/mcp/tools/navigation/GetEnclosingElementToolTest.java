package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetEnclosingElementTool.
 * Tests context resolution at positions.
 */
class GetEnclosingElementToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetEnclosingElementTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetEnclosingElementTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getEnclosingType(Map<String, Object> data) {
        return (Map<String, Object>) data.get("enclosingType");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Method body position returns enclosingType with name, qualifiedName, kind, filePath, and package")
    @SuppressWarnings("unchecked")
    void methodBodyPosition_returnsAllContextInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 15);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // File info
        assertNotNull(data.get("filePath"));
        assertEquals("com.example", data.get("enclosingPackage"));

        // Enclosing type info
        Map<String, Object> enclosingType = getEnclosingType(data);
        assertNotNull(enclosingType);
        assertEquals("Calculator", enclosingType.get("name"));
        assertEquals("com.example.Calculator", enclosingType.get("qualifiedName"));
        assertEquals("class", enclosingType.get("kind"));

        // Element info if present
        Map<String, Object> element = (Map<String, Object>) data.get("element");
        if (element != null) {
            assertNotNull(element.get("name"));
            assertNotNull(element.get("kind"));
        }
    }

    @Test
    @DisplayName("Method declaration position returns enclosingType and file info")
    void methodDeclarationPosition_returnsContextInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertNotNull(data.get("enclosingType"));
        assertNotNull(data.get("filePath"));
    }

    @Test
    @DisplayName("Type level position returns enclosingType with no enclosingMethod")
    void typeLevelPosition_noEnclosingMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        Map<String, Object> enclosingType = getEnclosingType(data);
        assertNotNull(enclosingType);
        assertEquals("Calculator", enclosingType.get("name"));
        assertNull(data.get("enclosingMethod"));
    }

    @Test
    @DisplayName("Service class returns correct package")
    void serviceClass_returnsCorrectPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 20);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("com.example.service", data.get("enclosingPackage"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Inside Calculator.add body: enclosingMethod resolves to 'add'")
    @SuppressWarnings("unchecked")
    void insideAddBody_enclosingMethodIsAdd() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        // Calculator.add body line `lastResult = a + b;` is 1-based line 16 -> 0-based 15.
        args.put("line", 15);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        Map<String, Object> enclosingMethod = (Map<String, Object>) data.get("enclosingMethod");
        assertNotNull(enclosingMethod,
            "Position inside Calculator.add body must yield enclosingMethod; got data: " + data);
        assertEquals("add", enclosingMethod.get("name"),
            "Enclosing method must be 'add'; got: " + enclosingMethod);
        assertEquals(false, enclosingMethod.get("isConstructor"),
            "Calculator.add is not a constructor; got: " + enclosingMethod);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 15);
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
        args3.put("line", 15);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());

        // File not found
        ObjectNode args4 = objectMapper.createObjectNode();
        args4.put("filePath", "/nonexistent/path/File.java");
        args4.put("line", 0);
        args4.put("column", 0);
        assertFalse(tool.execute(args4).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    /**
     * Resolve a position to (line, column) by locating an identifier in the source file.
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
    @DisplayName("Position on nested class (TypeKindsFixture.Inner) reports outerType+enclosingType")
    @SuppressWarnings("unchecked")
    void nestedClass_reportsOuterTypeAndEnclosingType() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // Find the "Inner" identifier — it occurs in the comment, but the first
        // matching identifier in the class body line is what we want. The first
        // "Inner" is the nested class declaration.
        // Search past the docs to find the actual class declaration.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("class Inner");
        assertTrue(idx > 0);
        idx = source.indexOf("Inner", idx); // skip past "class " to "Inner"
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on Inner class declaration must succeed; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);

        Map<String, Object> enclosingType = getEnclosingType(data);
        assertEquals("Inner", enclosingType.get("name"),
            "enclosingType.name must be the inner class itself; got: " + enclosingType);

        Map<String, Object> outerType = (Map<String, Object>) data.get("outerType");
        assertNotNull(outerType,
            "Nested class must report outerType; got: " + data);
        assertEquals("TypeKindsFixture", outerType.get("name"),
            "outerType.name must be the enclosing class; got: " + outerType);
        assertEquals("com.example.TypeKindsFixture", outerType.get("qualifiedName"));
    }

    @Test
    @DisplayName("Inside method body, enclosingMethod carries signature, modifiers, isConstructor=false")
    @SuppressWarnings("unchecked")
    void enclosingMethod_carriesFullShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 15);   // inside add() body
        args.put("column", 10);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> enclosingMethod = (Map<String, Object>) getData(r).get("enclosingMethod");
        assertNotNull(enclosingMethod);

        assertEquals("add", enclosingMethod.get("name"));
        assertEquals(Boolean.FALSE, enclosingMethod.get("isConstructor"));
        assertNotNull(enclosingMethod.get("signature"),
            "enclosingMethod.signature must be present; got: " + enclosingMethod);
        assertEquals("add(int a, int b): int", enclosingMethod.get("signature"),
            "Signature includes simple types, parameter names, and return type; got: " + enclosingMethod);
        List<String> modifiers = (List<String>) enclosingMethod.get("modifiers");
        assertTrue(modifiers.contains("public"),
            "public modifier must appear; got: " + modifiers);
        assertNotNull(enclosingMethod.get("line"));
    }

    @Test
    @DisplayName("Inside HelloWorld constructor body: enclosingMethod has isConstructor=true")
    @SuppressWarnings("unchecked")
    void insideConstructor_isConstructorTrue() throws Exception {
        String helloPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
        // Position inside HelloWorld constructor body. Find `this.greeting = ` line.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(helloPath));
        int idx = source.indexOf("this.greeting");
        if (idx > 0) {
            int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
            int lineStart = source.lastIndexOf('\n', idx) + 1;
            int column = idx - lineStart;
            ObjectNode args = objectMapper.createObjectNode();
            args.put("filePath", helloPath);
            args.put("line", line);
            args.put("column", column);

            ToolResponse r = tool.execute(args);
            assertTrue(r.isSuccess());
            Map<String, Object> enclosingMethod = (Map<String, Object>) getData(r).get("enclosingMethod");
            assertNotNull(enclosingMethod);
            assertEquals("HelloWorld", enclosingMethod.get("name"));
            assertEquals(Boolean.TRUE, enclosingMethod.get("isConstructor"),
                "Inside a constructor, isConstructor must be true; got: " + enclosingMethod);
        }
    }

    @Test
    @DisplayName("Default package: enclosingPackage reports '(default package)'")
    void defaultPackage_reportsParenthesizedLabel() throws Exception {
        // Load default-package fixture and run the tool on its file.
        JdtServiceImpl svc = helper.loadProject("default-package");
        GetEnclosingElementTool localTool = new GetEnclosingElementTool(() -> svc);
        java.nio.file.Path dp = helper.getFixturePath("default-package");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", dp.resolve("src/main/java/NoPackage.java").toString());
        args.put("line", 5);   // inside the class body
        args.put("column", 4);

        ToolResponse r = localTool.execute(args);
        assertTrue(r.isSuccess(),
            "Position inside default-package class must succeed; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        assertEquals("(default package)", getData(r).get("enclosingPackage"),
            "Default package must be reported as '(default package)'; got: " + getData(r));
    }
}
