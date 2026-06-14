package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCatchBlocksToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCatchBlocksTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCatchBlocksTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getCatchBlocks(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds the exact 3 IOException catch blocks")
    void findsCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(3, ((Number) getData(r).get("totalCount")).intValue());
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 1);
        // IOException is caught in exactly 3 blocks; maxResults=1 caps to exactly 1.
        assertEquals(1, getCatchBlocks(getData(tool.execute(args))).size());
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
    private List<Map<String, Object>> blocksOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("locations");
    }

    @Test
    @DisplayName("IOException catch blocks: exactly 3 across SearchPatterns (x2) and ControlFlowPatterns multi-catch")
    void ioException_exactCountAndFileSet() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> blocks = blocksOf(r);
        // Fixtures grep showed exactly 3 catch clauses naming IOException:
        //  - SearchPatterns.java:149  catch (IOException e)
        //  - SearchPatterns.java:165  catch (IOException e)
        //  - ControlFlowPatterns.java:124  catch (NumberFormatException | IOException e)
        assertEquals(3, blocks.size(),
            "IOException is caught in exactly 3 catch clauses; got: " + blocks);

        java.util.Set<String> files = new java.util.HashSet<>();
        for (Map<String, Object> b : blocks) {
            String fp = ((String) b.get("filePath")).replace('\\', '/');
            files.add(fp.substring(fp.lastIndexOf('/') + 1));
        }
        assertEquals(
            java.util.Set.of("SearchPatterns.java", "ControlFlowPatterns.java"),
            files,
            "Catch blocks span exactly 2 files; got: " + files);
    }

    @Test
    @DisplayName("Each IOException catch entry: exact length 11 and 0-based line in {123,148,164}")
    void catchBlockEntries_includeFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> blocks = blocksOf(r);
        assertEquals(3, blocks.size(), "exactly three IOException catch blocks; got: " + blocks);
        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        for (Map<String, Object> b : blocks) {
            lines.add(((Number) b.get("line")).intValue());
            assertTrue(((String) b.get("filePath")).endsWith(".java"), "filePath ends with .java; got: " + b);
            // The CATCH type reference "IOException" is length 11 at every site (incl. the
            // multi-catch union). line + file fully anchor each entry's location.
            assertEquals(11, ((Number) b.get("length")).intValue(), "\"IOException\".length(); got: " + b);
        }
        // SearchPatterns.java 0-based 148 & 164; ControlFlowPatterns.java multi-catch 0-based 123.
        assertEquals(java.util.Set.of(123, 148, 164), lines, "the three 0-based catch lines; got: " + lines);
    }

    @Test
    @DisplayName("CATCH_TYPE_REFERENCE distinction: throws clauses, instantiations, and instanceof are NOT counted")
    void catchClauseDistinction_excludesNonCatchUsages() {
        // IllegalStateException is used in a catch clause (ControlFlowPatterns nested try)
        // and nowhere else in throws/instanceof/instantiations in our fixtures. Exactly 1.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.lang.IllegalStateException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> blocks = blocksOf(r);
        assertEquals(1, blocks.size(),
            "IllegalStateException is caught exactly once; got: " + blocks);
    }

    @Test
    @DisplayName("Multi-catch union: each exception in `catch (A | B e)` resolves to the same line")
    void multiCatch_resolvesEachException() {
        // ControlFlowPatterns.multiCatch: `catch (NumberFormatException | IOException e)`
        // at 0-based line 123.
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("typeName", "java.io.IOException");
        args1.put("maxResults", 100);
        ToolResponse rIo = tool.execute(args1);
        assertTrue(rIo.isSuccess());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("typeName", "java.lang.NumberFormatException");
        args2.put("maxResults", 100);
        ToolResponse rNfe = tool.execute(args2);
        assertTrue(rNfe.isSuccess());

        boolean ioHitsMulti = blocksOf(rIo).stream()
            .anyMatch(b -> ((Number) b.get("line")).intValue() == 123
                && ((String) b.get("filePath")).replace('\\', '/').endsWith("ControlFlowPatterns.java"));
        boolean nfeHitsMulti = blocksOf(rNfe).stream()
            .anyMatch(b -> ((Number) b.get("line")).intValue() == 123
                && ((String) b.get("filePath")).replace('\\', '/').endsWith("ControlFlowPatterns.java"));
        assertTrue(ioHitsMulti && nfeHitsMulti,
            "Both IOException and NumberFormatException must resolve on ControlFlowPatterns multi-catch line; "
                + "ioHit=" + ioHitsMulti + " nfeHit=" + nfeHitsMulti);
    }

    @Test
    @DisplayName("maxResults caps and sets meta.truncated=true")
    void maxResults_capsAndSetsTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, blocksOf(r).size(),
            "maxResults=2 must cap catch blocks to 2");
        org.javalens.mcp.models.ResponseMeta meta = r.getMeta();
        assertNotNull(meta);
        assertEquals(Boolean.TRUE, meta.getTruncated());
    }

    @Test
    @DisplayName("Large maxResults: meta.truncated=false")
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
    @DisplayName("totalCatchBlocks == catchBlocks.size()")
    void totalCatchBlocks_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertEquals(total, blocksOf(r).size());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: IOException is caught in exactly 3 blocks across 2 files")
    void envelope_ioException_exactCount() {
        ObjectNode args = envelope.args();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);
        JsonNode payload = envelope.assertEnvelopeFidelity("find_catch_blocks", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_catch_blocks failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(3, data.get("totalCount").asInt(), "exactly three catch blocks through the envelope");
        java.util.Set<String> files = new java.util.TreeSet<>();
        for (JsonNode b : data.get("locations")) {
            String fp = b.get("filePath").asText().replace('\\', '/');
            files.add(fp.substring(fp.lastIndexOf('/') + 1));
        }
        assertEquals(java.util.Set.of("SearchPatterns.java", "ControlFlowPatterns.java"), files,
            "catch blocks span exactly two files through the envelope");
    }
}
