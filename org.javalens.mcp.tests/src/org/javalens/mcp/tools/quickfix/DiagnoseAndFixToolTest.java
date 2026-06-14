package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.DiagnoseAndFixTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins diagnose_and_fix (#22): one call returns the file's problems together
 * with the computed top-fix edits as editsByFile, and a clean file returns
 * empty sets. Strictly read-only — nothing is written.
 */
class DiagnoseAndFixToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DiagnoseAndFixTool tool;
    private EnvelopeHarness envelope;
    private String demoPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new DiagnoseAndFixTool(() -> service);
        envelope = new EnvelopeHarness(service);
        demoPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/DiagnoseFixDemo.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("unused import: the problem and its remove_import edit arrive in one response")
    @SuppressWarnings("unchecked")
    void unusedImport_problemAndEditCombined() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", demoPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        List<Map<String, Object>> problems = (List<Map<String, Object>>) data.get("problems");
        assertEquals(1, problems.size(),
            "exactly the unused-import warning; got: " + problems);
        Map<String, Object> problem = problems.get(0);
        assertTrue(String.valueOf(problem.get("message")).contains("java.util.Map"),
            "the problem must be the unused Map import; got: " + problem);
        assertTrue(String.valueOf(problem.get("fixId")).startsWith("remove_import"),
            "the chosen fix must be remove_import; got: " + problem);

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        assertEquals(1, editsByFile.size(),
            "edits for exactly the diagnosed file; got: " + editsByFile.keySet());
        List<Map<String, Object>> edits = editsByFile.values().iterator().next();
        assertFalse(edits.isEmpty(), "the remove_import edit must be computed; got: " + edits);
        // The delete edit carries offsets (apply_quick_fix's shape); verify the
        // range actually covers the unused import in the source.
        Map<String, Object> delete = edits.get(0);
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(demoPath));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        int start = ((Number) delete.get("startOffset")).intValue();
        int end = ((Number) delete.get("endOffset")).intValue();
        assertTrue(source.substring(start, end).contains("java.util.Map"),
            "the deleted range must cover the Map import; got range [" + start + "," + end
                + ") = " + source.substring(start, end));

        assertTrue(((Number) data.get("totalEdits")).intValue() >= 1);
    }

    @Test
    @DisplayName("a clean file returns empty problems and edits")
    @SuppressWarnings("unchecked")
    void cleanFile_returnsEmptySets() {
        String cleanPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierChild.java").toString();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", cleanPath);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("problemCount")).intValue(),
            "clean file has no problems; got: " + data.get("problems"));
        assertEquals(0, ((Number) data.get("totalEdits")).intValue());
        assertTrue(((Map<String, ?>) data.get("editsByFile")).isEmpty(),
            "clean file yields an empty edit set");
    }

    @Test
    @DisplayName("missing filePath and unknown file are rejected")
    void invalidInputs_rejected() {
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());

        ObjectNode unknown = mapper.createObjectNode();
        unknown.put("filePath", "/nonexistent/Nope.java");
        assertFalse(tool.execute(unknown).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: the unused Map import and its remove_import edit arrive in one response")
    void envelope_unusedImport_problemAndEditCombined() {
        ObjectNode args = envelope.args();
        args.put("filePath", demoPath);
        JsonNode payload = envelope.payload("diagnose_and_fix", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "diagnose_and_fix failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        JsonNode problems = data.get("problems");
        assertEquals(1, problems.size(), "exactly the unused-import warning through the envelope");
        JsonNode problem = problems.get(0);
        assertTrue(problem.get("message").asText().contains("java.util.Map"),
            "the problem must be the unused Map import through the envelope; got: " + problem);
        assertTrue(problem.get("fixId").asText().startsWith("remove_import"),
            "the chosen fix must be remove_import through the envelope; got: " + problem);
        assertEquals(1, data.get("editsByFile").size(),
            "edits for exactly the diagnosed file through the envelope; got: " + data.get("editsByFile"));
        assertTrue(data.get("totalEdits").asInt() >= 1, "at least one edit through the envelope");
    }
}
