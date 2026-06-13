package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindAnnotationUsagesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindAnnotationUsagesTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindAnnotationUsagesTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getUsages(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds annotation usages")
    void findsAnnotationUsages() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.Override");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getUsages(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
        assertEquals("java.lang.Override", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.Override");
        args.put("maxResults", 1);
        assertTrue(getUsages(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires annotation")
    void requiresAnnotation() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown annotation")
    void handlesUnknownAnnotation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("@Marker resolves to exactly its seven placements at exact 0-based lines")
    void marker_findsAllSevenPlacementsExactly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // AnnotationUsages.java applies @Marker at exactly seven sites (0-based lines):
        // class(6), field markedField(9), constructor(12), method markedMethod(16),
        // parameter p(30), local 'local'(31), and type-use List<@Marker String>(35).
        // "All locations where the annotation is applied" — a dropped or extra usage is a bug.
        assertEquals(7, ((Number) data.get("totalCount")).intValue(),
            "@Marker is applied at exactly seven sites; got: " + getUsages(data));

        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (Map<String, Object> u : usagesOf(r)) {
            lines.add(((Number) u.get("line")).intValue());
        }
        assertEquals(java.util.Set.of(6, 9, 12, 16, 30, 31, 35), lines,
            "the seven @Marker sites are at these exact 0-based lines; got: " + usagesOf(r));
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> usagesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("Usage entries expose filePath, line, column, offset, length")
    void marker_usageEntriesIncludeLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> usages = usagesOf(r);
        assertFalse(usages.isEmpty(), "@Marker has documented usages; list must be non-empty");
        for (Map<String, Object> u : usages) {
            String fp = (String) u.get("filePath");
            assertNotNull(fp, "filePath must be present: " + u);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + u);
            assertTrue(((Number) u.get("line")).intValue() >= 0, "line >= 0; got: " + u);
            assertTrue(((Number) u.get("column")).intValue() >= 0, "column >= 0; got: " + u);
            assertTrue(((Number) u.get("offset")).intValue() >= 0, "offset >= 0; got: " + u);
            assertTrue(((Number) u.get("length")).intValue() > 0, "length > 0; got: " + u);
        }
    }

    @Test
    @DisplayName("Marker.java itself has no @Marker usages — all usages are in AnnotationUsages.java")
    void marker_isolation_allUsagesInAnnotationUsagesJava() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> u : usagesOf(r)) {
            String filePath = ((String) u.get("filePath")).replace('\\', '/');
            assertTrue(filePath.endsWith("AnnotationUsages.java"),
                "@Marker usages must come from AnnotationUsages.java only (not Marker.java declaration); got: " + filePath);
        }
    }

    @Test
    @DisplayName("Top-level @Repeatable @Label is found at both occurrences on repeatedLabels()")
    void label_repeatableAnnotation_findsBothOccurrences() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Label");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Top-level @Label annotation must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        List<Map<String, Object>> usages = usagesOf(r);
        // @Label("one") + @Label("two") on repeatedLabels() — exactly 2 annotation references.
        assertEquals(2, usages.size(),
            "Repeatable @Label has exactly 2 occurrences on repeatedLabels(); got: " + usages);
    }

    @Test
    @DisplayName("maxResults caps usages list and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncatedTrue() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> usages = usagesOf(r);
        assertEquals(2, usages.size(),
            "maxResults=2 must cap usages list to exactly 2; got: " + usages.size());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated(),
            "meta.truncated must be true when usages are capped below total");
    }

    @Test
    @DisplayName("Large maxResults returns all usages and meta.truncated=false")
    void maxResults_large_noTruncation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.AnnotationsFixture.Tag");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Tag has 2 usages; well under cap.
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.FALSE, meta.getTruncated(),
            "meta.truncated must be false when usages fit under maxResults");
    }

    @Test
    @DisplayName("totalUsages == usages.size()")
    void totalUsagesEqualsUsagesSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalCount")).intValue();
        assertEquals(total, usagesOf(r).size(),
            "totalUsages must equal usages list size in this response");
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: @Marker totalCount is exactly seven")
    void envelope_marker_totalCountIsSeven() {
        ObjectNode args = envelope.args();
        args.put("typeName", "com.example.Marker");
        args.put("maxResults", 100);
        JsonNode payload = envelope.payload("find_annotation_usages", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_annotation_usages failed through the envelope: " + payload);
        assertEquals(7, payload.get("data").get("totalCount").asInt(),
            "all seven @Marker usages must survive the JSON-RPC envelope");
    }
}
