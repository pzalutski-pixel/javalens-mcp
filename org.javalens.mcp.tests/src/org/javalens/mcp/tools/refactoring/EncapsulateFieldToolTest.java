package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.EncapsulateFieldTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins encapsulate_field, the first tool driving a JDT refactoring through its
 * public descriptor. EncapsulateTarget.count is public and accessed directly by
 * EncapsulateReader, so the edits must cover BOTH files: the accessor pair in
 * the declaring file and rewritten accesses in the reader.
 */
class EncapsulateFieldToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private EncapsulateFieldTool tool;
    private EnvelopeHarness envelope;
    private String targetPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new EncapsulateFieldTool(() -> service);
        envelope = new EnvelopeHarness(service);
        targetPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/encap/EncapsulateTarget.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode countArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 7);    // 0-based; file line 8 = "    public int count;"
        args.put("column", 15); // the "count" identifier
        return args;
    }

    @Test
    @DisplayName("encapsulating a public field edits both the declaring file and the external accessor")
    @SuppressWarnings("unchecked")
    void encapsulate_rewritesDeclarationAndExternalAccess() {
        ToolResponse r = tool.execute(countArgs());
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("count", data.get("fieldName"));
        assertEquals("getCount", data.get("getterName"));
        assertEquals("setCount", data.get("setterName"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both EncapsulateTarget and EncapsulateReader must receive edits; got: "
                + editsByFile.keySet());

        String allTargetText = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith("EncapsulateTarget.java"))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertTrue(allTargetText.contains("getCount") && allTargetText.contains("setCount"),
            "declaring file must gain the accessor pair; got: " + allTargetText);

        String allReaderText = editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith("EncapsulateReader.java"))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertTrue(allReaderText.contains("getCount") || allReaderText.contains("setCount"),
            "external accesses must be rewritten to accessor calls; got: " + allReaderText);
    }

    @Test
    @DisplayName("custom accessor names are honored")
    @SuppressWarnings("unchecked")
    void customAccessorNames() {
        ObjectNode args = countArgs();
        args.put("getterName", "readCount");
        args.put("setterName", "writeCount");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());
        Map<String, Object> data = getData(r);
        assertEquals("readCount", data.get("getterName"));
        assertEquals("writeCount", data.get("setterName"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        String allText = editsByFile.values().stream()
            .flatMap(List::stream)
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat);
        assertTrue(allText.contains("readCount"),
            "custom getter name must appear in the edits; got: " + allText);
    }

    @Test
    @DisplayName("position on a method (not a field) is refused")
    void nonFieldPosition_refused() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 9);    // 0-based; file line 10 = "    public int twice() {"
        args.put("column", 15); // the "twice" identifier
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "method position must be refused; got: " + r.getData());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("missing required parameters are rejected")
    void missingParams_rejected() {
        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 8);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = mapper.createObjectNode();
        noLine.put("filePath", targetPath);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test
    @DisplayName("edits carry the standard shape keys")
    @SuppressWarnings("unchecked")
    void editShape() {
        ToolResponse r = tool.execute(countArgs());
        assertTrue(r.isSuccess());
        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) getData(r).get("editsByFile");
        for (List<Map<String, Object>> edits : editsByFile.values()) {
            for (Map<String, Object> edit : edits) {
                assertNotNull(edit.get("type"), "type missing: " + edit);
                assertTrue(edit.containsKey("offset") || edit.containsKey("startOffset"),
                    "offset keys missing: " + edit);
                if (!"delete".equals(edit.get("type"))) {
                    assertNotNull(edit.get("newText"), "newText missing on non-delete edit: " + edit);
                }
            }
        }
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: encapsulating count yields getCount/setCount and edits across both files")
    void envelope_encapsulate_rewritesDeclarationAndExternalAccess() {
        ObjectNode args = envelope.args();
        args.put("filePath", targetPath);
        args.put("line", 7);    // 0-based; "    public int count;"
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("encapsulate_field", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "encapsulate_field failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("count", data.get("fieldName").asText());
        assertEquals("getCount", data.get("getterName").asText());
        assertEquals("setCount", data.get("setterName").asText());
        JsonNode editsByFile = data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both EncapsulateTarget and EncapsulateReader must receive edits through the envelope; got: "
                + editsByFile);
        StringBuilder targetText = new StringBuilder();
        StringBuilder readerText = new StringBuilder();
        java.util.Iterator<String> files = editsByFile.fieldNames();
        while (files.hasNext()) {
            String file = files.next();
            StringBuilder sink = file.endsWith("EncapsulateTarget.java") ? targetText
                : file.endsWith("EncapsulateReader.java") ? readerText : new StringBuilder();
            for (JsonNode edit : editsByFile.get(file)) sink.append(edit.path("newText").asText());
        }
        assertTrue(targetText.toString().contains("getCount") && targetText.toString().contains("setCount"),
            "declaring file must gain the accessor pair through the envelope; got: " + targetText);
        assertTrue(readerText.toString().contains("getCount") || readerText.toString().contains("setCount"),
            "external accesses must be rewritten to accessor calls through the envelope; got: " + readerText);
    }
}
