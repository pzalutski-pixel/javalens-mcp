package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.PushDownTool;
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
 * Pins push_down: HierBase.movable() is pushed into HierChild — the base file
 * loses the method, the child file gains it, both via returned edits only.
 */
class PushDownToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private PushDownTool tool;
    private EnvelopeHarness envelope;
    private String basePath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new PushDownTool(() -> service);
        envelope = new EnvelopeHarness(service);
        basePath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierBase.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("pushing movable() down edits both HierBase (loses it) and HierChild (gains it)")
    @SuppressWarnings("unchecked")
    void pushDown_movesMethodToSubclass() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", basePath);
        args.put("line", 9);    // 0-based; file line 10 = "    public int movable() {"
        args.put("column", 15);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("movable", data.get("memberName"));
        assertEquals("com.example.hier.HierBase", data.get("fromType"));
        List<String> toTypes = (List<String>) data.get("toTypes");
        assertTrue(toTypes.contains("com.example.hier.HierChild"),
            "HierChild must be a push target; got: " + toTypes);

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both HierBase and HierChild must receive edits; got: " + editsByFile.keySet());

        String childNewText = textFor(editsByFile, "HierChild.java", "newText");
        assertTrue(childNewText.contains("movable"),
            "HierChild must gain movable(); got: " + childNewText);

        String baseOldText = textFor(editsByFile, "HierBase.java", "oldText");
        String baseNewText = textFor(editsByFile, "HierBase.java", "newText");
        assertTrue(baseOldText.contains("movable") && !baseNewText.contains("movable"),
            "HierBase must lose movable(); old: " + baseOldText + " new: " + baseNewText);
    }

    private String textFor(Map<String, List<Map<String, Object>>> editsByFile,
                           String fileSuffix, String key) {
        return editsByFile.entrySet().stream()
            .filter(e -> e.getKey().endsWith(fileSuffix))
            .flatMap(e -> e.getValue().stream())
            .map(e -> String.valueOf(e.get(key)))
            .reduce("", String::concat);
    }

    @Test
    @DisplayName("a member of a class without subclasses is refused")
    void noSubclasses_refused() {
        // HierChild has no subclasses.
        String childPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/hier/HierChild.java").toString();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", childPath);
        args.put("line", 6);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "pushing from a class without subclasses must be refused; got: " + r.getData());
    }

    @Test
    @DisplayName("non-member position and missing params are rejected")
    void invalidInputs_rejected() {
        ObjectNode wrongPos = mapper.createObjectNode();
        wrongPos.put("filePath", basePath);
        wrongPos.put("line", 0);
        wrongPos.put("column", 0);
        assertFalse(tool.execute(wrongPos).isSuccess());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 9);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: pushing movable() down removes it from HierBase and adds it to HierChild")
    void envelope_pushDown_movesMethodToSubclass() {
        ObjectNode args = envelope.args();
        args.put("filePath", basePath);
        args.put("line", 9);    // 0-based; "    public int movable() {"
        args.put("column", 15);
        JsonNode payload = envelope.payload("push_down", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "push_down failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("movable", data.get("memberName").asText());
        assertEquals("com.example.hier.HierBase", data.get("fromType").asText());
        boolean hasChildTarget = false;
        for (JsonNode t : data.get("toTypes")) {
            if ("com.example.hier.HierChild".equals(t.asText())) hasChildTarget = true;
        }
        assertTrue(hasChildTarget, "HierChild must be a push target through the envelope; got: " + data.get("toTypes"));
        JsonNode editsByFile = data.get("editsByFile");
        assertEquals(2, editsByFile.size(),
            "both HierBase and HierChild must receive edits through the envelope; got: " + editsByFile);
        StringBuilder childNew = new StringBuilder();
        StringBuilder baseOld = new StringBuilder();
        StringBuilder baseNew = new StringBuilder();
        java.util.Iterator<String> files = editsByFile.fieldNames();
        while (files.hasNext()) {
            String file = files.next();
            for (JsonNode edit : editsByFile.get(file)) {
                if (file.endsWith("HierChild.java")) childNew.append(edit.path("newText").asText());
                else if (file.endsWith("HierBase.java")) {
                    baseOld.append(edit.path("oldText").asText());
                    baseNew.append(edit.path("newText").asText());
                }
            }
        }
        assertTrue(childNew.toString().contains("movable"),
            "HierChild must gain movable() through the envelope; got: " + childNew);
        assertTrue(baseOld.toString().contains("movable") && !baseNew.toString().contains("movable"),
            "HierBase must lose movable() through the envelope; old: " + baseOld + " new: " + baseNew);
    }
}
