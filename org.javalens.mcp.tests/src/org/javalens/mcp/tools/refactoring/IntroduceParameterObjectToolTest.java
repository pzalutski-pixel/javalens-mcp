package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.IntroduceParameterObjectTool;
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
 * Pins introduce_parameter_object: send(host, port, secure) gains a
 * SendParameters bundle, and the in-file caller is rewritten to construct it.
 */
class IntroduceParameterObjectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private IntroduceParameterObjectTool tool;
    private EnvelopeHarness envelope;
    private String targetPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new IntroduceParameterObjectTool(() -> service);
        envelope = new EnvelopeHarness(service);
        targetPath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ipo/ParamBundleTarget.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("bundling send()'s parameters generates the class and rewrites the caller")
    @SuppressWarnings("unchecked")
    void introduceParameterObject_bundlesAndRewrites() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 6);    // 0-based; "    public String send(String host, int port, boolean secure) {"
        args.put("column", 18); // the "send" identifier

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; error: "
            + (r.getError() != null ? r.getError().getCode() + " " + r.getError().getMessage() : "n/a"));

        Map<String, Object> data = getData(r);
        assertEquals("send", data.get("methodName"));
        assertEquals("SendParameters", data.get("className"));
        assertEquals("parameterObject", data.get("parameterName"));
        // IPO bundles in-place: no new file, one edit rewriting the method region.
        assertEquals(0, ((List<?>) data.get("createdFiles")).size());
        assertEquals(1, ((Number) data.get("totalEdits")).intValue());
        assertEquals(1, ((Number) data.get("filesAffected")).intValue());

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data.get("editsByFile");
        String allNewText = editsByFile.values().stream()
            .flatMap(List::stream)
            .map(e -> String.valueOf(e.get("newText")))
            .reduce("", String::concat)
            .replace("\r\n", "\n");
        // Exact JDT introduce-parameter-object output: the nested SendParameters bundle
        // (tab-indented) followed by the rewritten send() and the caller construction
        // (space-indented, preserved from source). Pinned against the build's target platform.
        assertEquals(
            "static class SendParameters {\n"
            + "\t\tprivate String host;\n"
            + "\t\tprivate int port;\n"
            + "\t\tprivate boolean secure;\n"
            + "\n"
            + "\t\tpublic SendParameters(String host, int port, boolean secure) {\n"
            + "\t\t\tthis.host = host;\n"
            + "\t\t\tthis.port = port;\n"
            + "\t\t\tthis.secure = secure;\n"
            + "\t\t}\n"
            + "\n"
            + "\t\tpublic String getHost() {\n"
            + "\t\t\treturn host;\n"
            + "\t\t}\n"
            + "\n"
            + "\t\tpublic int getPort() {\n"
            + "\t\t\treturn port;\n"
            + "\t\t}\n"
            + "\n"
            + "\t\tpublic boolean isSecure() {\n"
            + "\t\t\treturn secure;\n"
            + "\t\t}\n"
            + "\t}\n"
            + "\n"
            + "\tpublic String send(SendParameters parameterObject) {\n"
            + "        return parameterObject.getHost() + \":\" + parameterObject.getPort() + (parameterObject.isSecure() ? \"!\" : \"\");\n"
            + "    }\n"
            + "\n"
            + "    public String sendDefault() {\n"
            + "        return send(new SendParameters(\"localhost\", 8080, true)",
            allNewText);
    }

    @Test
    @DisplayName("a method with no parameters is refused")
    void zeroParamMethod_refused() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", targetPath);
        args.put("line", 10);   // 0-based; "    public String sendDefault() {"
        args.put("column", 18);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "a method without parameters must be refused; got: " + r.getData());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'method': Method has no parameters to bundle",
            r.getError().getMessage());
    }

    @Test
    @DisplayName("non-method position and missing params are rejected")
    void invalidInputs_rejected() {
        ObjectNode wrongPos = mapper.createObjectNode();
        wrongPos.put("filePath", targetPath);
        wrongPos.put("line", 0);
        wrongPos.put("column", 0);
        ToolResponse wrongPosResp = tool.execute(wrongPos);
        assertFalse(wrongPosResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, wrongPosResp.getError().getCode());
        assertEquals("Invalid parameter 'position': No method at position", wrongPosResp.getError().getMessage());

        ObjectNode noFile = mapper.createObjectNode();
        noFile.put("line", 6);
        noFile.put("column", 18);
        ToolResponse noFileResp = tool.execute(noFile);
        assertFalse(noFileResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, noFileResp.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required", noFileResp.getError().getMessage());
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: bundling send() generates SendParameters and rewrites the caller")
    void envelope_introduceParameterObject_bundlesAndRewrites() {
        ObjectNode args = envelope.args();
        args.put("filePath", targetPath);
        args.put("line", 6);    // 0-based; send(String host, int port, boolean secure)
        args.put("column", 18);
        JsonNode payload = envelope.assertEnvelopeFidelity("introduce_parameter_object", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "introduce_parameter_object failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("send", data.get("methodName").asText());
        assertEquals("SendParameters", data.get("className").asText());
        StringBuilder allNewText = new StringBuilder();
        JsonNode editsByFile = data.get("editsByFile");
        java.util.Iterator<String> files = editsByFile.fieldNames();
        while (files.hasNext()) {
            for (JsonNode edit : editsByFile.get(files.next())) allNewText.append(edit.path("newText").asText());
        }
        String txt = allNewText.toString();
        assertTrue(txt.contains("SendParameters"),
            "edits must introduce the SendParameters class/usages through the envelope; got: " + txt);
        assertTrue(txt.contains("new SendParameters"),
            "the caller must construct the bundle through the envelope; got: " + txt);
    }
}
