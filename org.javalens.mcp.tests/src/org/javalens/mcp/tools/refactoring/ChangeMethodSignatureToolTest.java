package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
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
 * Integration tests for ChangeMethodSignatureTool on simple-maven: signature changes and call
 * site updates. Every expectation is the whole payload, through execute() and through the
 * JSON-RPC envelope. The declaration edit spans the signature from the return type (a
 * constructor's name) to the closing parenthesis; each call edit spans the call, a constructor
 * delegation with its semicolon, and rebuilds its arguments in the new order. Raw offsets depend
 * on line endings, so each edit's startOffset and endOffset are replaced by the span between them;
 * lines and columns pin the edit.
 *
 * <p>RefactoringTarget declares `String formatMessage(String message, int count)` on line 71 and
 * calls it on lines 79 and 81. ConstructorTarget(String, int) (line 11) is called by
 * ConstructorTarget(String)'s this(name, 0) (line 8), four times by ConstructorCaller (lines 8, 17,
 * 18, 19) and by ConstructorSubclass's super(name, count) (line 11).
 */
class ChangeMethodSignatureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ChangeMethodSignatureTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("renames method and updates call sites with complete response")
    void renamesMethodWithCompleteResponse() {
        assertPayload(formatMessageArgs("formatOutput", null), RENAME_TO_FORMAT_OUTPUT);
    }

    @Test
    @DisplayName("add parameter: declaration newSignature includes the new param; each call passes its default")
    void addsParameterWithDefaultValue() {
        ObjectNode args = formatMessageArgs(null, null);
        args.set("newParameters", params("message", "String", null, "count", "int", null, "prefix", "String", "\"\""));
        assertPayload(args, formatMessage("formatMessage", "String", List.of("message", "String", "count", "int",
            "prefix", "String"), "String formatMessage(String message, int count, String prefix)",
            "formatMessage(\"Items\", 5, \"\")", "formatMessage(\"Total\", 10, \"\")"));
    }

    @Test
    @DisplayName("remove parameter: declaration newSignature drops `count`; each call drops its argument")
    void removesParameter() {
        ObjectNode args = formatMessageArgs(null, null);
        args.set("newParameters", params("message", "String", null));
        assertPayload(args, formatMessage("formatMessage", "String", List.of("message", "String"),
            "String formatMessage(String message)", "formatMessage(\"Items\")", "formatMessage(\"Total\")"));
    }

    @Test
    @DisplayName("reorder parameters: declaration newSignature and each call have the swapped order")
    void reordersParameters() {
        ObjectNode args = formatMessageArgs(null, null);
        args.set("newParameters", params("count", "int", null, "message", "String", null));
        assertPayload(args, formatMessage("formatMessage", "String", List.of("count", "int", "message", "String"),
            "String formatMessage(int count, String message)", "formatMessage(5, \"Items\")",
            "formatMessage(10, \"Total\")"));
    }

    @Test
    @DisplayName("changing formatMessage's return type to void, which its body and callers in its file use, is refused, naming each compile error")
    void changesReturnType() {
        // formatMessage (line 71, return type at 11) returns a String its callers in
        // printMessages (line 78, return type at 11) assign and print: void breaks all three
        String at = "src/main/java/com/example/RefactoringTarget.java:";
        assertPayload(formatMessageArgs(null, "void"), refusal("Invalid parameter 'newParameters': "
            + "Void methods cannot return a value, in RefactoringTarget.formatMessage at " + at + "71:11; "
            + "Type mismatch: cannot convert from void to String, in RefactoringTarget.printMessages at " + at + "78:11; "
            + "The method println(boolean) in the type PrintStream is not applicable for the arguments (void),"
            + " in RefactoringTarget.printMessages at " + at + "78:11"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires at least one change to be specified")
    void requiresAtLeastOneChange() {
        assertPayload(formatMessageArgs(null, null), refusal("Invalid parameter 'changes': At least one of newName, "
            + "newReturnType, or newParameters must be specified"));
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "formatOutput");
        assertPayload(args, refusal("Invalid parameter 'filePath': Required"));
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = at(refactoringTargetPath, -1, -1);
        args.put("newName", "formatOutput");
        assertPayload(args, refusal("Invalid parameter 'line/column': Must be >= 0"));
    }

    @Test
    @DisplayName("handles non-method position gracefully")
    void handlesNotAMethod() {
        ObjectNode args = at(refactoringTargetPath, 14, 19);  // Field declaration
        args.put("newName", "newFieldName");
        assertPayload(args, Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: No method found at position",
            "hint", "Use search_symbols to find available symbols")));
    }

    // ========== Semantic-grade tests (constructor call sites) ==========

    @Test
    @DisplayName("adding a parameter to ConstructorTarget(String, int) updates all 4 `new` call sites + this() delegation")
    void addParam_updatesConstructorCallSitesAcrossFiles() {
        // A constructor's signature starts at its name and has no return type; the payload
        // reports no return types for it.
        assertPayload(constructorTagArgs(), CONSTRUCTOR_TAG);
    }

    @Test
    @DisplayName("adding a parameter to ConstructorTarget(String, int) propagates to the this(name, 0) delegation site in the same file")
    void addParam_propagatesToThisConstructorDelegation() {
        // The this(...) form is a ConstructorInvocation statement: its edit covers the
        // trailing semicolon.
        assertPayload(constructorTagArgs(), CONSTRUCTOR_TAG);
    }

    @Test
    @DisplayName("adding a parameter to ConstructorTarget(String, int) propagates to a subclass's super(name, count) delegation")
    void addParam_propagatesToSuperConstructorDelegation() {
        // The super(...) form is a SuperConstructorInvocation statement: its edit covers the
        // trailing semicolon.
        assertPayload(constructorTagArgs(), CONSTRUCTOR_TAG);
    }

    @Test
    @DisplayName("adding a parameter to a method consumed via method reference (Foo::method) reports that reference with a warning and leaves its file unedited")
    void addParam_methodReferenceCallSite_isSurfaced() {
        // MethodRefUser holds `IntFunction<String> formatter = MethodRefTarget::formatId;` (line
        // 15, the reference at column 40). IntFunction expects (int), so no textual rewrite fits.
        String target = "src/main/java/com/example/MethodRefTarget.java";
        String user = "src/main/java/com/example/MethodRefUser.java";
        ObjectNode args = at(projectPath.resolve(target).toString(), 8, 25);
        args.set("newParameters", params("id", "int", null, "prefix", "String", "\"\""));
        assertPayload(args, payload("formatId", "formatId", List.of("String", "String"), 1,
            List.of("id", "int", "prefix", "String"),
            Map.of(target, List.of(declaration(8, 18, "String formatId(int id)", "String formatId(int id, String prefix)"))),
            List.of(methodReference(user, 15, 40, "MethodRefTarget::formatId"))));
    }

    @Test
    @DisplayName("adding a parameter to SuperMethodParent.greet propagates to super.greet(name) and reports the super::greet reference")
    void addParam_propagatesToSuperMethodInvocation() {
        // SuperMethodChild calls super.greet(name) (line 17), a SuperMethodInvocation, and
        // captures super::greet (line 21), a method reference that cannot be rewritten.
        String parent = "src/main/java/com/example/SuperMethodParent.java";
        String child = "src/main/java/com/example/SuperMethodChild.java";
        ObjectNode args = at(projectPath.resolve(parent).toString(), 8, 18);
        args.set("newParameters", params("name", "String", null, "suffix", "String", "\"\""));
        assertPayload(args, payload("greet", "greet", List.of("String", "String"), 1,
            List.of("name", "String", "suffix", "String"),
            Map.of(
                parent, List.of(declaration(8, 11, "String greet(String name)", "String greet(String name, String suffix)")),
                child, List.of(call(17, 15, "super.greet(name)", "super.greet(name, \"\")"))),
            List.of(methodReference(child, 21, 15, "super::greet"))));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Invalid Java identifier in newName is rejected")
    void rejectsInvalidNewName() {
        assertPayload(formatMessageArgs("123invalid", null),
            refusal("Invalid parameter 'newName': Not a valid Java identifier"));
    }

    @Test
    @DisplayName("removing all of formatMessage's parameters, which its body reads, is refused, naming the compile error")
    void removesAllParameters() {
        // formatMessage's body reads message first, which no longer resolves
        ObjectNode args = formatMessageArgs(null, null);
        args.set("newParameters", objectMapper.createArrayNode());
        assertPayload(args, refusal("Invalid parameter 'newParameters': message cannot be resolved to a variable,"
            + " in RefactoringTarget.formatMessage at src/main/java/com/example/RefactoringTarget.java:71:11"));
    }

    @Test
    @DisplayName("Declaration edit carries type, positions, oldSignature, newSignature and isDeclaration=true")
    void declarationEdit_shape() {
        assertPayload(formatMessageArgs("formatOutput", null), RENAME_TO_FORMAT_OUTPUT);
    }

    @Test
    @DisplayName("Only one declaration edit exists across all files")
    void onlyOneDeclarationEdit() {
        assertPayload(formatMessageArgs("formatOutput", null), RENAME_TO_FORMAT_OUTPUT);
    }

    @Test
    @DisplayName("totalEdits equals the sum of per-file edit list sizes")
    void totalEdits_sumsAcrossFiles() {
        assertPayload(formatMessageArgs("formatOutput", null), RENAME_TO_FORMAT_OUTPUT);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: adding `String prefix` yields newSignature `String formatMessage(String message, int count, String prefix)`")
    void envelope_addParameter_exactNewSignature() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 71);
        args.put("column", 18);
        args.set("newParameters", params("message", "String", null, "count", "int", null, "prefix", "String", "\"\""));
        assertPayload(args, formatMessage("formatMessage", "String", List.of("message", "String", "count", "int",
            "prefix", "String"), "String formatMessage(String message, int count, String prefix)",
            "formatMessage(\"Items\", 5, \"\")", "formatMessage(\"Total\", 10, \"\")"));
    }

    // ========== exact payload helpers ==========

    private static final String REFACTORING_TARGET = "src/main/java/com/example/RefactoringTarget.java";

    private static final Map<String, Object> RENAME_TO_FORMAT_OUTPUT = formatMessage("formatOutput", "String",
        List.of("message", "String", "count", "int"), "String formatOutput(String message, int count)",
        "formatOutput(\"Items\", 5)", "formatOutput(\"Total\", 10)");

    /**
     * ConstructorTarget(String, int) given a third parameter, tag, defaulting to "": its
     * declaration and the this() delegation in ConstructorTarget, ConstructorCaller's four
     * creations and ConstructorSubclass's super() delegation.
     */
    private static final Map<String, Object> CONSTRUCTOR_TAG = payload("ConstructorTarget", "ConstructorTarget",
        null, 2, List.of("name", "String", "count", "int", "tag", "String"),
        Map.of(
            "src/main/java/com/example/ConstructorTarget.java", List.of(
                declaration(11, 11, "ConstructorTarget(String name, int count)",
                    "ConstructorTarget(String name, int count, String tag)"),
                call(8, 8, "this(name, 0);", "this(name, 0, \"\");")),
            "src/main/java/com/example/ConstructorCaller.java", List.of(
                call(8, 15, "new ConstructorTarget(\"alpha\", 1)", "new ConstructorTarget(\"alpha\", 1, \"\")"),
                call(17, 16, "new ConstructorTarget(\"a\", 1)", "new ConstructorTarget(\"a\", 1, \"\")"),
                call(18, 16, "new ConstructorTarget(\"b\", 2)", "new ConstructorTarget(\"b\", 2, \"\")"),
                call(19, 16, "new ConstructorTarget(\"c\", 3)", "new ConstructorTarget(\"c\", 3, \"\")")),
            "src/main/java/com/example/ConstructorSubclass.java", List.of(
                call(11, 8, "super(name, count);", "super(name, count, \"\");"))),
        List.of());

    private ObjectNode at(String file, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    /** formatMessage at its name (line 71, column 18), with the given newName and newReturnType. */
    private ObjectNode formatMessageArgs(String newName, String newReturnType) {
        ObjectNode args = at(refactoringTargetPath, 71, 18);
        if (newName != null) {
            args.put("newName", newName);
        }
        if (newReturnType != null) {
            args.put("newReturnType", newReturnType);
        }
        return args;
    }

    private ObjectNode constructorTagArgs() {
        // The 2-arg constructor's name on 0-based line 11.
        ObjectNode args = at(projectPath.resolve("src/main/java/com/example/ConstructorTarget.java").toString(), 11, 11);
        args.set("newParameters", params("name", "String", null, "count", "int", null, "tag", "String", "\"\""));
        return args;
    }

    /** newParameters from triples of name, type and default value (null for none). */
    private ArrayNode params(String... nameTypeDefault) {
        ArrayNode params = objectMapper.createArrayNode();
        for (int i = 0; i < nameTypeDefault.length; i += 3) {
            ObjectNode param = params.addObject();
            param.put("name", nameTypeDefault[i]);
            param.put("type", nameTypeDefault[i + 1]);
            if (nameTypeDefault[i + 2] != null) {
                param.put("defaultValue", nameTypeDefault[i + 2]);
            }
        }
        return params;
    }

    /**
     * formatMessage's payload: its declaration (line 71, the signature from column 11) and its
     * two calls, on line 79 at column 24 and on line 81 at column 27.
     */
    private static Map<String, Object> formatMessage(String newName, String newReturnType, List<String> newParameters,
                                                     String newSignature, String newCall79, String newCall81) {
        return payload("formatMessage", newName, List.of("String", newReturnType), 2, newParameters,
            Map.of(REFACTORING_TARGET, List.of(
                declaration(71, 11, "String formatMessage(String message, int count)", newSignature),
                call(79, 24, "formatMessage(\"Items\", 5)", newCall79),
                call(81, 27, "formatMessage(\"Total\", 10)", newCall81))),
            List.of());
    }

    /** A one-line declaration edit starting at {@code column}. */
    private static Map<String, Object> declaration(int line, int column, String oldSignature, String newSignature) {
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "replace");
        edit.put("startLine", line);
        edit.put("startColumn", column);
        edit.put("endLine", line);
        edit.put("endColumn", column + oldSignature.length());
        edit.put("span", oldSignature.length());
        edit.put("oldSignature", oldSignature);
        edit.put("newSignature", newSignature);
        edit.put("isDeclaration", true);
        return edit;
    }

    /** A one-line call edit starting at {@code column}. */
    private static Map<String, Object> call(int line, int column, String oldText, String newText) {
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("type", "replace");
        edit.put("startLine", line);
        edit.put("startColumn", column);
        edit.put("endLine", line);
        edit.put("endColumn", column + oldText.length());
        edit.put("span", oldText.length());
        edit.put("oldText", oldText);
        edit.put("newText", newText);
        edit.put("isDeclaration", false);
        return edit;
    }

    /** A method reference that cannot be rewritten. */
    private static Map<String, Object> methodReference(String file, int line, int column, String text) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("filePath", file);
        reference.put("line", line);
        reference.put("column", column);
        reference.put("span", text.length());
        reference.put("text", text);
        reference.put("reason", "Method reference cannot be automatically rewritten; the functional-interface "
            + "signature would no longer match. Replace with a lambda or refactor manually.");
        return reference;
    }

    /**
     * The success payload of a signature change; {@code returnTypes} is the old and new return
     * type, null for a constructor, and {@code newParameters} alternates names and types.
     */
    private static Map<String, Object> payload(String oldName, String newName, List<String> returnTypes,
                                               int oldParameterCount, List<String> newParameters,
                                               Map<String, List<Map<String, Object>>> editsByFile,
                                               List<Map<String, Object>> methodReferences) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (int i = 0; i < newParameters.size(); i += 2) {
            parameters.add(Map.of("name", newParameters.get(i), "type", newParameters.get(i + 1)));
        }
        int total = editsByFile.values().stream().mapToInt(List::size).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", oldName);
        data.put("newName", newName);
        if (returnTypes != null) {
            data.put("oldReturnType", returnTypes.get(0));
            data.put("newReturnType", returnTypes.get(1));
        }
        data.put("oldParameterCount", oldParameterCount);
        data.put("newParameterCount", parameters.size());
        data.put("newParameters", parameters);
        data.put("totalEdits", total);
        data.put("filesAffected", editsByFile.size());
        data.put("editsByFile", editsByFile);
        if (!methodReferences.isEmpty()) {
            data.put("methodReferences", methodReferences);
            data.put("warnings", methodReferences.stream()
                .map(reference -> reference.get("filePath"))
                .distinct()
                .map(file -> file + " has method-reference call sites that cannot be automatically updated. "
                    + "Replace each with a lambda or refactor the functional-interface signature.")
                .toList());
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", total);
        meta.put("suggestedNextTools", List.of(
            "Apply the text edits to complete the signature change",
            "get_diagnostics to verify no errors"));
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
     * the JSON-RPC envelope both equal {@code expected}, each edit's and method reference's raw
     * offsets replaced by their span.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = spans(objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload for " + args + " does not serialize", e);
        }
        JsonNode wire = spans(envelope.assertEnvelopeFidelity("change_method_signature", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode spans(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        copy.path("data").path("editsByFile").forEach(edits -> edits.forEach(ChangeMethodSignatureToolTest::span));
        copy.path("data").path("methodReferences").forEach(ChangeMethodSignatureToolTest::span);
        return copy;
    }

    /** {@code node} with its startOffset and endOffset replaced by the span between them. */
    private static void span(JsonNode node) {
        ObjectNode object = (ObjectNode) node;
        int span = object.path("endOffset").asInt() - object.path("startOffset").asInt();
        object.remove(List.of("startOffset", "endOffset"));
        object.put("span", span);
    }
}
