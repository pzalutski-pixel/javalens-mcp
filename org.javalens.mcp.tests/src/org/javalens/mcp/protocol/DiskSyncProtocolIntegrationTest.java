package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.sync.DiskSyncMode;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP-envelope validation of the #26 contract: the initialize response
 * carries the sync instructions, an on-disk edit is reflected by the very
 * next tools/call with no reload in between, and a build-file change
 * surfaces RELOAD_REQUIRED through the protocol.
 */
class DiskSyncProtocolIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;
    private volatile IJdtService sharedService;
    private Path projectCopy;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        sharedService = null;

        toolRegistry.register(new LoadProjectTool(service -> this.sharedService = service));
        toolRegistry.register(new FindReferencesTool(() -> this.sharedService));

        projectCopy = helper.copyFixture("simple-maven");
    }

    private JsonNode rpc(String request) throws Exception {
        String response = handler.processMessage(request);
        assertNotNull(response);
        return objectMapper.readTree(response);
    }

    private JsonNode toolPayload(String request) throws Exception {
        JsonNode json = rpc(request);
        assertNull(json.get("error"), () -> "JSON-RPC error: " + json);
        return objectMapper.readTree(json.get("result").get("content").get(0).get("text").asText());
    }

    private void loadCopyOverProtocol() throws Exception {
        String load = String.format("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"load_project","arguments":{"projectPath":"%s"}
            }}
            """, projectCopy.toString().replace("\\", "\\\\"));
        assertTrue(toolPayload(load).get("success").asBoolean());
    }

    private String findReferencesRequest() {
        return String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"find_references",
                "arguments":{"filePath":"%s","line":14,"column":16}
            }}
            """, projectCopy.resolve("src/main/java/com/example/Calculator.java")
                .toString().replace("\\", "\\\\"));
    }

    @Test
    @DisplayName("initialize carries the strict-mode sync instructions")
    void initialize_carriesInstructions() throws Exception {
        JsonNode init = rpc("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """);
        JsonNode instructions = init.get("result").get("instructions");
        assertNotNull(instructions, "initialize result must carry the MCP instructions field");
        assertTrue(instructions.asText().contains("verified against"),
            () -> "strict-mode contract expected; got: " + instructions.asText());
        assertTrue(instructions.asText().contains("RELOAD_REQUIRED"),
            "instructions must tell the AI what RELOAD_REQUIRED means");
    }

    @Test
    @DisplayName("the manual-mode instructions describe today's contract")
    void manualInstructions_describeManualContract() {
        String manual = McpProtocolHandler.syncInstructions(DiskSyncMode.MANUAL);
        assertTrue(manual.contains("load_project"),
            "manual contract must direct the AI to reload after edits");
        assertTrue(manual.toLowerCase().contains("after"),
            () -> "manual contract must describe the edit->reload loop; got: " + manual);
    }

    @Test
    @DisplayName("an on-disk edit is reflected by the very next tools/call - no reload between")
    void editThenQuery_overProtocol() throws Exception {
        loadCopyOverProtocol();

        JsonNode before = toolPayload(findReferencesRequest());
        assertTrue(before.get("success").asBoolean());
        int sitesBefore = before.get("data").get("locations").size();

        Path greeter = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        String source = Files.readString(greeter);
        int lastBrace = source.lastIndexOf('}');
        Files.writeString(greeter, source.substring(0, lastBrace)
            + "\n    public int protocolEdit() {\n        return new Calculator().add(5, 6);\n    }\n}\n");

        JsonNode after = toolPayload(findReferencesRequest());
        assertTrue(after.get("success").asBoolean(), () -> "after-edit call failed: " + after);
        assertEquals(sitesBefore + 1, after.get("data").get("locations").size(),
            "the new call site must appear with no reload call in between");
    }

    @Test
    @DisplayName("a build-file change surfaces RELOAD_REQUIRED through the envelope")
    void buildFileChange_overProtocol() throws Exception {
        loadCopyOverProtocol();

        Path pom = projectCopy.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom)
            .replace("</project>", "    <!-- touched -->\n</project>"));

        JsonNode payload = toolPayload(findReferencesRequest());
        assertFalse(payload.get("success").asBoolean());
        assertEquals("RELOAD_REQUIRED", payload.get("error").get("code").asText());
        assertTrue(payload.get("error").get("message").asText().contains("pom.xml"));
    }

    @Test
    @DisplayName("manual mode over the protocol keeps the stale answer")
    void manualMode_overProtocol() throws Exception {
        loadCopyOverProtocol();
        ((JdtServiceImpl) sharedService).setDiskSyncMode(DiskSyncMode.MANUAL);

        JsonNode before = toolPayload(findReferencesRequest());
        int sitesBefore = before.get("data").get("locations").size();

        Path greeter = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        String source = Files.readString(greeter);
        int lastBrace = source.lastIndexOf('}');
        Files.writeString(greeter, source.substring(0, lastBrace)
            + "\n    public int manualEdit() {\n        return new Calculator().add(5, 6);\n    }\n}\n");

        JsonNode after = toolPayload(findReferencesRequest());
        assertTrue(after.get("success").asBoolean());
        assertEquals(sitesBefore, after.get("data").get("locations").size(),
            "manual mode must not auto-repair through the protocol either");
    }
}
