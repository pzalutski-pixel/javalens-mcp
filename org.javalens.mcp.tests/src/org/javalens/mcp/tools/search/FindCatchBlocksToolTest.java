package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
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
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCatchBlocksTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getCatchBlocks(Map<String, Object> d) { return (List<?>) d.get("locations"); }

    @Test @DisplayName("finds catch blocks")
    void findsCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getCatchBlocks(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCount"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 1);
        assertTrue(getCatchBlocks(getData(tool.execute(args))).size() <= 1);
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
    @DisplayName("IOException catch blocks: SearchPatterns.handleExceptions has two; ControlFlowPatterns has none directly catching IOException")
    void ioException_findsExpectedCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns.handleExceptions has two catch (IOException ...) blocks
        // plus ControlFlowPatterns.multiCatch's `catch (NumberFormatException | IOException e)`.
        // Total >= 3.
        int total = ((Number) getData(r).get("totalCount")).intValue();
        assertTrue(total >= 3,
            "Expected at least 3 IOException catch blocks; got: "
                + total + " (" + getCatchBlocks(getData(r)) + ")");
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
    @DisplayName("Catch-block entries include filePath, line, column, offset, length, context")
    void catchBlockEntries_includeFullLocation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "java.io.IOException");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> blocks = blocksOf(r);
        assertFalse(blocks.isEmpty());
        for (Map<String, Object> b : blocks) {
            String fp = (String) b.get("filePath");
            assertNotNull(fp, "filePath missing: " + b);
            assertTrue(fp.endsWith(".java"), "filePath ends with .java; got: " + b);
            assertTrue(((Number) b.get("line")).intValue() >= 0, "line >= 0; got: " + b);
            assertTrue(((Number) b.get("column")).intValue() >= 0, "column >= 0; got: " + b);
            assertTrue(((Number) b.get("offset")).intValue() >= 0, "offset >= 0; got: " + b);
            assertTrue(((Number) b.get("length")).intValue() > 0, "length > 0; got: " + b);
            String ctx = (String) b.get("context");
            assertNotNull(ctx, "context missing: " + b);
            assertFalse(ctx.isBlank(), "context non-blank; got: " + b);
        }
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
}
