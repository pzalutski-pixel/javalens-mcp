package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for SuggestImportsTool on simple-maven: import suggestion and relevance
 * ranking. Every expectation is the whole payload, through execute() and through the JSON-RPC
 * envelope. java.util ranks 100, other java. packages 70 and com. packages 40; equally relevant
 * candidates come by fully qualified name.
 */
class SuggestImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SuggestImportsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SuggestImportsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("finds JDK types with complete candidate structure and relevance ranking: java.util.List, then java.awt.List")
    void findsJdkTypesWithCompleteStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    @Test
    @DisplayName("maxResults=1 returns java.util.List, the most relevant of the two List candidates, and reports the cut")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 1);
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST)));
    }

    @Test
    @DisplayName("finds project types: Calculator is com.example.Calculator only")
    void findsProjectTypes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");
        assertPayload(args, suggestions("Calculator", 1, List.of(CALCULATOR)));
    }

    @Test
    @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        assertPayload(objectMapper.createObjectNode(), refusal("Invalid parameter 'typeName': Required"));
    }

    @Test
    @DisplayName("rejects blank typeName")
    void rejectsBlankTypeName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "   ");
        assertPayload(args, refusal("Invalid parameter 'typeName': Required"));
    }

    @Test
    @DisplayName("returns empty list for unknown type")
    void returnsEmptyForUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "NonExistentType12345");
        assertPayload(args, suggestions("NonExistentType12345", 0, List.of()));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Candidates sorted by relevance descending")
    void candidates_sortedByRelevanceDescending() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    @Test
    @DisplayName("totalCandidates == candidates.size() when nothing is cut")
    void totalCandidates_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 2);
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    @Test
    @DisplayName("java.util.List relevance (100) > java.awt.List relevance (70)")
    void relevance_ranking_javaUtilOverJavaAwt() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    @Test
    @DisplayName("Project-local Calculator has fixId=add_import:com.example.Calculator")
    void projectType_fixIdShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");
        assertPayload(args, suggestions("Calculator", 1, List.of(CALCULATOR)));
    }

    @Test
    @DisplayName("isInterface/isClass/isEnum flags are correct for known types")
    void kindFlags_correct() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    @Test
    @DisplayName("Negative maxResults returns INVALID_PARAMETER naming maxResults")
    void negativeMaxResults_returnsInvalidParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", -1);
        assertPayload(args, refusal("Invalid parameter 'maxResults': Must be >= 0; got: -1"));
    }

    @Test
    @DisplayName("Internal packages (sun.*, com.sun.*, *.internal.*, *.impl.*) are filtered: com.sun.tools.javac.util.List is no candidate")
    void internalPackages_filteredFromResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 100);
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    // ========== Exact ranking contract ==========

    @Test
    @DisplayName("java.util.List is the strict top-ranked candidate for \"List\"")
    void list_javaUtilListRanksFirst() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 100);
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: java.util.List ranks first for \"List\"")
    void envelope_list_javaUtilFirst() {
        ObjectNode args = envelope.args();
        args.put("typeName", "List");
        args.put("maxResults", 100);
        assertPayload(args, suggestions("List", 2, List.of(JAVA_UTIL_LIST, JAVA_AWT_LIST)));
    }

    // ========== exact payload helpers ==========

    /*
     * The JDK 21 types named List are java.util.List (java.base), java.awt.List (java.desktop)
     * and com.sun.tools.javac.util.List (jdk.compiler), which the com.sun. filter leaves out;
     * simple-maven declares none. The JDK declares no Calculator.
     */
    private static final Map<String, Object> JAVA_UTIL_LIST =
        candidate("java.util", "List", 100, true, false);
    private static final Map<String, Object> JAVA_AWT_LIST =
        candidate("java.awt", "List", 70, false, true);
    private static final Map<String, Object> CALCULATOR =
        candidate("com.example", "Calculator", 40, false, true);

    private static Map<String, Object> candidate(String pkg, String simpleName, int relevance,
                                                 boolean isInterface, boolean isClass) {
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("fullyQualifiedName", pkg + "." + simpleName);
        candidate.put("packageName", pkg);
        candidate.put("simpleName", simpleName);
        candidate.put("relevance", relevance);
        candidate.put("isInterface", isInterface);
        candidate.put("isClass", isClass);
        candidate.put("isEnum", false);
        candidate.put("fixId", "add_import:" + pkg + "." + simpleName);
        return candidate;
    }

    /** The success payload for {@code typeName}: {@code total} candidates, {@code candidates} listed. */
    private static Map<String, Object> suggestions(String typeName, int total, List<Map<String, Object>> candidates) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("typeName", typeName);
        data.put("totalCandidates", total);
        data.put("candidates", candidates);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", candidates.size());
        meta.put("truncated", candidates.size() < total);
        meta.put("suggestedNextTools", List.of(
            "apply_quick_fix with fixId to add the import",
            "get_quick_fixes to see all available fixes"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
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
     * the JSON-RPC envelope both equal {@code expected}.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = envelope.assertEnvelopeFidelity("suggest_imports", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
