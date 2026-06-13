package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindThrowsDeclarationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindThrowsDeclarationsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindThrowsDeclarationsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getDeclarations(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds throws declarations")
    void findsThrowsDeclarations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getDeclarations(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 1);
        assertTrue(getDeclarations(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires exceptionType")
    void requiresExceptionType() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown exception type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("IOException throws declarations: SearchPatterns.readFile, SearchPatterns.riskyOperation, ControlFlowPatterns.tryWithResources")
    void ioException_findsExpectedDeclarations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns.readFile throws IOException, riskyOperation throws IOException,
        // ControlFlowPatterns.tryWithResources throws IOException. Expect at least 3 matches.
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertTrue(total >= 3,
            "Expected at least 3 IOException throws declarations; got: "
                + total + " (" + getDeclarations(getData(r)) + ")");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> declsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("IOException throws declarations: exactly 5 across all fixtures")
    void ioException_exactCountAndFileSet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> decls = declsOf(r);
        // Throws-clause occurrences across fixtures:
        //  - SearchPatterns.readFile               (throws IOException)
        //  - SearchPatterns.riskyOperation         (throws IOException, IllegalArgumentException)
        //  - ControlFlowPatterns.tryWithResources  (throws IOException)
        //  - TypeKindsFixture.throwingHelper       (throws java.io.IOException)
        //  - JavadocTagFixture.documentedMethod    (throws IOException)
        assertEquals(5, decls.size(),
            "IOException must be declared in exactly 5 throws clauses; got: " + decls);

        java.util.Set<String> files = new java.util.HashSet<>();
        for (Map<String, Object> d : decls) {
            String fp = ((String) d.get("filePath")).replace('\\', '/');
            files.add(fp.substring(fp.lastIndexOf('/') + 1));
        }
        assertEquals(
            java.util.Set.of("SearchPatterns.java", "ControlFlowPatterns.java",
                "TypeKindsFixture.java", "JavadocTagFixture.java"),
            files,
            "IOException throws span 4 files; got: " + files);
    }

    @Test
    @DisplayName("Each declaration entry includes filePath, line, column, offset, length, context")
    void declarationEntries_includeFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> decls = declsOf(r);
        assertFalse(decls.isEmpty());
        for (Map<String, Object> d : decls) {
            String fp = (String) d.get("filePath");
            assertNotNull(fp, "filePath missing: " + d);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + d);
            assertTrue(((Number) d.get("line")).intValue() >= 0, "line >= 0; got: " + d);
            assertTrue(((Number) d.get("column")).intValue() >= 0, "column >= 0; got: " + d);
            assertTrue(((Number) d.get("offset")).intValue() >= 0, "offset >= 0; got: " + d);
            assertTrue(((Number) d.get("length")).intValue() > 0, "length > 0; got: " + d);
            String ctx = (String) d.get("context");
            assertNotNull(ctx, "context missing: " + d);
            assertFalse(ctx.isBlank(), "context non-blank; got: " + d);
        }
    }

    @Test
    @DisplayName("THROWS_CLAUSE_TYPE_REFERENCE distinction: javadoc @throws and `throw new` instantiations are NOT counted")
    void throwsClauseDistinction_excludesNonClauseUsages() {
        // TypeKindsFixture has `@throws java.lang.IllegalArgumentException` in javadoc on
        // richlyDocumentedMethod (line ~109 1-based); should NOT count as throws clause.
        // SearchPatterns.validateInput has `throw new RuntimeException(...)` — instantiation, not clause.
        // The IllegalArgumentException throws clauses are exactly:
        //  - SearchPatterns.riskyOperation (multi-throws)
        //  - InterfaceExtractTarget.validate
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.IllegalArgumentException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> decls = declsOf(r);
        assertEquals(2, decls.size(),
            "IllegalArgumentException is declared in exactly 2 throws clauses; got: " + decls);
    }

    @Test
    @DisplayName("Multi-throws clause: each exception in `throws A, B` resolves correctly")
    void multiThrowsClause_resolvesEachException() {
        // riskyOperation has `throws IOException, IllegalArgumentException`. Both queries
        // must find a declaration on the SAME source line (1-based 124, 0-based 123).
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);
        ToolResponse rIo = tool.execute(args);
        assertTrue(rIo.isSuccess());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("typeName", "java.lang.IllegalArgumentException");
        args2.put("maxResults", 100);
        ToolResponse rIae = tool.execute(args2);
        assertTrue(rIae.isSuccess());

        boolean ioHitsRisky = declsOf(rIo).stream()
            .anyMatch(d -> ((Number) d.get("line")).intValue() == 123
                && ((String) d.get("filePath")).replace('\\', '/').endsWith("SearchPatterns.java"));
        boolean iaeHitsRisky = declsOf(rIae).stream()
            .anyMatch(d -> ((Number) d.get("line")).intValue() == 123
                && ((String) d.get("filePath")).replace('\\', '/').endsWith("SearchPatterns.java"));
        assertTrue(ioHitsRisky && iaeHitsRisky,
            "Both IOException and IllegalArgumentException must resolve on SearchPatterns.riskyOperation line; "
                + "ioHit=" + ioHitsRisky + " iaeHit=" + iaeHitsRisky);
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, declsOf(r).size(),
            "maxResults=2 must cap declarations to 2");
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: returns all and meta.truncated=false")
    void maxResults_large_noTruncation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.FALSE, meta.getTruncated());
    }

    @Test
    @DisplayName("totalDeclarations == throwsDeclarations.size()")
    void totalDeclarations_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, declsOf(r).size());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: IOException declared in exactly 5 throws clauses across 4 files")
    void envelope_ioException_exactCount() {
        ObjectNode args = envelope.args();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);
        JsonNode payload = envelope.payload("find_throws_declarations", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_throws_declarations failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(5, data.get("totalCount").asInt(), "exactly five throws clauses through the envelope");
        java.util.Set<String> files = new java.util.TreeSet<>();
        for (JsonNode d : data.get("locations")) {
            String fp = d.get("filePath").asText().replace('\\', '/');
            files.add(fp.substring(fp.lastIndexOf('/') + 1));
        }
        assertEquals(java.util.Set.of("SearchPatterns.java", "ControlFlowPatterns.java",
                "TypeKindsFixture.java", "JavadocTagFixture.java"), files,
            "the throws clauses span exactly four files through the envelope");
    }
}
