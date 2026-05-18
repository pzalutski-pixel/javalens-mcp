package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
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
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractConstantTool(() -> service);
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
        String expr = (String) getData(r).get("expressionText");
        assertTrue(expr.contains("PREFIX_"),
            "expressionText must contain PREFIX_; got: " + expr);
    }

    @Test
    @DisplayName("declaration string is `private static final String DEFAULT_PREFIX = \"PREFIX_\";`")
    void declaration_shape() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        String decl = (String) getData(r).get("declaration");
        assertNotNull(decl);
        assertTrue(decl.startsWith("private static final String DEFAULT_PREFIX = "),
            "declaration must start with `private static final String DEFAULT_PREFIX = `; got: " + decl);
        assertTrue(decl.endsWith(";"), "declaration must end with `;`; got: " + decl);
    }

    @Test
    @DisplayName("containingType reports RefactoringTarget")
    void containingType_reported() {
        ToolResponse r = tool.execute(prefixArgs("DEFAULT_PREFIX"));
        assertTrue(r.isSuccess());
        assertEquals("RefactoringTarget", getData(r).get("containingType"));
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
    }
}
