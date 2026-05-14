package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindInstanceofChecksToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindInstanceofChecksTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindInstanceofChecksTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getChecks(Map<String, Object> d) { return (List<?>) d.get("instanceofChecks"); }

    @Test @DisplayName("finds instanceof checks")
    void findsInstanceofChecks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getChecks(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalChecks"));
        assertEquals("com.example.Calculator", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        assertTrue(getChecks(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("instanceof checks for Calculator: 2 in fixtures (performCasts + checkTypes)")
    void calculator_findsExactInstanceofCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns has TWO `instanceof Calculator` checks: one in performCasts (line 79)
        // and one in checkTypes (line 100). No other instanceof Calculator anywhere.
        assertEquals(2, ((Number) getData(r).get("totalChecks")).intValue(),
            "Expected exactly 2 instanceof Calculator checks; got: "
                + getData(r).get("totalChecks") + " (" + getChecks(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checksOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("instanceofChecks");
    }

    @Test
    @DisplayName("Check entry includes filePath, line, column, offset, length")
    void checkEntry_includesFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> checks = checksOf(r);
        assertFalse(checks.isEmpty());
        for (Map<String, Object> c : checks) {
            assertNotNull(c.get("filePath"), "filePath missing: " + c);
            assertNotNull(c.get("line"), "line missing: " + c);
            assertNotNull(c.get("column"), "column missing: " + c);
            assertNotNull(c.get("offset"), "offset missing: " + c);
            assertNotNull(c.get("length"), "length missing: " + c);
            String fp = ((String) c.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith("SearchPatterns.java"),
                "instanceof Calculator must come from SearchPatterns.java; got: " + fp);
        }
    }

    @Test
    @DisplayName("Suggestion field present when checks list is non-empty")
    void suggestion_presentOnNonEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertFalse(checksOf(r).isEmpty(), "Precondition: Calculator has instanceof checks");
        assertNotNull(data.get("suggestion"),
            "suggestion must be present when checks exist; data keys: " + data.keySet());
    }

    @Test
    @DisplayName("Suggestion absent and list empty for type with no instanceof checks (isolation)")
    void suggestion_absentWhenEmpty_animalNeverChecked() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Animal type must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals(0, checksOf(r).size(),
            "Animal is never checked via instanceof; got: " + checksOf(r));
        assertNull(data.get("suggestion"),
            "suggestion must be absent when no checks exist; got: " + data.get("suggestion"));
    }

    @Test
    @DisplayName("INSTANCEOF_TYPE_REFERENCE distinction: casts and plain refs NOT counted")
    void instanceofDistinction_excludesCastAndPlain() {
        // Calculator has 1 cast and 2 instanceof. The instanceof tool returns ONLY the 2
        // instanceof matches — confirmed by exactInstanceofCount. Here strengthen via line
        // numbers: 0-based lines 78 (performCasts) and 99 (checkTypes).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (Map<String, Object> c : checksOf(r)) {
            lines.add(((Number) c.get("line")).intValue());
        }
        assertEquals(java.util.Set.of(78, 99), lines,
            "instanceof Calculator must be on 0-based lines {78, 99}; got: " + lines);
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(1, checksOf(r).size(),
            "maxResults=1 must cap instanceof checks to exactly 1");
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
    void maxResults_large_noTruncation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.FALSE, meta.getTruncated());
    }

    @Test
    @DisplayName("totalChecks == instanceofChecks.size()")
    void totalChecks_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalChecks")).intValue();
        assertEquals(total, checksOf(r).size());
    }
}
