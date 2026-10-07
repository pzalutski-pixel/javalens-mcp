package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractConstantTool.
 * Tests expression extraction to static final constant.
 */
class ExtractConstantToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractConstantTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractConstantTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdits(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edits");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts expression to static final constant with complete response")
    void extractsExpressionToConstantWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);  // "PREFIX_" string literal
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify constant info — extracting a String literal yields String type
        assertEquals("DEFAULT_PREFIX", data.get("constantName"));
        String constantType = (String) data.get("constantType");
        assertNotNull(constantType, "constantType missing");
        assertEquals("String", constantType,
            "extracted PREFIX_ string literal yields String type; got: " + data);

        // Verify edit structure
        List<Map<String, Object>> edits = getEdits(data);
        assertFalse(edits.isEmpty());

        // The declaration edit should contain static final
        boolean hasStaticFinal = edits.stream()
            .anyMatch(e -> {
                String newText = (String) e.get("newText");
                return newText != null && newText.contains("static") && newText.contains("final");
            });
        assertTrue(hasStaticFinal);
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("extract emits a declaration edit naming the constant and referencing the value")
    void extract_declarationEditNamesConstantAndHoldsValue() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));

        // At least one edit's newText must declare DEFAULT_PREFIX with the literal "PREFIX_".
        boolean declMentionsName = edits.stream()
            .map(e -> (String) e.get("newText"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(t -> t.contains("DEFAULT_PREFIX") && t.contains("PREFIX_"));
        assertTrue(declMentionsName,
            "At least one edit must declare DEFAULT_PREFIX initialized to \"PREFIX_\"; got: " + edits);
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires constantName parameter")
    void requiresConstantName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        // No constantName provided

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'constantName': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", response.getError().getMessage());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid constant names")
    void rejectsInvalidConstantNames() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "123INVALID");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'constantName': Not a valid Java identifier",
            response.getError().getMessage());
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
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'positions': All positions must be >= 0",
            response.getError().getMessage());
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode prefixArgs(String name) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        if (name != null) args.put("constantName", name);
        return args;
    }

    @Test
    @DisplayName("constantType is `String` for the \"PREFIX_\" literal")
    void constantType_isString() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        assertEquals("String", getData(r).get("constantType"));
    }

    @Test
    @DisplayName("expressionText is the selected literal text")
    void expressionText_matchesSelection() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        assertEquals("\"PREFIX_\"", getData(r).get("expressionText"));
    }

    @Test
    @DisplayName("declaration string is `private static final String DEFAULT_PREFIX = \"PREFIX_\";`")
    void declaration_shape() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        assertEquals("private static final String DEFAULT_PREFIX = \"PREFIX_\";",
            getData(r).get("declaration"));
    }

    @Test
    @DisplayName("containingType reports RefactoringTarget")
    void containingType_reported() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        assertEquals("com.example.RefactoringTarget", getData(r).get("containingType"));
    }

    @Test
    @DisplayName("Exactly two edits emitted: one insert (declaration) + one replace (expression)")
    void edits_haveInsertAndReplace() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        List<Map<String, Object>> edits = getEdits(getData(r));
        assertEquals(2, edits.size(),
            "extract_constant must emit exactly 2 edits: insert + replace; got: " + edits);
        java.util.Set<String> types = new java.util.HashSet<>();
        for (Map<String, Object> e : edits) types.add((String) e.get("type"));
        assertEquals(java.util.Set.of("insert", "replace"), types);
    }

    @Test
    @DisplayName("Replace edit substitutes the constant name for the expression")
    void replaceEdit_substitutesConstantName() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        Map<String, Object> replace = getEdits(getData(r)).stream()
            .filter(e -> "replace".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("DEFAULT_PREFIX", replace.get("newText"));
        for (String key : List.of("startLine", "startColumn", "endLine", "endColumn",
                "startOffset", "endOffset", "oldText", "newText")) {
            assertNotNull(replace.get(key), key + " missing on replace edit: " + replace);
        }

        // The "PREFIX_" literal spans 0-based line 35, columns 24..33 — the replace
        // edit must cover exactly that range. An off-by-one mis-applies the edit.
        assertEquals(35, ((Number) replace.get("startLine")).intValue());
        assertEquals(24, ((Number) replace.get("startColumn")).intValue());
        assertEquals(35, ((Number) replace.get("endLine")).intValue());
        assertEquals(33, ((Number) replace.get("endColumn")).intValue());
        assertEquals("\"PREFIX_\"", replace.get("oldText"));
    }

    @Test
    @DisplayName("Insert edit's newText includes the static final declaration")
    void insertEdit_containsStaticFinalDeclaration() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        Map<String, Object> insert = getEdits(getData(r)).stream()
            .filter(e -> "insert".equals(e.get("type"))).findFirst().orElseThrow();
        String text = (String) insert.get("newText");
        assertNotNull(text);
        assertTrue(text.contains("private static final String DEFAULT_PREFIX"),
            "Insert edit must include the static final declaration; got: " + text);
    }

    @Test
    @DisplayName("Empty constantName rejected")
    void rejectsEmptyConstantName() {
        ObjectNode args = prefixArgs(null);
        args.put("constantName", "");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("Inverted range (start >= end) is rejected")
    void rejectsInvertedRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 33);
        args.put("endLine", 35);
        args.put("endColumn", 24);
        args.put("constantName", "DEFAULT_PREFIX");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'positions': Invalid selection range", r.getError().getMessage());
    }

    // ========== Static-context extractability ==========

    private void assertStaticRefusal(ToolResponse r, String reasonSuffix) {
        assertFalse(r.isSuccess(), "static-context refusal expected; got: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'selection': Expression cannot be extracted to a "
            + "static final constant: " + reasonSuffix, r.getError().getMessage());
    }

    @Test
    @DisplayName("Refuses extraction when expression references a method parameter")
    void refusesExtractionReferencingParameter() {
        // A static final field initializer runs at class load and cannot
        // reference method parameters. Selecting `input` (the parameter of
        // processData) would yield `private static final String X = input;`
        // which does not compile. Tool must refuse, not produce broken edits.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 26);
        args.put("constantName", "INPUT_REF");

        ToolResponse r = tool.execute(args);
        assertStaticRefusal(r, "references local variable or parameter `input`");
    }

    @Test
    @DisplayName("Refuses extraction when expression references a local variable")
    void refusesExtractionReferencingLocalVariable() {
        // oldName is a local variable in localVariableRename(); it cannot
        // appear in a static initializer.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 90);
        args.put("startColumn", 22);
        args.put("endLine", 90);
        args.put("endColumn", 29);
        args.put("constantName", "LOCAL_REF");

        ToolResponse r = tool.execute(args);
        assertStaticRefusal(r, "references local variable or parameter `oldName`");
    }

    @Test
    @DisplayName("Refuses extraction when expression references an instance field")
    void refusesExtractionReferencingInstanceField() {
        // userName is a non-static field; `private static final ... = userName;`
        // does not compile.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 107);
        args.put("startColumn", 15);
        args.put("endLine", 107);
        args.put("endColumn", 23);
        args.put("constantName", "FIELD_REF");

        ToolResponse r = tool.execute(args);
        assertStaticRefusal(r, "references instance field `userName`");
    }

    @Test
    @DisplayName("Refuses extraction when expression calls an instance method")
    void refusesExtractionCallingInstanceMethod() {
        // formatMessage is an instance method; calling it from a static
        // initializer requires an instance and won't compile.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 79);
        args.put("startColumn", 24);
        args.put("endLine", 79);
        args.put("endColumn", 49);
        args.put("constantName", "METHOD_CALL");

        ToolResponse r = tool.execute(args);
        assertStaticRefusal(r, "calls instance method `formatMessage` via implicit `this`");
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: replace edit spans columns 24..33 of line 35")
    void envelope_replaceEdit_exactRange() {
        ObjectNode args = envelope.args();
        args.put("filePath", refactoringTargetPath);
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");
        JsonNode payload = envelope.assertEnvelopeFidelity("extract_constant", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "extract_constant failed through the envelope: " + payload);
        JsonNode replace = null;
        for (JsonNode e : payload.get("data").get("edits")) {
            if ("replace".equals(e.get("type").asText())) replace = e;
        }
        assertNotNull(replace, "no replace edit through the envelope");
        assertEquals(24, replace.get("startColumn").asInt(),
            "edit start column must survive the envelope");
        assertEquals(33, replace.get("endColumn").asInt(),
            "edit end column must survive the envelope");
        assertEquals("\"PREFIX_\"", replace.get("oldText").asText());
    }
}
