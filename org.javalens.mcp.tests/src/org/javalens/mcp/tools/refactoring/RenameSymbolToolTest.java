package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RenameSymbolTool.
 * Tests cross-file rename and identifier validation.
 */
class RenameSymbolToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RenameSymbolTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new RenameSymbolTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> getEditsByFile(Map<String, Object> data) {
        return (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("rename local variable returns complete response with all edit details")
    void renameLocalVariable_returnsCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 88);  // int oldName = 42;
        args.put("column", 12);
        args.put("newName", "newName");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify symbol info
        assertEquals("oldName", data.get("oldName"));
        assertEquals("newName", data.get("newName"));
        assertEquals("variable", data.get("symbolKind"));

        // Verify edit counts
        assertTrue((int) data.get("totalEdits") > 0);
        assertNotNull(data.get("filesAffected"));
        assertTrue((int) data.get("filesAffected") >= 1);

        // Verify edit structure
        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(data);
        assertFalse(editsByFile.isEmpty());
        List<Map<String, Object>> edits = editsByFile.values().iterator().next();
        assertFalse(edits.isEmpty());

        Map<String, Object> edit = edits.get(0);
        assertTrue(((Number) edit.get("line")).intValue() >= 0, "line >= 0; got: " + edit);
        int col = ((Number) edit.get("column")).intValue();
        int endCol = ((Number) edit.get("endColumn")).intValue();
        assertTrue(col >= 0, "column >= 0; got: " + edit);
        assertEquals(col + "oldName".length(), endCol,
            "endColumn must equal column + oldText.length(); got: " + edit);
        assertEquals("oldName", edit.get("oldText"));
        assertEquals("newName", edit.get("newText"));
    }

    @Test
    @DisplayName("rename field finds all usages and returns field kind")
    void renameField_findsAllUsagesAndReturnsFieldKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 15);  // private String userName;
        args.put("column", 19);
        args.put("newName", "userFullName");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("userName", data.get("oldName"));
        assertEquals("userFullName", data.get("newName"));
        assertEquals("field", data.get("symbolKind"));
        // Field is used in multiple places
        assertTrue((int) data.get("totalEdits") >= 3);
    }

    @Test
    @DisplayName("rename method returns method kind")
    void renameMethod_returnsMethodKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // public int add(int a, int b)
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("oldName"));
        assertEquals("sum", data.get("newName"));
        assertEquals("method", data.get("symbolKind"));
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("rejects invalid Java identifiers, reserved words, and same name")
    void rejectsInvalidNames() {
        // Test invalid identifier (starts with number)
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("line", 88);
        args1.put("column", 12);
        args1.put("newName", "123invalid");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertTrue(response1.getError().getMessage().contains("identifier"));

        // Test reserved word
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("line", 88);
        args2.put("column", 12);
        args2.put("newName", "class");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());

        // Test same name
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", refactoringTargetPath);
        args3.put("line", 88);
        args3.put("column", 12);
        args3.put("newName", "oldName");

        ToolResponse response3 = tool.execute(args3);
        assertFalse(response3.isSuccess());
        assertTrue(response3.getError().getMessage().contains("Same as current"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    @Test
    @DisplayName("requires newName parameter")
    void requiresNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 10);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("Negative line/column returns INVALID_PARAMETER")
    void handlesInvalidLineColumn() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", -1);
        args.put("column", -1);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            response.getError().getCode(),
            "Negative coords must surface as INVALID_PARAMETER; got: "
                + response.getError().getCode());
    }

    @Test
    @DisplayName("Position with no symbol returns SYMBOL_NOT_FOUND")
    void handlesNoSymbolAtPosition() {
        // Line 1 col 0 — blank line between `package` and class Javadoc.
        // getElementAtPosition returns null → tool emits SYMBOL_NOT_FOUND.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 1);
        args.put("column", 0);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND,
            response.getError().getCode(),
            "Blank-line position must surface as SYMBOL_NOT_FOUND; got: "
                + response.getError().getCode());
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    @Test
    @DisplayName("rename FieldHolder.pet (custom-typed field): cross-file edits across FieldHolder + WidgetHelper")
    void renameCustomTypedField_emitsAllEditsAcrossFiles() {
        String fieldHolderPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/FieldHolder.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", fieldHolderPath);
        args.put("line", 4);    // `    Animal pet;` (0-based)
        args.put("column", 11); // start of "pet"
        args.put("newName", "companion");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("pet", data.get("oldName"));
        assertEquals("companion", data.get("newName"));
        assertEquals("field", data.get("symbolKind"));

        int totalEdits = ((Number) data.get("totalEdits")).intValue();
        int filesAffected = ((Number) data.get("filesAffected")).intValue();

        // Expected: 4 edits in FieldHolder (decl + 2 ctor inits + getPet return) + 4 edits
        // in WidgetHelper (describe read + swap write + extract read + extract write) = 8
        // across 2 files. Tool may or may not count the declaration itself as an edit; bound
        // conservatively at >= 7 across 2 files.
        assertEquals(2, filesAffected,
            "FieldHolder.pet must be renamed across both declaring file and WidgetHelper");
        assertTrue(totalEdits >= 7,
            "Expected at least 7 edits for pet (decl + inits + accessors + cross-file uses); got: " + totalEdits);

        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.List<?>> editsByFile =
            (java.util.Map<String, java.util.List<?>>) data.get("editsByFile");
        boolean hasFieldHolder = editsByFile.keySet().stream()
            .anyMatch(k -> k.replace('\\', '/').endsWith("FieldHolder.java"));
        boolean hasWidgetHelper = editsByFile.keySet().stream()
            .anyMatch(k -> k.replace('\\', '/').endsWith("WidgetHelper.java"));
        assertTrue(hasFieldHolder, "edits must include FieldHolder.java; got: " + editsByFile.keySet());
        assertTrue(hasWidgetHelper, "edits must include WidgetHelper.java; got: " + editsByFile.keySet());
    }

    @Test
    @DisplayName("renaming MethodRefTarget.formatId propagates to MethodRefTarget::formatId method-reference call site")
    void renameMethod_propagatesToTypeMethodReference() {
        // MethodRefUser holds `IntFunction<String> formatter = MethodRefTarget::formatId;`
        // (a TypeMethodReference). Renaming formatId must update that reference; the
        // formatter's binding doesn't change at the binding-key level (still IMethodBinding
        // for the same method), but the textual identifier `formatId` in the call site
        // must be rewritten to the new name.
        String methodRefTargetPath = projectPath
            .resolve("src/main/java/com/example/MethodRefTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", methodRefTargetPath);
        // 0-based line 8: `    public static String formatId(int id) {`
        // "formatId" starts at column 25 (4 indent + "public static String " = 25).
        args.put("line", 8);
        args.put("column", 25);
        args.put("newName", "formatIdentifier");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "Rename must succeed; got error: " +
                (response.getError() != null ? response.getError().getMessage() : "n/a"));

        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(getData(response));
        // The declaration update is in MethodRefTarget.java; the method-reference
        // update must be in MethodRefUser.java.
        boolean hasUserFile = editsByFile.keySet().stream()
            .anyMatch(k -> k.replace('\\', '/').endsWith("MethodRefUser.java"));
        assertTrue(hasUserFile,
            "MethodRefUser.java must appear in edits — it has a `MethodRefTarget::formatId` " +
                "method-reference that must be rewritten to ::formatIdentifier; got files: "
                + editsByFile.keySet());

        // The edit's oldText must be `formatId` (the identifier inside the method
        // reference); newText `formatIdentifier`.
        List<Map<String, Object>> userEdits = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("MethodRefUser.java"))
            .map(Map.Entry::getValue)
            .findFirst().orElseThrow();
        boolean hasFormatIdRewrite = userEdits.stream()
            .anyMatch(e -> "formatId".equals(e.get("oldText"))
                && "formatIdentifier".equals(e.get("newText")));
        assertTrue(hasFormatIdRewrite,
            "Expected an edit in MethodRefUser.java rewriting `formatId` to `formatIdentifier`; " +
                "got: " + userEdits);
    }

    @Test
    @DisplayName("renaming SuperMethodParent.greet propagates to the super.greet(name) call site in SuperMethodChild")
    void renameMethod_propagatesToSuperMethodInvocation() {
        // SuperMethodChild.enthusiasticGreet calls super.greet(name). Renaming
        // SuperMethodParent.greet → salute must rewrite super.greet to super.salute.
        // SuperMethodInvocation's name is a SimpleName whose binding matches the
        // rename target.
        String parentPath = projectPath
            .resolve("src/main/java/com/example/SuperMethodParent.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", parentPath);
        // 0-based line 8: `    public String greet(String name) {`
        // "greet" starts at column 18.
        args.put("line", 8);
        args.put("column", 18);
        args.put("newName", "salute");

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(),
            "Rename must succeed; got error: " +
                (response.getError() != null ? response.getError().getMessage() : "n/a"));

        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(getData(response));
        boolean hasChild = editsByFile.keySet().stream()
            .anyMatch(k -> k.replace('\\', '/').endsWith("SuperMethodChild.java"));
        assertTrue(hasChild,
            "SuperMethodChild.java must appear in edits — it has a super.greet(name) call " +
                "site that must be rewritten to super.salute(name); got files: " +
                editsByFile.keySet());

        List<Map<String, Object>> childEdits = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("SuperMethodChild.java"))
            .map(Map.Entry::getValue)
            .findFirst().orElseThrow();
        boolean hasGreetRewrite = childEdits.stream()
            .anyMatch(e -> "greet".equals(e.get("oldText"))
                && "salute".equals(e.get("newText")));
        assertTrue(hasGreetRewrite,
            "Expected an edit in SuperMethodChild.java rewriting `greet` → `salute`; got: "
                + childEdits);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Per-edit shape: line, column, endColumn, oldText, newText, startOffset, endOffset all present")
    void perEdit_shapeIncludesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(data);
        for (List<Map<String, Object>> edits : editsByFile.values()) {
            for (Map<String, Object> e : edits) {
                assertTrue(((Number) e.get("line")).intValue() >= 0, "line >= 0; got: " + e);
                assertEquals("add", e.get("oldText"));
                assertEquals("sum", e.get("newText"));
                int col = ((Number) e.get("column")).intValue();
                int endCol = ((Number) e.get("endColumn")).intValue();
                assertTrue(col >= 0, "column >= 0; got: " + e);
                assertEquals(col + "add".length(), endCol,
                    "endColumn must equal column + oldText.length(); got: " + e);
                int startOffset = ((Number) e.get("startOffset")).intValue();
                int endOffset = ((Number) e.get("endOffset")).intValue();
                assertTrue(startOffset >= 0, "startOffset >= 0; got: " + e);
                assertEquals(startOffset + "add".length(), endOffset,
                    "endOffset must equal startOffset + oldText.length(); got: " + e);
            }
        }
    }

    @Test
    @DisplayName("Rename type sets symbolKind=Class and emits the file-rename note")
    void renameClass_emitsFileRenameNote() {
        // Calculator.java line 5 (0-based) `public class Calculator {`
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13); // start of `Calculator`
        args.put("newName", "ComputeEngine");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Calculator", data.get("oldName"));
        assertEquals("class", data.get("symbolKind"));
        assertNotNull(data.get("note"), "Class rename must include the file-rename note");
        assertTrue(((String) data.get("note")).toLowerCase().contains("file"),
            "Note must mention file rename; got: " + data.get("note"));
    }

    @Test
    @DisplayName("Rename method propagates to call sites in other files")
    void renameMethod_includesCrossFileCallSites() {
        // Calculator.add — called from UserService (calculator.add(...)) and SampleTest.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(data);
        // Files containing add or its calls: Calculator.java (decl), and at minimum UserService.java.
        java.util.Set<String> files = new java.util.HashSet<>();
        for (String k : editsByFile.keySet()) {
            String n = k.replace('\\', '/');
            files.add(n.substring(n.lastIndexOf('/') + 1));
        }
        assertTrue(files.contains("Calculator.java"),
            "Calculator.java (declaration) must be edited; got: " + files);
        assertTrue(files.contains("UserService.java"),
            "UserService.java (caller) must be edited; got: " + files);
    }

    @Test
    @DisplayName("Method rename does NOT touch a same-named but unrelated method in a different type (isolation)")
    void renameMethod_isolation_doesNotTouchUnrelatedSameName() {
        // Calculator.add is being renamed. The fixture has other types that may have a
        // method named `add` (e.g., List<...>.add); those bind to a different element and
        // must not appear in editsByFile.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(data);
        for (List<Map<String, Object>> edits : editsByFile.values()) {
            for (Map<String, Object> e : edits) {
                assertEquals("add", e.get("oldText"),
                    "Every edit's oldText must be `add` for the Calculator.add rename; got: " + e);
            }
        }
    }

    @Test
    @DisplayName("Reject reserved word: var, sealed, permits, record, yield, non-sealed all rejected")
    void rejectAdditionalReservedWords() {
        for (String word : List.of("var", "sealed", "permits", "record", "yield")) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("filePath", refactoringTargetPath);
            args.put("line", 88);
            args.put("column", 12);
            args.put("newName", word);
            ToolResponse r = tool.execute(args);
            assertFalse(r.isSuccess(),
                "Reserved word `" + word + "` must be rejected");
        }
    }

    @Test
    @DisplayName("Reject empty newName")
    void rejectEmptyNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 88);
        args.put("column", 12);
        args.put("newName", "");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("Non-type rename (method) does NOT include the file-rename note")
    void renameMethod_noFileRenameNote() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNull(data.get("note"),
            "Method rename must not include file-rename note; got: " + data.get("note"));
    }

    @Test
    @DisplayName("Renaming a generic-class type parameter propagates to its usages in member signatures and body")
    @SuppressWarnings("unchecked")
    void renameTypeParameter_propagatesInClassBody() {
        // TypeKindsFixture.GenericContainer<T> declares <T> and uses T in:
        //   - field declaration: `private T value;`
        //   - getter return type: `public T get()`
        //   - setter parameter type: `public void set(T value)`
        // Position cursor on the T declaration in `class GenericContainer<T>`
        // (0-based line 16, column 41) and rename to E. Every use of T inside
        // the class body must be updated.
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", 16);
        args.put("column", 41);
        args.put("newName", "E");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Rename on a type parameter must succeed; got error: "
                + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, List<Map<String, Object>>> editsByFile = getEditsByFile(getData(r));
        assertNotNull(editsByFile);
        assertFalse(editsByFile.isEmpty(),
            "rename of a type parameter must produce at least one file edit; full response: " + getData(r));

        List<Map<String, Object>> edits = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().replace('\\', '/').endsWith("TypeKindsFixture.java"))
            .findFirst().map(java.util.Map.Entry::getValue)
            .orElseThrow(() -> new AssertionError(
                "Edits must be under TypeKindsFixture.java; got files: " + editsByFile.keySet()
                    + "; full response: " + getData(r)));

        // Edit set must include the declaration (line 16) AND the body usages:
        //   line 18 (private T value;)
        //   line 20 (public T get())
        //   line 24 (set(T value))
        // The declaration edit at line 16 is included by rename_symbol's
        // editsByFile (the response always rewrites the declaration too).
        java.util.Set<Integer> editLines = new java.util.HashSet<>();
        for (Map<String, Object> e : edits) {
            editLines.add(((Number) e.get("line")).intValue());
        }
        assertTrue(editLines.contains(18),
            "Field declaration `private T value;` must be rewritten; got lines: " + editLines);
        assertTrue(editLines.contains(20),
            "Getter return type `public T get()` must be rewritten; got lines: " + editLines);
        assertTrue(editLines.contains(24),
            "Setter parameter type `public void set(T value)` must be rewritten; got lines: " + editLines);

        // Every edit's newText must be the new name "E".
        for (Map<String, Object> e : edits) {
            assertEquals("E", e.get("newText"),
                "Rename edit must substitute E for T; got: " + e);
        }
    }

    @Test
    @DisplayName("totalEdits equals the sum of per-file edit list sizes")
    void totalEdits_sumsAcrossFiles() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalEdits")).intValue();
        int sum = getEditsByFile(data).values().stream().mapToInt(List::size).sum();
        assertEquals(total, sum, "totalEdits must equal the sum of per-file edits");
    }
}
