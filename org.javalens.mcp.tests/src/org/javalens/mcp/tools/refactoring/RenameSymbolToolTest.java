package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for RenameSymbolTool on simple-maven: cross-file rename and identifier
 * validation. Every expectation is the whole payload, through execute() and through the JSON-RPC
 * envelope. Each file's edits come last position first; an edit replaces the old name where it
 * is written. Raw offsets depend on line endings, so each edit's startOffset and endOffset are
 * replaced by the span between them; line and column pin the edit.
 */
class RenameSymbolToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RenameSymbolTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new RenameSymbolTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("rename local variable returns complete response with all edit details")
    void renameLocalVariable_returnsCompleteResponse() {
        // localVariableRename declares oldName on line 88 and reads it on lines 89 and 90.
        assertPayload(at(refactoringTargetPath, 88, 12, "newName"), rename("oldName", "newName", "variable",
            Map.of(REFACTORING_TARGET, List.of(edit(90, 22), edit(89, 27), edit(88, 12))), null));
    }

    @Test
    @DisplayName("rename field finds all usages and returns field kind")
    void renameField_findsAllUsagesAndReturnsFieldKind() {
        // userName is declared on line 15 and used on lines 98, 100, 107 and 114 (this.userName;
        // setUserName's parameter of the same name is not the field).
        assertPayload(at(refactoringTargetPath, 15, 19, "userFullName"), rename("userName", "userFullName", "field",
            Map.of(REFACTORING_TARGET, List.of(edit(114, 13), edit(107, 15), edit(100, 27), edit(98, 8),
                edit(15, 19))), null));
    }

    @Test
    @DisplayName("rename method returns method kind")
    void renameMethod_returnsMethodKind() {
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("rejects invalid Java identifiers, reserved words, and same name")
    void rejectsInvalidNames() {
        assertAll(
            () -> assertPayload(at(refactoringTargetPath, 88, 12, "123invalid"),
                refusal("Invalid parameter 'newName': Not a valid Java identifier")),
            () -> assertPayload(at(refactoringTargetPath, 88, 12, "class"),
                refusal("Invalid parameter 'newName': Not a valid Java identifier")),
            () -> assertPayload(at(refactoringTargetPath, 88, 12, "oldName"),
                refusal("Invalid parameter 'newName': Same as current name")));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        args.put("newName", "test");
        assertPayload(args, refusal("Invalid parameter 'filePath': Required"));
    }

    @Test
    @DisplayName("requires newName parameter")
    void requiresNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 10);
        args.put("column", 5);
        assertPayload(args, refusal("Invalid parameter 'newName': Required"));
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("Negative line/column returns INVALID_PARAMETER")
    void handlesInvalidLineColumn() {
        assertPayload(at(refactoringTargetPath, -1, -1, "test"),
            refusal("Invalid parameter 'line/column': Must be >= 0"));
    }

    @Test
    @DisplayName("Position with no symbol returns SYMBOL_NOT_FOUND")
    void handlesNoSymbolAtPosition() {
        // Line 1 col 0 is the blank line between `package` and the class Javadoc.
        assertPayload(at(refactoringTargetPath, 1, 0, "test"), Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: No symbol at position",
            "hint", "Use search_symbols to find available symbols")));
    }

    // ========== Semantic-grade tests (exact-content assertions) ==========

    @Test
    @DisplayName("rename FieldHolder.pet (custom-typed field): cross-file edits across FieldHolder + WidgetHelper")
    void renameCustomTypedField_emitsAllEditsAcrossFiles() {
        // FieldHolder: the declaration (4), both constructors' this.pet (7, 11) and getPet (15).
        // WidgetHelper: describe's read (5), swap's write (9), extract's read and write (13, 14).
        String fieldHolder = "src/main/java/com/example/FieldHolder.java";
        String widgetHelper = "src/main/java/com/example/WidgetHelper.java";
        assertPayload(at(projectPath.resolve(fieldHolder).toString(), 4, 11, "companion"),
            rename("pet", "companion", "field", Map.of(
                fieldHolder, List.of(edit(15, 15), edit(11, 13), edit(7, 13), edit(4, 11)),
                widgetHelper, List.of(edit(14, 15), edit(13, 32), edit(9, 15), edit(5, 32))), null));
    }

    @Test
    @DisplayName("renaming MethodRefTarget.formatId propagates to MethodRefTarget::formatId method-reference call site")
    void renameMethod_propagatesToTypeMethodReference() {
        // MethodRefUser holds `IntFunction<String> formatter = MethodRefTarget::formatId;` (line 15).
        String target = "src/main/java/com/example/MethodRefTarget.java";
        String user = "src/main/java/com/example/MethodRefUser.java";
        assertPayload(at(projectPath.resolve(target).toString(), 8, 25, "formatIdentifier"),
            rename("formatId", "formatIdentifier", "method", Map.of(
                target, List.of(edit(8, 25)),
                user, List.of(edit(15, 57))), null));
    }

    @Test
    @DisplayName("renaming SuperMethodParent.greet propagates to super.greet(name) and super::greet in SuperMethodChild")
    void renameMethod_propagatesToSuperMethodInvocation() {
        // SuperMethodChild does not override greet: it calls super.greet(name) (line 17) and
        // captures super::greet (line 21).
        String parent = "src/main/java/com/example/SuperMethodParent.java";
        String child = "src/main/java/com/example/SuperMethodChild.java";
        assertPayload(at(projectPath.resolve(parent).toString(), 8, 18, "salute"),
            rename("greet", "salute", "method", Map.of(
                parent, List.of(edit(8, 18)),
                child, List.of(edit(21, 22), edit(17, 21))), null));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Per-edit shape: line, column, endColumn, oldText, newText, and an offset span equal to the old name's length")
    void perEdit_shapeIncludesAllFields() {
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    @Test
    @DisplayName("Renaming a type whose file is named after it sets symbolKind from the type's kind and renames the file in renamedFiles")
    void renameClass_renamesItsFile() {
        // Marker (declared on line 11, `public @interface Marker`) is applied as @Marker in seven
        // positions of AnnotationUsages and referenced nowhere else.
        String marker = "src/main/java/com/example/Marker.java";
        String usages = "src/main/java/com/example/AnnotationUsages.java";
        assertPayload(at(projectPath.resolve(marker).toString(), 11, 18, "Tagged"), rename("Marker", "Tagged",
            "annotation", Map.of(
                marker, List.of(edit(11, 18)),
                usages, List.of(edit(35, 17), edit(31, 9), edit(30, 33), edit(16, 5), edit(12, 5), edit(9, 5),
                    edit(6, 1))),
            List.of(Map.of("filePath", marker, "newFilePath", "src/main/java/com/example/Tagged.java"))));
    }

    @Test
    @DisplayName("Rename method propagates to call sites in other files")
    void renameMethod_includesCrossFileCallSites() {
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    @Test
    @DisplayName("Method rename does NOT touch a same-named but unrelated method in a different type (isolation)")
    void renameMethod_isolation_doesNotTouchUnrelatedSameName() {
        // List.add calls (SearchPatterns' calcs.add, list.add) bind to another method.
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    @Test
    @DisplayName("Reject reserved word: var, sealed, permits, record, yield all rejected")
    void rejectAdditionalReservedWords() {
        assertAll(List.of("var", "sealed", "permits", "record", "yield").stream()
            .map(word -> (org.junit.jupiter.api.function.Executable) () -> assertPayload(
                at(refactoringTargetPath, 88, 12, word),
                refusal("Invalid parameter 'newName': Not a valid Java identifier"))));
    }

    @Test
    @DisplayName("Reject empty newName")
    void rejectEmptyNewName() {
        assertPayload(at(refactoringTargetPath, 88, 12, ""), refusal("Invalid parameter 'newName': Required"));
    }

    @Test
    @DisplayName("A method rename renames no file: renamedFiles is absent")
    void renameMethod_renamesNoFile() {
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    @Test
    @DisplayName("Renaming a generic-class type parameter propagates to its usages in member signatures and body")
    void renameTypeParameter_propagatesInClassBody() {
        // TypeKindsFixture.GenericContainer<T> (line 16, column 41) uses T in its field (18), the
        // getter's return type (20) and the setter's parameter type (24).
        String typeKinds = "src/main/java/com/example/TypeKindsFixture.java";
        assertPayload(at(projectPath.resolve(typeKinds).toString(), 16, 41, "E"), rename("T", "E", "typeParameter",
            Map.of(typeKinds, List.of(edit(24, 24), edit(20, 15), edit(18, 16), edit(16, 41))), null));
    }

    @Test
    @DisplayName("totalEdits equals the sum of per-file edit list sizes")
    void totalEdits_sumsAcrossFiles() {
        assertPayload(addToSum(), ADD_TO_SUM);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: renaming Calculator.add edits its declaration and its four calls, every edit add->sum")
    void envelope_renameAdd_crossFileExactEdits() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("newName", "sum");
        assertPayload(args, ADD_TO_SUM);
    }

    // ========== exact payload helpers ==========

    private static final String REFACTORING_TARGET = "src/main/java/com/example/RefactoringTarget.java";

    /**
     * Calculator.add renamed to sum: its declaration (line 14) and its calls in SearchPatterns
     * (58, 80), UserService (58) and SampleTest (35); the edit sits on `add` in each.
     */
    private static final Map<String, Object> ADD_TO_SUM = rename("add", "sum", "method", Map.of(
        "src/main/java/com/example/Calculator.java", List.of(edit(14, 15)),
        "src/main/java/com/example/SearchPatterns.java", List.of(edit(80, 17), edit(58, 26)),
        "src/main/java/com/example/service/UserService.java", List.of(edit(58, 26)),
        "src/test/java/com/example/SampleTest.java", List.of(edit(35, 32))), null);

    private ObjectNode addToSum() {
        return at(calculatorPath, 14, 15, "sum");
    }

    private ObjectNode at(String file, int line, int column, String newName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        return args;
    }

    /** An edit at {@code line} and {@code column}; its old and new names are filled in by {@link #rename}. */
    private static Map<String, Object> edit(int line, int column) {
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("line", line);
        edit.put("column", column);
        return edit;
    }

    /**
     * The success payload renaming {@code oldName} to {@code newName} with {@code edits} by file, and
     * {@code renamedFiles} when the rename renames a file.
     */
    private static Map<String, Object> rename(String oldName, String newName, String symbolKind,
                                              Map<String, List<Map<String, Object>>> edits,
                                              List<Map<String, Object>> renamedFiles) {
        Map<String, List<Map<String, Object>>> editsByFile = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            List<Map<String, Object>> fileEdits = new ArrayList<>();
            for (Map<String, Object> position : file.getValue()) {
                Map<String, Object> edit = new LinkedHashMap<>(position);
                edit.put("endColumn", (int) position.get("column") + oldName.length());
                edit.put("oldText", oldName);
                edit.put("newText", newName);
                edit.put("span", oldName.length());
                fileEdits.add(edit);
            }
            editsByFile.put(file.getKey(), fileEdits);
            total += fileEdits.size();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", oldName);
        data.put("newName", newName);
        data.put("symbolKind", symbolKind);
        data.put("totalEdits", total);
        data.put("filesAffected", editsByFile.size());
        data.put("editsByFile", editsByFile);
        if (renamedFiles != null) {
            data.put("renamedFiles", renamedFiles);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", total);
        meta.put("suggestedNextTools", List.of(
            "Apply the text edits to complete the rename",
            "get_diagnostics to verify no errors after rename"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each edit's raw offsets replaced by
     * their span.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = spans(objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = spans(envelope.assertEnvelopeFidelity("rename_symbol", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** {@code payload} with each edit's startOffset and endOffset replaced by the span between them. */
    private static JsonNode spans(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("editsByFile").forEach(edits -> edits.forEach(edit -> {
            ObjectNode object = (ObjectNode) edit;
            int span = object.path("endOffset").asInt() - object.path("startOffset").asInt();
            object.remove(List.of("startOffset", "endOffset"));
            object.put("span", span);
        }));
        return copy;
    }
}
