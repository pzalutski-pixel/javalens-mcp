package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChangeMethodSignatureTool.
 * Tests method signature changes and call site updates.
 */
class ChangeMethodSignatureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ChangeMethodSignatureTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("renames method and updates call sites with complete response")
    void renamesMethodWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);  // formatMessage method
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify rename info
        assertEquals("formatOutput", data.get("newName"));

        // Verify edit structure
        assertNotNull(data.get("editsByFile"));
        assertNotNull(data.get("totalEdits"));
        assertNotNull(data.get("filesAffected"));
        assertTrue((int) data.get("totalEdits") > 1);  // Declaration + call sites
    }

    @Test
    @DisplayName("adds new parameter with default value")
    void addsParameterWithDefaultValue() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "message");
        param1.put("type", "String");
        params.add(param1);

        ObjectNode param2 = objectMapper.createObjectNode();
        param2.put("name", "count");
        param2.put("type", "int");
        params.add(param2);

        ObjectNode param3 = objectMapper.createObjectNode();
        param3.put("name", "prefix");
        param3.put("type", "String");
        param3.put("defaultValue", "\"\"");
        params.add(param3);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("editsByFile"));
    }

    @Test
    @DisplayName("removes parameter from method signature")
    void removesParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        // Keep only first parameter (message)
        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "message");
        param1.put("type", "String");
        params.add(param1);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("reorders parameters in method signature")
    void reordersParameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);

        // Swap parameter order
        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode param1 = objectMapper.createObjectNode();
        param1.put("name", "count");
        param1.put("type", "int");
        params.add(param1);

        ObjectNode param2 = objectMapper.createObjectNode();
        param2.put("name", "message");
        param2.put("type", "String");
        params.add(param2);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }

    @Test
    @DisplayName("changes method return type")
    void changesReturnType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newReturnType", "void");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("void", data.get("newReturnType"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires at least one change to be specified")
    void requiresAtLeastOneChange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        // No newName, newReturnType, or newParameters

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", -1);
        args.put("column", -1);
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles non-method position gracefully")
    void handlesNotAMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 14);  // Field declaration
        args.put("column", 19);
        args.put("newName", "newFieldName");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Constructor Call-Site Propagation (BUG-3 regression) ==========

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("adds parameter to constructor and propagates to new-expression call sites")
    void addsParameterToConstructorWithCallSitePropagation() {
        // ConstructorTarget(String value) is at line 8 (0-based), column 11
        // ConstructorCallers has two `new ConstructorTarget(...)` call sites in a separate file
        String constructorTargetPath = projectPath
            .resolve("src/main/java/com/example/ConstructorTarget.java").toString();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", constructorTargetPath);
        args.put("line", 8);
        args.put("column", 11);

        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode existing = objectMapper.createObjectNode();
        existing.put("name", "value");
        existing.put("type", "String");
        params.add(existing);

        ObjectNode newParam = objectMapper.createObjectNode();
        newParam.put("name", "extra");
        newParam.put("type", "String");
        newParam.put("defaultValue", "null");
        params.add(newParam);

        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), "Expected success but got: " + response.getError());
        Map<String, Object> data = getData(response);

        // Declaration + both call sites in ConstructorCallers.java
        int totalEdits = (int) data.get("totalEdits");
        assertTrue(totalEdits >= 3,
            "Expected declaration + 2 call sites, got totalEdits=" + totalEdits);

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");

        // Cross-file propagation: ConstructorCallers.java must appear in edits
        assertTrue(editsByFile.keySet().stream().anyMatch(k -> k.endsWith("ConstructorCallers.java")),
            "Expected edits in ConstructorCallers.java, but only found: " + editsByFile.keySet());

        // Declaration edit must NOT start with "void " (constructor signature bug)
        String declarationKey = editsByFile.keySet().stream()
            .filter(k -> k.endsWith("ConstructorTarget.java"))
            .findFirst().orElse(null);
        assertNotNull(declarationKey, "ConstructorTarget.java not in edits");
        Map<String, Object> declEdit = editsByFile.get(declarationKey).stream()
            .filter(e -> Boolean.TRUE.equals(e.get("isDeclaration")))
            .findFirst().orElse(null);
        assertNotNull(declEdit, "No declaration edit found in ConstructorTarget.java");
        String newSignature = (String) declEdit.get("newSignature");
        assertFalse(newSignature.startsWith("void "),
            "Constructor newSignature must not start with 'void': " + newSignature);

        // Call-site edits must include the default value "null" for the new parameter
        String callersKey = editsByFile.keySet().stream()
            .filter(k -> k.endsWith("ConstructorCallers.java"))
            .findFirst().orElse(null);
        List<Map<String, Object>> callerEdits = editsByFile.get(callersKey);
        assertTrue(callerEdits.stream().anyMatch(e -> {
            String newText = (String) e.get("newText");
            return newText != null && newText.contains("null");
        }), "Expected caller edits to contain 'null' default value, got: " + callerEdits);
    }
}
