package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetMethodAtPositionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetMethodAtPositionTool.
 * Tests method signature extraction.
 */
class GetMethodAtPositionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetMethodAtPositionTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String helloWorldPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetMethodAtPositionTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        helloWorldPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Method declaration returns name, returnType, parameters, parameterCount, modifiers, signature, declaringType, filePath, and line")
    @SuppressWarnings("unchecked")
    void methodDeclaration_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Basic info
        assertEquals("add", data.get("name"));
        assertEquals(false, data.get("isConstructor"));
        assertEquals("int", data.get("returnType"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        assertEquals(2, data.get("parameterCount"));

        // Parameters
        List<Map<String, String>> params = (List<Map<String, String>>) data.get("parameters");
        assertNotNull(params);
        assertEquals(2, params.size());
        assertEquals("int", params.get(0).get("type"));
        assertEquals("a", params.get(0).get("name"));
        assertEquals("int", params.get(1).get("type"));
        assertEquals("b", params.get(1).get("name"));

        // Modifiers
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        // Signature
        assertNotNull(data.get("signature"));
        String sig = data.get("signature").toString();
        assertTrue(sig.contains("add"));
        assertTrue(sig.contains("int"));

        // Location
        String filePath = (String) data.get("filePath");
        assertNotNull(filePath, "filePath must be present");
        assertTrue(filePath.endsWith("Calculator.java"),
            "Calculator.add is in Calculator.java; got: " + filePath);
        assertTrue(((Number) data.get("line")).intValue() >= 0,
            "line must be >= 0; got: " + data);
    }

    @Test
    @DisplayName("Constructor returns isConstructor=true, name, and no returnType")
    void constructor_returnsCorrectFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 11);
        args.put("column", 11);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(true, data.get("isConstructor"));
        assertEquals("HelloWorld", data.get("name"));
        assertNull(data.get("returnType"));
    }

    @Test
    @DisplayName("Main method is identified with isMainMethod flag")
    void mainMethod_identifiedCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helloWorldPath);
        args.put("line", 50);
        args.put("column", 23);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("main", data.get("name"));
        assertEquals(true, data.get("isMainMethod"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing filePath, negative line, and negative column each return INVALID_PARAMETER")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 14);
        args1.put("column", 15);
        ToolResponse r1 = tool.execute(args1);
        assertFalse(r1.isSuccess());
        assertNotNull(r1.getError());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r1.getError().getCode());
        assertTrue(r1.getError().getMessage().contains("filePath"),
            "Error message must name the missing parameter; got: " + r1.getError().getMessage());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 15);
        ToolResponse r2 = tool.execute(args2);
        assertFalse(r2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r2.getError().getCode());
        assertTrue(r2.getError().getMessage().contains("line"),
            "Error message must name the failing parameter (line); got: " + r2.getError().getMessage());

        // Negative column — independent validation branch from `line`.
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 14);
        args3.put("column", -1);
        ToolResponse r3 = tool.execute(args3);
        assertFalse(r3.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r3.getError().getCode());
        assertTrue(r3.getError().getMessage().contains("column"),
            "Error message must name the failing parameter (column); got: " + r3.getError().getMessage());

        // Blank filePath ("") — separate guard branch from null filePath.
        ObjectNode args4 = objectMapper.createObjectNode();
        args4.put("filePath", "");
        args4.put("line", 14);
        args4.put("column", 15);
        ToolResponse r4 = tool.execute(args4);
        assertFalse(r4.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r4.getError().getCode());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position on field returns error (not a method)")
    void positionOnField_returnsError() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAt(String fp, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fp);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Static method (TypeKindsFixture.staticHelper) reports modifier 'static'")
    @SuppressWarnings("unchecked")
    void staticMethod_reportsStaticModifier() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 39 `public static String staticHelper(...)` -> 0-based 38;
        // "staticHelper" starts at column 25.
        ToolResponse r = tool.execute(argsAt(tkf, 38, 25));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("staticHelper", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("static"),
            "static modifier must appear; got: " + modifiers);
        assertTrue(modifiers.contains("public"),
            "public modifier must appear; got: " + modifiers);
    }

    @Test
    @DisplayName("Synchronized method reports modifier 'synchronized'")
    @SuppressWarnings("unchecked")
    void synchronizedMethod_reportsSynchronizedModifier() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 44 `public synchronized String synchronizedHelper(...)` -> 0-based 43;
        // "synchronizedHelper" starts at column 31.
        ToolResponse r = tool.execute(argsAt(tkf, 43, 31));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("synchronizedHelper", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("synchronized"),
            "synchronized modifier must appear; got: " + modifiers);
    }

    @Test
    @DisplayName("Generic method (convert<U>) reports typeParameters=[U]")
    @SuppressWarnings("unchecked")
    void genericMethod_reportsTypeParameters() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 49 `public <U> U convert(U value)` -> 0-based 48;
        // "convert" starts at column 17.
        ToolResponse r = tool.execute(argsAt(tkf, 48, 17));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("convert", data.get("name"));
        List<String> typeParams = (List<String>) data.get("typeParameters");
        assertNotNull(typeParams,
            "convert<U> must have typeParameters reported; got: " + data);
        assertEquals(List.of("U"), typeParams);
    }

    @Test
    @DisplayName("Method declaring throws clauses reports exceptions list")
    @SuppressWarnings("unchecked")
    void methodWithThrows_reportsExceptionsList() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 54 `public String throwingHelper(String path) throws java.io.IOException` ->
        // 0-based 53; "throwingHelper" starts at column 18.
        ToolResponse r = tool.execute(argsAt(tkf, 53, 18));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("throwingHelper", data.get("name"));
        List<String> exceptions = (List<String>) data.get("exceptions");
        assertNotNull(exceptions);
        assertEquals(List.of("IOException"), exceptions,
            "throws java.io.IOException should be reported (simple name); got: " + exceptions);
    }

    @Test
    @DisplayName("Interface default method reports modifier 'default'")
    @SuppressWarnings("unchecked")
    void defaultInterfaceMethod_reportsDefaultModifier() {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        // 1-based line 63 `default String greet() {` in DefaultMethodHolder -> 0-based 62;
        // "greet" starts at column 23.
        ToolResponse r = tool.execute(argsAt(tkf, 62, 23));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("greet", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("default"),
            "default modifier must appear for interface default method; got: " + modifiers);
    }

    @Test
    @DisplayName("Private method (UnusedCode.unusedPrivateMethod) reports modifier 'private'")
    @SuppressWarnings("unchecked")
    void privateMethod_reportsPrivateModifier() throws Exception {
        // UnusedCode.unusedPrivateMethod — find its line. The fixture is a known unused
        // member; the file has the declaration.
        String unusedPath = projectPath.resolve("src/main/java/com/example/UnusedCode.java").toString();
        // Tool walks element ancestor chain to find IMethod, so as long as we land on the
        // method's name range, the test passes regardless of exact column. We position on
        // the first occurrence of "unusedPrivateMethod" line.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(unusedPath));
        int idx = source.indexOf("unusedPrivateMethod");
        assertTrue(idx > 0, "Fixture UnusedCode.java must declare unusedPrivateMethod");
        int lineNum = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count(); // 0-based
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int col = idx - lineStart;

        ToolResponse r = tool.execute(argsAt(unusedPath, lineNum, col));
        assertTrue(r.isSuccess(),
            "Position on unusedPrivateMethod must resolve; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("unusedPrivateMethod", data.get("name"));
        List<String> modifiers = (List<String>) data.get("modifiers");
        assertTrue(modifiers.contains("private"),
            "private modifier must appear; got: " + modifiers);
    }

    @Test
    @DisplayName("Method signature field is exact: name(type name, ...): returnType")
    void signature_formattedExactly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add(int a, int b): int", data.get("signature"),
            "Signature must format as name(type name, ...): returnType; got: " + data.get("signature"));
    }

    @Test
    @DisplayName("No-arg method (Calculator.getLastResult) reports parameterCount=0 and empty parameters list")
    @SuppressWarnings("unchecked")
    void noArgMethod_emptyParameters() {
        // Calculator.getLastResult — 1-based line 46 `public int getLastResult() {` -> 0-based 45;
        // "getLastResult" starts at column 15 (4-space indent + "public int " = 15 chars).
        // Pins the paramTypes.length == 0 branch in createMethodInfo: parameters list is
        // empty (not absent, not null), parameterCount is 0 (not absent).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 45);
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Position on Calculator.getLastResult must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "ok"));
        Map<String, Object> data = getData(r);
        assertEquals("getLastResult", data.get("name"));
        assertEquals(0, data.get("parameterCount"),
            "No-arg method must report parameterCount=0; got: " + data.get("parameterCount"));
        List<Map<String, String>> params = (List<Map<String, String>>) data.get("parameters");
        assertNotNull(params, "parameters key must be present even when empty; got: " + data);
        assertTrue(params.isEmpty(),
            "No-arg method must report empty parameters list; got: " + params);
        // Optional shape keys must be ABSENT (not present-with-null) for a no-arg, no-throws,
        // non-generic method — pins the `if (length > 0)` guards in createMethodInfo.
        assertFalse(data.containsKey("typeParameters"),
            "typeParameters must be absent for a non-generic method; got: " + data);
        assertFalse(data.containsKey("exceptions"),
            "exceptions must be absent for a method declaring no throws; got: " + data);
        // Return type IS present (non-constructor branch).
        assertEquals("int", data.get("returnType"));
        // isMainMethod is FALSE for getLastResult — pins the false-arm of the isMainMethod flag.
        assertEquals(false, data.get("isMainMethod"),
            "Non-main method must report isMainMethod=false; got: " + data.get("isMainMethod"));
        // Signature with zero params is "name(): returnType".
        assertEquals("getLastResult(): int", data.get("signature"),
            "No-arg signature must be `name(): returnType`; got: " + data.get("signature"));
    }

    @Test
    @DisplayName("Position at a method call site (not declaration) resolves to the called method")
    void methodCallSite_resolvesToCalledMethod() {
        // SearchPatterns.createObjects calls Calculator.add(...). Position on the .add call.
        String searchPath = projectPath.resolve("src/main/java/com/example/SearchPatterns.java").toString();
        // SearchPatterns.java line 59 (1-based): `int result = calc.add(1, 2);` -> 0-based 58.
        // "add" is after "calc.". Find by string search.
        String content;
        try {
            content = java.nio.file.Files.readString(java.nio.file.Path.of(searchPath));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int idx = content.indexOf("calc.add(1, 2)");
        assertTrue(idx > 0, "SearchPatterns must contain `calc.add(1, 2)` call site");
        int dot = content.indexOf(".add", idx);
        int lineNum = (int) content.substring(0, dot + 1).chars().filter(c -> c == '\n').count();
        int lineStart = content.lastIndexOf('\n', dot) + 1;
        int col = (dot + 1) - lineStart; // position of 'a' of "add"

        ToolResponse r = tool.execute(argsAt(searchPath, lineNum, col));
        assertTrue(r.isSuccess(),
            "Position at the call site must resolve to the invoked method; got error: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals("add", data.get("name"),
            "Resolved method must be `add`; got: " + data);
        assertEquals("com.example.Calculator", data.get("declaringType"),
            "Resolved method's declaringType must be Calculator; got: " + data);
    }
}
