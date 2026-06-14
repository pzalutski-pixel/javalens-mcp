package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractSuperclassTool;
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
 * Pins extract_superclass: pulling HierChild.liftable() into a NEW superclass
 * yields the new class as file content (createdFiles) plus edits making
 * HierChild extend it and dropping the moved member.
 */
class ExtractSuperclassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractSuperclassTool tool;
    private EnvelopeHarness envelope;
    private String childPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new ExtractSuperclassTool(() -> service);
        envelope = new EnvelopeHarness(service);
        childPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierChild.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("extracting liftable() creates the new superclass file and rewires HierChild")
    @SuppressWarnings("unchecked")
    void extractSuperclass_createsFileAndRewires() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", childPath);
        args.put("line", 6);    // 0-based; "    public int liftable() {"
        args.put("column", 15);
        args.put("superclassName", "LiftBase");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("LiftBase", data.get("superclassName"));

        List<Map<String, String>> createdFiles = (List<Map<String, String>>) data.get("createdFiles");
        assertEquals(1, createdFiles.size(),
            "exactly one new file (the superclass); got: " + createdFiles);
        String content = createdFiles.get(0).get("content");
        assertTrue(content.contains("class LiftBase") && content.contains("liftable"),
            "new file must declare LiftBase with the moved member; got:\n" + content);

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        String childNew = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith("HierChild.java"))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertTrue(childNew.contains("LiftBase"),
            "HierChild must be rewired to extend LiftBase; got: " + childNew);
    }

    @Test
    @DisplayName("invalid superclass name and missing params are rejected")
    void invalidInputs_rejected() {
        ObjectNode badName = mapper.createObjectNode();
        badName.put("filePath", childPath);
        badName.put("line", 6);
        badName.put("column", 15);
        badName.put("superclassName", "123Bad");
        assertFalse(tool.execute(badName).isSuccess());

        ObjectNode noName = mapper.createObjectNode();
        noName.put("filePath", childPath);
        noName.put("line", 6);
        noName.put("column", 15);
        assertFalse(tool.execute(noName).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: extracting liftable() creates the LiftBase superclass and rewires HierChild")
    void envelope_extractSuperclass_createsFileAndRewires() {
        ObjectNode args = envelope.args();
        args.put("filePath", childPath);
        args.put("line", 6);    // 0-based; "    public int liftable() {"
        args.put("column", 15);
        args.put("superclassName", "LiftBase");
        JsonNode payload = envelope.payload("extract_superclass", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "extract_superclass failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("LiftBase", data.get("superclassName").asText());
        JsonNode createdFiles = data.get("createdFiles");
        assertEquals(1, createdFiles.size(), "exactly one new superclass file through the envelope");
        String content = createdFiles.get(0).get("content").asText();
        assertTrue(content.contains("class LiftBase") && content.contains("liftable"),
            "the new file must declare LiftBase with the moved member through the envelope; got:\n" + content);
        // HierChild must be rewired to extend LiftBase.
        StringBuilder childNew = new StringBuilder();
        JsonNode editsByFile = data.get("editsByFile");
        java.util.Iterator<String> fields = editsByFile.fieldNames();
        while (fields.hasNext()) {
            String file = fields.next();
            if (file.endsWith("HierChild.java")) {
                for (JsonNode edit : editsByFile.get(file)) childNew.append(edit.path("newText").asText());
            }
        }
        assertTrue(childNew.toString().contains("LiftBase"),
            "HierChild must be rewired to extend LiftBase through the envelope; got: " + childNew);
    }
}
