package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SuggestImportsTool.
 * Tests import suggestion and relevance ranking.
 */
class SuggestImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SuggestImportsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SuggestImportsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCandidates(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("candidates");
    }

    @Test
    @DisplayName("finds JDK types with complete candidate structure and relevance ranking")
    void findsJdkTypesWithCompleteStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify summary fields
        assertEquals("List", data.get("typeName"));
        int totalCandidates = ((Number) data.get("totalCandidates")).intValue();
        assertTrue(totalCandidates > 0, "List has candidate imports; got: " + data);

        // Verify candidates structure
        List<Map<String, Object>> candidates = getCandidates(data);
        assertEquals(totalCandidates, candidates.size(),
            "totalCandidates must equal candidates list size; got: " + data);

        // Verify java.util.List is found
        assertTrue(candidates.stream()
            .anyMatch(c -> "java.util.List".equals(c.get("fullyQualifiedName"))));

        // Verify first candidate has all required fields with valid values
        Map<String, Object> first = candidates.get(0);
        String fqn = (String) first.get("fullyQualifiedName");
        assertNotNull(fqn, "fullyQualifiedName missing");
        assertTrue(fqn.contains("."), "FQN must include package; got: " + first);
        String pkg = (String) first.get("packageName");
        assertNotNull(pkg, "packageName missing");
        assertTrue(fqn.startsWith(pkg + "."),
            "FQN must start with packageName; got: " + first);
        assertTrue(((Number) first.get("relevance")).intValue() >= 0,
            "relevance >= 0; got: " + first);
        assertTrue(first.get("isInterface") instanceof Boolean,
            "isInterface must be Boolean; got: " + first);
        assertTrue(first.get("isClass") instanceof Boolean,
            "isClass must be Boolean; got: " + first);
        assertTrue(first.get("isEnum") instanceof Boolean,
            "isEnum must be Boolean; got: " + first);

        // Verify fixId format
        String fixId = (String) first.get("fixId");
        assertNotNull(fixId);
        assertTrue(fixId.startsWith("add_import:"));

        // Verify relevance ranking: java.util.List should come before java.awt types
        int utilIndex = -1;
        int awtIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String candFqn = (String) candidates.get(i).get("fullyQualifiedName");
            if ("java.util.List".equals(candFqn)) {
                utilIndex = i;
            } else if (candFqn != null && candFqn.startsWith("java.awt.")) {
                awtIndex = i;
            }
        }
        if (utilIndex >= 0 && awtIndex >= 0) {
            assertTrue(utilIndex < awtIndex, "java.util.List should rank higher than java.awt types");
        }
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
    @DisplayName("finds project types")
    void findsProjectTypes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertTrue(candidates.stream()
            .anyMatch(c -> "com.example.Calculator".equals(c.get("fullyQualifiedName"))));
    }

    @Test
    @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'typeName': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("rejects blank typeName")
    void rejectsBlankTypeName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "   ");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertEquals("Invalid parameter 'typeName': Required", response.getError().getMessage());
    }

    @Test
    @DisplayName("returns empty list for unknown type")
    void returnsEmptyForUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "NonExistentType12345");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertEquals(0, candidates.size());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Candidates sorted by relevance descending")
    void candidates_sortedByRelevanceDescending() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> cands = getCandidates(getData(r));
        for (int i = 1; i < cands.size(); i++) {
            int prev = ((Number) cands.get(i - 1).get("relevance")).intValue();
            int curr = ((Number) cands.get(i).get("relevance")).intValue();
            assertTrue(prev >= curr,
                "Candidates must be sorted by relevance descending; got prev=" + prev + " curr=" + curr);
        }
    }

    @Test
    @DisplayName("totalCandidates == candidates.size()")
    void totalCandidates_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalCandidates")).intValue();
        assertEquals(total, getCandidates(data).size());
    }

    @Test
    @DisplayName("java.util.List relevance > java.awt.List relevance")
    void relevance_ranking_javaUtilOverJavaAwt() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> cands = getCandidates(getData(r));
        int utilRel = -1, awtRel = -1;
        for (Map<String, Object> c : cands) {
            String fqn = (String) c.get("fullyQualifiedName");
            int rel = ((Number) c.get("relevance")).intValue();
            if ("java.util.List".equals(fqn)) utilRel = rel;
            else if ("java.awt.List".equals(fqn)) awtRel = rel;
        }
        if (utilRel >= 0 && awtRel >= 0) {
            assertTrue(utilRel > awtRel,
                "java.util.List must rank higher than java.awt.List; got util=" + utilRel + " awt=" + awtRel);
        }
    }

    @Test
    @DisplayName("Project-local Calculator has fixId=add_import:com.example.Calculator")
    void projectType_fixIdShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> calc = getCandidates(getData(r)).stream()
            .filter(c -> "com.example.Calculator".equals(c.get("fullyQualifiedName")))
            .findFirst().orElseThrow();
        assertEquals("add_import:com.example.Calculator", calc.get("fixId"));
        assertEquals("com.example", calc.get("packageName"));
        assertEquals("Calculator", calc.get("simpleName"));
    }

    @Test
    @DisplayName("isInterface/isClass/isEnum flags are correct for known types")
    void kindFlags_correct() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> jUtilList = getCandidates(getData(r)).stream()
            .filter(c -> "java.util.List".equals(c.get("fullyQualifiedName")))
            .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, jUtilList.get("isInterface"),
            "java.util.List is an interface");
        assertEquals(Boolean.FALSE, jUtilList.get("isClass"));
        assertEquals(Boolean.FALSE, jUtilList.get("isEnum"));
    }

    @Test
    @DisplayName("Negative maxResults returns INVALID_PARAMETER naming maxResults")
    void negativeMaxResults_returnsInvalidParameter() {
        // Source: `if (maxResults < 0) return invalidParameter("maxResults", ...)`.
        // Pin the strict-rejection branch.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertEquals("Invalid parameter 'maxResults': Must be >= 0; got: -1",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("Internal packages (sun.*, com.sun.*, *.internal.*, *.impl.*) are filtered from candidates")
    void internalPackages_filteredFromResults() {
        // Source skip-list in acceptTypeNameMatch:
        //   if (packageName.contains(".internal.") || .contains(".impl.") ||
        //       packageName.startsWith("sun.") || packageName.startsWith("com.sun.")) return;
        // Search a common JDK name; assert NO candidate's packageName is in the skip-list.
        // Without this guard the LLM consumer would see implementation-detail types in the
        // import suggestion list — a real correctness problem.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : getCandidates(getData(r))) {
            String pkg = (String) c.get("packageName");
            assertNotNull(pkg, "packageName missing on candidate: " + c);
            assertFalse(pkg.startsWith("sun."),
                "sun.* packages must be filtered; got: " + c);
            assertFalse(pkg.startsWith("com.sun."),
                "com.sun.* packages must be filtered; got: " + c);
            assertFalse(pkg.contains(".internal."),
                "*.internal.* packages must be filtered; got: " + c);
            assertFalse(pkg.contains(".impl."),
                "*.impl.* packages must be filtered; got: " + c);
        }
    }

    // ========== Exact ranking contract ==========

    @Test
    @DisplayName("java.util.List is the strict top-ranked candidate for \"List\"")
    void list_javaUtilListRanksFirst() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> cands = getCandidates(getData(r));
        assertFalse(cands.isEmpty(), "List resolves to at least java.util.List");

        // calculateRelevance gives package java.util a score of 100 — the unique
        // table maximum — so java.util.List must rank first regardless of which
        // other List types (java.awt.List, etc.) the JDK module scope surfaces.
        // The description's "java.util ranked higher than java.awt" contract.
        assertEquals("java.util.List", cands.get(0).get("fullyQualifiedName"),
            "java.util.List must rank first; got: " + cands);
        int top = ((Number) cands.get(0).get("relevance")).intValue();
        for (int i = 1; i < cands.size(); i++) {
            assertTrue(((Number) cands.get(i).get("relevance")).intValue() < top,
                "java.util.List must be the strict relevance maximum; got: " + cands);
        }
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: java.util.List ranks first for \"List\"")
    void envelope_list_javaUtilFirst() {
        ObjectNode args = envelope.args();
        args.put("typeName", "List");
        args.put("maxResults", 100);
        JsonNode payload = envelope.assertEnvelopeFidelity("suggest_imports", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "suggest_imports failed through the envelope: " + payload);
        JsonNode cands = payload.get("data").get("candidates");
        assertTrue(cands.size() > 0, "at least java.util.List");
        assertEquals("java.util.List", cands.get(0).get("fullyQualifiedName").asText(),
            "the top-ranked import must survive the JSON-RPC envelope");
    }

    // ========== exact payload helpers ==========

    /*
     * The JDK 21 types named List are java.util.List (java.base), java.awt.List (java.desktop)
     * and com.sun.tools.javac.util.List (jdk.compiler), which the com.sun. filter leaves out;
     * simple-maven declares none.
     */
    private static final Map<String, Object> JAVA_UTIL_LIST =
        candidate("java.util", "List", 100, true, false);
    private static final Map<String, Object> JAVA_AWT_LIST =
        candidate("java.awt", "List", 70, false, true);

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
