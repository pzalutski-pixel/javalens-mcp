package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractMethodTool.
 * Tests code block extraction to new method.
 */
class ExtractMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractMethodTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractMethodTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts code block to method with complete response")
    void extractsCodeBlockWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);  // Start of sum calculation loop
        args.put("startColumn", 8);
        args.put("endLine", 47);    // End of loop
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("calculateSum", data.get("methodName"));
        assertEquals("int", data.get("returnType"));
        assertEquals("int sum = calculateSum(numbers);", data.get("methodCall"));
        // Exact generated method (CRLF-normalized): tab-indented body, the `sum += num`
        // line keeps the source selection's extra 4-space indent.
        assertEquals(
            "private int calculateSum(List<Integer> numbers) {\n"
            + "\t\tint sum = 0;\n"
            + "\t\tfor (Integer num : numbers) {\n"
            + "\t\t    sum += num;\n"
            + "\t\t}\n"
            + "\t\treturn sum;\n"
            + "\t}",
            ((String) data.get("newMethodCode")).replace("\r\n", "\n"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires methodName parameter")
    void requiresMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        // No methodName

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'methodName': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid method names and reserved words")
    void rejectsInvalidMethodNames() {
        // Test invalid identifier (starts with number)
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetPath);
        args1.put("startLine", 44);
        args1.put("startColumn", 8);
        args1.put("endLine", 47);
        args1.put("endColumn", 9);
        args1.put("methodName", "123invalid");

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response1.getError().getCode());
        assertEquals("Invalid parameter 'methodName': Not a valid Java identifier",
            response1.getError().getMessage());

        // Test reserved word
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetPath);
        args2.put("startLine", 44);
        args2.put("startColumn", 8);
        args2.put("endLine", 47);
        args2.put("endColumn", 9);
        args2.put("methodName", "while");

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response2.getError().getCode());
        assertEquals("Invalid parameter 'methodName': Not a valid Java identifier",
            response2.getError().getMessage());
    }

    @Test
    @DisplayName("handles invalid range gracefully")
    void handlesInvalidRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", -1);
        args.put("startColumn", -1);
        args.put("endLine", -1);
        args.put("endColumn", -1);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'positions': All positions must be >= 0",
            response.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("extracted method body contains code text from selected range")
    void extractedMethodBody_containsSelectedCodeText() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String declaration = (String) getData(r).get("newMethodCode");

        // The selection includes a `sum +=` loop body; the extracted method must contain it.
        assertTrue(declaration.contains("sum +="),
            "Extracted method body must include the original `sum +=` accumulator; got:\n" + declaration);
        assertTrue(declaration.contains("calculateSum"),
            "Method declaration must contain its new name; got:\n" + declaration);
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode calculateSumArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> editsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("edits");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paramsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("parameters");
    }

    @Test
    @DisplayName("Detected parameters include `numbers` (declared before and used in selection)")
    void parameters_includeNumbers() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> params = paramsOf(r);
        boolean hasNumbers = params.stream()
            .anyMatch(p -> "numbers".equals(p.get("name")));
        assertTrue(hasNumbers,
            "`numbers` is declared as method parameter before the selection and read inside it; "
                + "must appear as an extracted-method parameter. got: " + params);
    }

    @Test
    @DisplayName("returnType is `int` (sum is modified and used after the selection)")
    void returnType_isIntForModifiedAndUsedAfter() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        // `sum` is declared inside selection and read after via `return sum * 2;`.
        // The tool classifies this as a returned value.
        assertEquals("int", getData(r).get("returnType"),
            "returnType must be `int` when single modified variable (sum) is used after selection");
    }

    @Test
    @DisplayName("methodCall assigns into the returned variable, redeclaring its type when extracted with the declaration")
    void methodCall_assignsReturnValue() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        String call = (String) getData(r).get("methodCall");
        assertNotNull(call);
        // `sum` is declared inside the selection (the `int sum = 0;` line is
        // part of the extracted range), so the call site must reintroduce
        // the declaration to keep post-selection references compiling.
        assertTrue(call.contains("sum = "),
            "methodCall must assign result into `sum`; got: " + call);
        assertTrue(call.contains("calculateSum"),
            "methodCall must reference the new method name; got: " + call);
        assertTrue(call.contains("numbers"),
            "methodCall must pass the parameter; got: " + call);
    }

    @Test
    @DisplayName("Exactly two edits emitted: one insert (new method) + one replace (selection)")
    void edits_haveInsertAndReplace() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = editsOf(r);
        assertEquals(2, edits.size(),
            "extract_method must emit exactly 2 edits: insert + replace; got: " + edits);
        java.util.Set<String> types = new java.util.HashSet<>();
        for (Map<String, Object> e : edits) {
            types.add((String) e.get("type"));
        }
        assertEquals(java.util.Set.of("insert", "replace"), types,
            "Edit types must be {insert, replace}; got: " + types);
    }

    @Test
    @DisplayName("Insert edit carries newText + line + offset")
    void insertEdit_shape() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        Map<String, Object> insert = editsOf(r).stream()
            .filter(e -> "insert".equals(e.get("type"))).findFirst().orElseThrow();
        String newText = (String) insert.get("newText");
        assertNotNull(newText, "newText missing on insert edit");
        assertTrue(newText.contains("calculateSum"),
            "newText must contain the extracted method name; got: " + insert);
        assertTrue(((Number) insert.get("line")).intValue() >= 0, "line >= 0; got: " + insert);
        assertTrue(((Number) insert.get("offset")).intValue() >= 0, "offset >= 0; got: " + insert);
    }

    @Test
    @DisplayName("Replace edit carries startLine/Column, endLine/Column, startOffset/endOffset, oldText, newText")
    void replaceEdit_shape() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        Map<String, Object> replace = editsOf(r).stream()
            .filter(e -> "replace".equals(e.get("type"))).findFirst().orElseThrow();
        for (String key : List.of("startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(replace.get(key), key + " missing on replace edit: " + replace);
        }
        assertEquals(44, ((Number) replace.get("startLine")).intValue());
        assertEquals(47, ((Number) replace.get("endLine")).intValue());
    }

    @Test
    @DisplayName("Empty methodName rejected")
    void rejectsEmptyMethodName() {
        ObjectNode args = calculateSumArgs();
        args.put("methodName", "");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("Selection outside any method body is rejected")
    void rejectsSelectionOutsideMethod() {
        // Position 0,0 is the package declaration — outside any method body.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 0);
        args.put("startColumn", 0);
        args.put("endLine", 0);
        args.put("endColumn", 7);
        args.put("methodName", "extracted");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Selection outside any method body must be rejected; got: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'selection': Selection must be inside a method body",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("Extracting from a generic method propagates the method-level <U> clause to the new method")
    @SuppressWarnings("unchecked")
    void extractFromGenericMethod_propagatesTypeParameter() {
        // GenericExtractTarget.processGeneric is declared as `<U> U processGeneric(U input)`.
        // Extracting the statement `System.out.println(result);` (where `result` is of
        // type U) into a new private method requires that new method to declare <U>
        // too, otherwise the parameter type U is undeclared in the new method scope.
        String generic = projectPath.resolve(
            "src/main/java/com/example/GenericExtractTarget.java").toString();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("startLine", 12);
        args.put("startColumn", 8);
        args.put("endLine", 12);
        args.put("endColumn", 35);
        args.put("methodName", "logResult");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "Extraction must succeed; got: "
            + (r.getError() != null ? r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        String newMethodCode = (String) data.get("newMethodCode");
        assertNotNull(newMethodCode);
        assertTrue(newMethodCode.contains("<U"),
            "The extracted method body references U; new method declaration must include `<U>`; got: "
                + newMethodCode);
    }

    @Test
    @DisplayName("Returned variable declared inside selection is redeclared at call site")
    void returnedVariable_declaredInSelection_isRedeclaredAtCallSite() {
        // The fixture's calculateTotal has `int sum = 0;` INSIDE the selection
        // (the selection starts at that line). The extracted method declares
        // and returns sum. After the selection is replaced with the call,
        // the post-selection code `return sum * 2;` would reference an
        // undeclared sum unless the call site redeclares it. The methodCall
        // must therefore be `int sum = calculateSum(numbers);`, not
        // `sum = calculateSum(numbers);`.
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());
        String call = (String) getData(r).get("methodCall");
        assertNotNull(call);
        assertTrue(call.startsWith("int sum = "),
            "Call site must redeclare `int sum` since its declaration was extracted; got: " + call);
    }

    @Test
    @DisplayName("Inverted range (start >= end) is rejected")
    void rejectsInvertedRange() {
        ObjectNode args = calculateSumArgs();
        // Swap endLine/endColumn with startLine/startColumn so start > end.
        args.put("startLine", 47);
        args.put("startColumn", 9);
        args.put("endLine", 44);
        args.put("endColumn", 8);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "Inverted range must be rejected");
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'positions': Invalid selection range", r.getError().getMessage());
    }

    // ========== Exact extracted-parameter type ==========

    @Test
    @DisplayName("Extracted parameter `numbers` keeps its declared type List<Integer>")
    void numbers_parameterHasListIntegerType() {
        ToolResponse r = tool.execute(calculateSumArgs());
        assertTrue(r.isSuccess());

        // calculateTotal(List<Integer> numbers) — the extracted method takes
        // `numbers` by its declared type. A degraded resolution to Object or raw
        // List would compile-break or change semantics, so the type is pinned.
        Map<String, Object> numbers = paramsOf(r).stream()
            .filter(p -> "numbers".equals(p.get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("no `numbers` parameter: " + paramsOf(r)));
        assertEquals("List<Integer>", numbers.get("type"),
            "extracted parameter must keep its declared type List<Integer>; got: " + numbers);

        // The rendered declaration must carry the typed parameter, not Object.
        assertTrue(((String) getData(r).get("newMethodCode"))
                .contains("calculateSum(List<Integer> numbers)"),
            "new method signature must read calculateSum(List<Integer> numbers); got:\n"
                + getData(r).get("newMethodCode"));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: extracted method returns int and takes List<Integer> numbers")
    void envelope_extract_exactSignature() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");
        JsonNode payload = envelope.assertEnvelopeFidelity("extract_method", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "extract_method failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("int", data.get("returnType").asText());
        JsonNode numbers = null;
        for (JsonNode p : data.get("parameters")) {
            if ("numbers".equals(p.get("name").asText())) numbers = p;
        }
        assertNotNull(numbers, () -> "no `numbers` parameter through envelope: " + data);
        assertEquals("List<Integer>", numbers.get("type").asText(),
            "parameter type must survive the envelope, not degrade to Object");
    }
}
