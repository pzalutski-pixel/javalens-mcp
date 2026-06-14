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

    @Test @DisplayName("finds the exact 5 IOException throws declarations")
    void findsThrowsDeclarations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(5, ((Number) getData(r).get("totalCount")).intValue());
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 1);
        // IOException is declared in exactly 5 throws clauses; maxResults=1 caps to exactly 1.
        assertEquals(1, getDeclarations(getData(tool.execute(args))).size());
    }

    @Test @DisplayName("missing typeName is rejected with INVALID_PARAMETER")
    void requiresExceptionType() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().toLowerCase().contains("required"),
            "message must explain typeName is required; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("unknown type is rejected with SYMBOL_NOT_FOUND naming the type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("com.nonexistent.X"),
            "message must name the unresolved type; got: " + r.getError().getMessage());
    }

    @Test @DisplayName("negative maxResults is rejected with INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", -1);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains(">= 0"),
            "message must explain the bound; got: " + r.getError().getMessage());
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
    @DisplayName("Each IOException throws entry: length is 11 (simple) or 19 (FQN java.io.IOException)")
    void declarationEntries_includeFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> decls = declsOf(r);
        assertEquals(5, decls.size(), "exactly five IOException throws clauses; got: " + decls);
        for (Map<String, Object> d : decls) {
            assertTrue(((String) d.get("filePath")).endsWith(".java"), "filePath ends with .java; got: " + d);
            // Four sites declare the simple name `IOException` (length 11); TypeKindsFixture
            // declares the fully-qualified `java.io.IOException` (length 19). Exact closed set.
            int len = ((Number) d.get("length")).intValue();
            assertTrue(len == 11 || len == 19,
                "throws type reference length must be 11 (simple) or 19 (FQN); got: " + d);
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
        JsonNode payload = envelope.assertEnvelopeFidelity("find_throws_declarations", args);

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
