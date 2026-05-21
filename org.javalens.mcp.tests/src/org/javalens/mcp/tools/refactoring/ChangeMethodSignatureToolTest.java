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
        @SuppressWarnings("unchecked")
        Map<String, ?> editsByFile = (Map<String, ?>) data.get("editsByFile");
        assertNotNull(editsByFile, "editsByFile must be present");
        assertFalse(editsByFile.isEmpty(), "renaming a called method must touch at least one file");
        int totalEdits = ((Number) data.get("totalEdits")).intValue();
        int filesAffected = ((Number) data.get("filesAffected")).intValue();
        assertTrue(totalEdits > 1, "Declaration + call sites; totalEdits > 1; got: " + data);
        assertEquals(editsByFile.size(), filesAffected,
            "filesAffected must equal editsByFile.size(); got: " + data);
    }

    /**
     * Locate the declaration edit (isDeclaration=true) for the method being modified,
     * across all files in editsByFile.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findDeclarationEdit(Map<String, Object> data) {
        Map<String, List<Map<String, Object>>> byFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        for (List<Map<String, Object>> edits : byFile.values()) {
            for (Map<String, Object> edit : edits) {
                if (Boolean.TRUE.equals(edit.get("isDeclaration"))) {
                    return edit;
                }
            }
        }
        throw new AssertionError("No declaration edit found in editsByFile: " + byFile);
    }

    @Test
    @DisplayName("add parameter: declaration newSignature includes the new param; new param count is reported")
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

        assertEquals(2, ((Number) data.get("oldParameterCount")).intValue());
        assertEquals(3, ((Number) data.get("newParameterCount")).intValue(),
            "After adding `String prefix`, newParameterCount must be 3; got: " + data);

        // The declaration edit must carry a newSignature that reflects the added param.
        Map<String, Object> declEdit = findDeclarationEdit(data);
        String newSig = (String) declEdit.get("newSignature");
        assertEquals("String formatMessage(String message, int count, String prefix)", newSig,
            "Declaration newSignature must include all three params in order; got: " + newSig);
    }

    @Test
    @DisplayName("remove parameter: declaration newSignature drops `count`; newParameterCount=1")
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
        Map<String, Object> data = getData(response);

        assertEquals(2, ((Number) data.get("oldParameterCount")).intValue());
        assertEquals(1, ((Number) data.get("newParameterCount")).intValue(),
            "After removing `count`, newParameterCount must be 1; got: " + data);

        Map<String, Object> declEdit = findDeclarationEdit(data);
        String newSig = (String) declEdit.get("newSignature");
        assertEquals("String formatMessage(String message)", newSig,
            "Declaration newSignature must drop `count`; got: " + newSig);
    }

    @Test
    @DisplayName("reorder parameters: declaration newSignature has params in swapped order")
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
        Map<String, Object> data = getData(response);

        Map<String, Object> declEdit = findDeclarationEdit(data);
        String newSig = (String) declEdit.get("newSignature");
        assertEquals("String formatMessage(int count, String message)", newSig,
            "Declaration newSignature must reflect swapped order; got: " + newSig);
    }

    @Test
    @DisplayName("change return type: declaration newSignature uses new return type")
    void changesReturnType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newReturnType", "void");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("String", data.get("oldReturnType"),
            "Old return type must be reported; got: " + data);
        assertEquals("void", data.get("newReturnType"));

        Map<String, Object> declEdit = findDeclarationEdit(data);
        String newSig = (String) declEdit.get("newSignature");
        assertEquals("void formatMessage(String message, int count)", newSig,
            "Declaration newSignature must use new return type with original params; got: " + newSig);
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

    // ========== Semantic-grade tests (constructor call sites) ==========

    @Test
    @DisplayName("adding a parameter to ConstructorTarget(String, int) updates all 4 `new` call sites + this() delegation")
    void addParam_updatesConstructorCallSitesAcrossFiles() {
        String constructorTargetPath = projectPath
            .resolve("src/main/java/com/example/ConstructorTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", constructorTargetPath);
        // 2-arg constructor declared on 0-based line 11: `public ConstructorTarget(String name, int count) {`
        // Position on "ConstructorTarget" identifier (constructor name).
        args.put("line", 11);
        args.put("column", 11);

        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode p1 = objectMapper.createObjectNode();
        p1.put("name", "name");
        p1.put("type", "String");
        params.add(p1);
        ObjectNode p2 = objectMapper.createObjectNode();
        p2.put("name", "count");
        p2.put("type", "int");
        params.add(p2);
        ObjectNode p3 = objectMapper.createObjectNode();
        p3.put("name", "tag");
        p3.put("type", "String");
        p3.put("defaultValue", "\"\"");
        params.add(p3);
        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "Constructor signature change must succeed; got error: " +
                (response.getError() != null ? response.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        Map<String, List<?>> editsByFile =
            (Map<String, List<?>>) data.get("editsByFile");

        // ConstructorCaller.java has 4 `new ConstructorTarget(String, int)` call sites
        // (makeOne + 3 in makeMany). All must be present in editsByFile.
        boolean hasCaller = editsByFile.keySet().stream()
            .anyMatch(k -> k.replace('\\', '/').endsWith("ConstructorCaller.java"));
        assertTrue(hasCaller,
            "ConstructorCaller.java must appear in edits — its 4 `new ConstructorTarget(...)` " +
                "sites need updating; got files: " + editsByFile.keySet());

        List<?> callerEdits = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("ConstructorCaller.java"))
            .map(Map.Entry::getValue)
            .findFirst().orElseThrow();
        assertTrue(callerEdits.size() >= 4,
            "Expected at least 4 edits in ConstructorCaller.java (one per call site); got: "
                + callerEdits.size() + " edits: " + callerEdits);

        // Constructor newSignature must be `Name(params)` with no return-type prefix.
        // JDT returns `"V"` for IMethod.getReturnType() on constructors; without an
        // explicit skip, `Signature.toString` flows that through as `"void"` and the
        // emitted signature becomes `void ConstructorTarget(...)` — invalid Java.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declEdits = (List<Map<String, Object>>) editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("ConstructorTarget.java"))
            .map(Map.Entry::getValue)
            .findFirst().orElseThrow();
        Map<String, Object> declEdit = declEdits.stream()
            .filter(e -> Boolean.TRUE.equals(e.get("isDeclaration")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected an isDeclaration=true edit on ConstructorTarget.java; got: " + declEdits));
        String newSig = (String) declEdit.get("newSignature");
        assertNotNull(newSig);
        assertFalse(newSig.startsWith("void "),
            "Constructor signature must not start with `void`; got: " + newSig);
        assertEquals("ConstructorTarget(String name, int count, String tag)", newSig,
            "Constructor signature must be `Name(params)`; got: " + newSig);

        // Constructors have no return type — top-level oldReturnType / newReturnType
        // keys are meaningless and must be omitted (vs reported as the JDT-internal
        // "void" placeholder).
        assertFalse(data.containsKey("newReturnType"),
            "newReturnType must be absent on a constructor signature change; got: " + data);
        assertFalse(data.containsKey("oldReturnType"),
            "oldReturnType must be absent on a constructor signature change; got: " + data);
    }

    @Test
    @DisplayName("adding a parameter to ConstructorTarget(String, int) propagates to the this(name, 0) delegation site in the same file")
    void addParam_propagatesToThisConstructorDelegation() {
        // ConstructorTarget.java declares two constructors. The 1-arg constructor at
        // line 8 (0-based) delegates via `this(name, 0)` at line 9. When the 2-arg
        // constructor gets a third parameter, that this() site must be updated to
        // `this(name, 0, "");` or it breaks compilation. The this(...) form is a
        // `ConstructorInvocation` AST node — distinct from MethodInvocation and
        // ClassInstanceCreation. JDT models it as a Statement (not an Expression),
        // so the edit covers the full statement including the trailing semicolon.
        String constructorTargetPath = projectPath
            .resolve("src/main/java/com/example/ConstructorTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", constructorTargetPath);
        // 2-arg constructor at 0-based line 11.
        args.put("line", 11);
        args.put("column", 11);

        ArrayNode params = objectMapper.createArrayNode();
        ObjectNode p1 = objectMapper.createObjectNode();
        p1.put("name", "name");
        p1.put("type", "String");
        params.add(p1);
        ObjectNode p2 = objectMapper.createObjectNode();
        p2.put("name", "count");
        p2.put("type", "int");
        params.add(p2);
        ObjectNode p3 = objectMapper.createObjectNode();
        p3.put("name", "tag");
        p3.put("type", "String");
        p3.put("defaultValue", "\"\"");
        params.add(p3);
        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "Constructor signature change must succeed; got error: " +
                (response.getError() != null ? response.getError().getMessage() : "n/a"));

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) getData(response).get("editsByFile");

        List<Map<String, Object>> targetEdits = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("ConstructorTarget.java"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ConstructorTarget.java must appear in edits; got files: " + editsByFile.keySet()));

        // The declaration edit (isDeclaration=true) covers line 11 (the 2-arg ctor).
        // The this() delegation at line 8 (1-arg ctor body) must produce a separate
        // non-declaration edit that includes the new `""` argument.
        List<Map<String, Object>> nonDeclarationEdits = targetEdits.stream()
            .filter(e -> !Boolean.TRUE.equals(e.get("isDeclaration")))
            .toList();
        assertFalse(nonDeclarationEdits.isEmpty(),
            "Expected a non-declaration edit in ConstructorTarget.java for the this(name, 0) " +
                "delegation site; got only declaration edits: " + targetEdits);

        // The this() edit's newText must include the defaulted `""` value for the new param.
        Map<String, Object> thisEdit = nonDeclarationEdits.stream()
            .filter(e -> {
                String oldText = (String) e.get("oldText");
                return oldText != null && oldText.startsWith("this(");
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected an edit whose oldText starts with `this(` in ConstructorTarget.java; " +
                    "got non-declaration edits: " + nonDeclarationEdits));
        String newText = (String) thisEdit.get("newText");
        assertNotNull(newText, "this() edit newText must be present; got: " + thisEdit);
        assertEquals("this(name, 0, \"\");", newText,
            "this() delegation edit must include the defaulted new arg AND the trailing " +
                "semicolon (the edit replaces the full Statement); got: " + newText);
        // oldText reflects JDT's ASTNode.toString() canonical format (e.g., spaces inside
        // parens may differ from the original source). The load-bearing part of the edit
        // is the (startOffset, endOffset) range + newText; the consumer applies the edit
        // by offset, not by literal oldText match. So we only assert that the oldText
        // names the this() form, not its exact whitespace.
        String oldText = (String) thisEdit.get("oldText");
        assertNotNull(oldText);
        // JDT's ASTNode.toString() on a Statement appends a trailing newline and
        // strips inter-argument whitespace; trim before shape checks.
        String oldTrimmed = oldText.trim();
        assertTrue(oldTrimmed.startsWith("this("),
            "Old text must be the this() delegation form; got: " + oldText);
        assertTrue(oldTrimmed.endsWith(";"),
            "Old text covers the full Statement, must end with `;`; got: " + oldText);
        // Offsets must be non-negative and end > start.
        int startOffset = ((Number) thisEdit.get("startOffset")).intValue();
        int endOffset = ((Number) thisEdit.get("endOffset")).intValue();
        assertTrue(startOffset >= 0 && endOffset > startOffset,
            "Offsets must form a valid range; got start=" + startOffset + " end=" + endOffset);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Invalid Java identifier in newName is rejected")
    void rejectsInvalidNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "123invalid");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("Empty newParameters array removes all params: signature becomes `String formatMessage()`")
    void removesAllParameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.set("newParameters", objectMapper.createArrayNode());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("newParameterCount")).intValue());

        Map<String, Object> decl = findDeclarationEdit(data);
        assertEquals("String formatMessage()", decl.get("newSignature"),
            "Empty newParameters must drop all params; got: " + decl.get("newSignature"));
    }

    @Test
    @DisplayName("Declaration edit carries isDeclaration=true, oldSignature, newSignature, full position shape")
    void declarationEdit_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> decl = findDeclarationEdit(getData(r));
        for (String key : List.of("type", "isDeclaration", "oldSignature", "newSignature",
                "startLine", "startColumn", "endLine", "endColumn", "startOffset", "endOffset")) {
            assertNotNull(decl.get(key), key + " missing on declaration edit: " + decl);
        }
        assertEquals(Boolean.TRUE, decl.get("isDeclaration"));
        assertEquals("replace", decl.get("type"));
    }

    @Test
    @DisplayName("Only one declaration edit exists across all files")
    void onlyOneDeclarationEdit() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> byFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        long declCount = byFile.values().stream()
            .flatMap(List::stream)
            .filter(e -> Boolean.TRUE.equals(e.get("isDeclaration")))
            .count();
        assertEquals(1L, declCount,
            "Exactly one edit must carry isDeclaration=true; got: " + declCount);
    }

    @Test
    @DisplayName("totalEdits equals the sum of per-file edit list sizes")
    void totalEdits_sumsAcrossFiles() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalEdits")).intValue();
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> byFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        int sum = byFile.values().stream().mapToInt(List::size).sum();
        assertEquals(total, sum);
    }
}
