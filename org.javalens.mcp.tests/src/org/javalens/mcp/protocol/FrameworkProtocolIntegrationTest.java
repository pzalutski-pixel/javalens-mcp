package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP-protocol integration validation for the framework extractors (issue
 * #24) against framework-maven: the assembled JPA model and route table are
 * asserted through the real JSON-RPC envelope.
 */
class FrameworkProtocolIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;
    private volatile IJdtService sharedService;
    private Path projectPath;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        sharedService = null;

        toolRegistry.register(new LoadProjectTool(service -> this.sharedService = service));
        toolRegistry.register(new GetJpaModelTool(() -> this.sharedService));
        toolRegistry.register(new GetHttpEndpointsTool(() -> this.sharedService));

        projectPath = helper.getFixturePath("framework-maven");
    }

    private JsonNode toolPayload(String request) throws Exception {
        String response = handler.processMessage(request);
        assertNotNull(response);
        JsonNode rpc = objectMapper.readTree(response);
        assertNull(rpc.get("error"), () -> "JSON-RPC error: " + rpc);
        String text = rpc.get("result").get("content").get(0).get("text").asText();
        return objectMapper.readTree(text);
    }

    private void loadFixture() throws Exception {
        String loadRequest = String.format("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"load_project",
                "arguments":{"projectPath":"%s"}
            }}
            """, projectPath.toString().replace("\\", "\\\\"));
        assertTrue(toolPayload(loadRequest).get("success").asBoolean());
    }

    @Test
    @DisplayName("tools/call get_jpa_model returns the assembled entity model")
    void toolsCall_getJpaModel() throws Exception {
        loadFixture();

        JsonNode payload = toolPayload("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"get_jpa_model","arguments":{}
            }}
            """);

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(2, data.get("entityCount").asInt());
        JsonNode customer = data.get("entities").get(0);
        assertEquals("com.fw.Customer", customer.get("name").asText());
        assertEquals("customers", customer.get("table").asText());
        assertEquals("customer", customer.get("relationships").get(0).get("mappedBy").asText());
    }

    @Test
    @DisplayName("tools/call get_http_endpoints returns the composed route table")
    void toolsCall_getHttpEndpoints() throws Exception {
        loadFixture();

        JsonNode payload = toolPayload("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"get_http_endpoints","arguments":{}
            }}
            """);

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(5, data.get("endpointCount").asInt());
        JsonNode first = data.get("endpoints").get(1);
        assertEquals("GET", first.get("httpMethod").asText());
        assertEquals("/api/orders/{id}", first.get("path").asText(),
            "class prefix and method path must be composed");
        assertEquals("com.fw.OrderController#get(long)", first.get("handler").asText());
    }
}
