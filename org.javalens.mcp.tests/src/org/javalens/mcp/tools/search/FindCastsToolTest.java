package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCastsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCastsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCastsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCastsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getCasts(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds casts to project type")
    void findsCastsToProjectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getCasts(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
        assertEquals("com.example.Calculator", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        assertTrue(getCasts(getData(tool.execute(args))).size() <= 1);
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
    @DisplayName("casts to Calculator: exactly 1 cast in SearchPatterns.performCasts")
    void calculator_findsExactCastCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns.performCasts has `(Calculator) obj` exactly once. No other casts to
        // Calculator anywhere in the fixture.
        assertEquals(1, ((Number) getData(r).get("totalCount")).intValue(),
            "Expected exactly 1 (Calculator) cast in fixtures; got: "
                + getData(r).get("totalCount") + " (" + getCasts(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("Cast entry includes filePath, line, column, offset, length")
    void castEntry_includesFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> casts = castsOf(r);
        assertFalse(casts.isEmpty());
        for (Map<String, Object> c : casts) {
            assertNotNull(c.get("filePath"), "filePath missing: " + c);
            assertNotNull(c.get("line"), "line missing: " + c);
            assertNotNull(c.get("column"), "column missing: " + c);
            assertNotNull(c.get("offset"), "offset missing: " + c);
            assertNotNull(c.get("length"), "length missing: " + c);
            String fp = ((String) c.get("filePath")).replace('\\', '/');
            assertTrue(fp.endsWith("SearchPatterns.java"),
                "(Calculator) cast must come from SearchPatterns.java; got: " + fp);
        }
    }

    @Test
    @DisplayName("Warning field present when casts list is non-empty")
    void warning_presentOnNonEmpty() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertFalse(castsOf(r).isEmpty(), "Precondition: Calculator has casts");
        assertNotNull(data.get("advice"),
            "advice must be present when casts exist; data keys: " + data.keySet());
    }

    @Test
    @DisplayName("Warning field absent and list empty for a type with no casts (isolation)")
    void warning_absentWhenEmpty_animalNeverCast() {
        // Animal is a regular class — never cast anywhere in the fixtures.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Animal");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "Animal type must resolve; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> data = getData(r);
        assertEquals(0, castsOf(r).size(),
            "Animal is never cast; cast list must be empty; got: " + castsOf(r));
        assertNull(data.get("advice"),
            "advice must be absent when no casts exist; got: " + data.get("advice"));
    }

    @Test
    @DisplayName("CAST_TYPE_REFERENCE distinction: plain references and instanceof checks NOT counted")
    void castDistinction_excludesPlainAndInstanceof() {
        // Calculator appears as instanceof check (line 79) and method references throughout
        // the fixtures. Only the actual cast `(Calculator) obj` on line 80 should be counted.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Exactly 1 cast — proven by the existing exactCastCount test; here we strengthen
        // the assertion by checking the cast is on the cast line, not the instanceof line.
        for (Map<String, Object> c : castsOf(r)) {
            int line = ((Number) c.get("line")).intValue();
            // 0-based line of `(Calculator) obj;` is 79 (1-based line 80).
            assertEquals(79, line,
                "(Calculator) cast must be on line 79 (0-based) only; got: " + c);
        }
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        // SearchPatterns.performCasts has 1 (String) cast; StringCasts.java adds 3 more.
        // Total = 4, well above any small cap. java.lang.String is project-scoped here
        // (project scope excludes JDK), so search returns only fixture occurrences.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.String");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, castsOf(r).size(),
            "maxResults=2 must cap cast list to exactly 2; got: " + castsOf(r));
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
    void maxResults_large_noTruncation() {
        // Calculator has exactly 1 cast across the fixtures. With maxResults far above
        // total, truncated must be false.
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
    @DisplayName("totalCount == locations.size()")
    void totalCasts_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, castsOf(r).size());
    }
}
