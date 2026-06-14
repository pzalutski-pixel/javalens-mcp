package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins apply_cleanup, which drives JDT's own clean-up operations headlessly.
 * The convert_loops clean-up rewrites an index-based for loop as an enhanced
 * for loop; the tool returns the rewritten source without writing the file.
 */
class ApplyCleanupToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private EnvelopeHarness envelope;
    private String loopDemoPath;
    private String flexibleCtorPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new ApplyCleanupTool(() -> service);
        envelope = new EnvelopeHarness(service);
        loopDemoPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/LoopDemo.java").toString();
        flexibleCtorPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/FlexibleCtorDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("convert_loops rewrites an index-based for loop as an enhanced for loop")
    void convertLoops_rewritesIndexedForLoop() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", loopDemoPath);
        args.put("cleanupId", "convert_loops");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("changed"),
            "the indexed loop is convertible; got: " + data);

        String source = (String) data.get("source");
        assertTrue(source.contains(" : nums)"),
            "rewritten source must contain an enhanced for over nums; got:\n" + source);
        assertFalse(source.contains("nums.get(i)"),
            "the index access must be gone after conversion; got:\n" + source);
        assertFalse(source.contains("i < nums.size()"),
            "the index condition must be gone after conversion; got:\n" + source);
    }

    @Test
    @DisplayName("no convertible loop: changed=false, source returned unchanged")
    void noConvertibleLoop_reportsUnchanged() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", flexibleCtorPath);
        args.put("cleanupId", "convert_loops");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());
        assertEquals(Boolean.FALSE, getData(r).get("changed"),
            "FlexibleCtorDemo has no loops; nothing should change");
    }

    @Test
    @DisplayName("unknown cleanupId is rejected")
    void unknownCleanupId_rejected() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", loopDemoPath);
        args.put("cleanupId", "no_such_cleanup");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: convert_loops rewrites the indexed loop as an enhanced for over nums")
    void envelope_convertLoops_rewritesIndexedForLoop() {
        ObjectNode args = envelope.args();
        args.put("filePath", loopDemoPath);
        args.put("cleanupId", "convert_loops");
        JsonNode payload = envelope.payload("apply_cleanup", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "apply_cleanup failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertTrue(data.get("changed").asBoolean(), "the indexed loop is convertible through the envelope");
        String source = data.get("source").asText();
        assertTrue(source.contains(" : nums)"),
            "rewritten source must contain an enhanced for over nums through the envelope; got:\n" + source);
        assertFalse(source.contains("nums.get(i)"),
            "the index access must be gone after conversion through the envelope; got:\n" + source);
        assertFalse(source.contains("i < nums.size()"),
            "the index condition must be gone after conversion through the envelope; got:\n" + source);
    }
}
