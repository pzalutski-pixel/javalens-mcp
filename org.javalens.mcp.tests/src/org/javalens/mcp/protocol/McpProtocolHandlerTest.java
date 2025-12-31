package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.Tool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpProtocolHandler.
 * Tests JSON-RPC message handling and MCP protocol lifecycle.
 */
class McpProtocolHandlerTest {

    private ToolRegistry toolRegistry;
    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        objectMapper = new ObjectMapper();
    }

    // ========== Message Parsing Tests ==========

    @Test
    @DisplayName("processMessage should parse valid JSON-RPC request")
    void processMessage_parsesValidJson() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """;

        String response = handler.processMessage(request);

        assertNotNull(response);
        JsonNode json = objectMapper.readTree(response);
        assertEquals("2.0", json.get("jsonrpc").asText());
        assertEquals(1, json.get("id").asInt());
        assertNotNull(json.get("result"));
    }

    @Test
    @DisplayName("processMessage should return parse error for invalid JSON")
    void processMessage_returnsParseErrorForInvalidJson() throws Exception {
        String invalidJson = "{ not valid json }";

        String response = handler.processMessage(invalidJson);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("error"));
        assertEquals(-32700, json.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("processMessage should return invalid request for wrong version")
    void processMessage_returnsInvalidRequestForWrongVersion() throws Exception {
        String request = """
            {"jsonrpc":"1.0","id":1,"method":"initialize","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("error"));
        assertEquals(-32600, json.get("error").get("code").asInt());
    }

    // ========== Initialize Tests ==========

    @Test
    @DisplayName("initialize should return server info")
    void initialize_returnsServerInfo() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        JsonNode result = json.get("result");
        assertNotNull(result);
        assertNotNull(result.get("protocolVersion"));
        assertNotNull(result.get("serverInfo"));
        assertEquals("JavaLens", result.get("serverInfo").get("name").asText());
    }

    @Test
    @DisplayName("initialize should set initialized flag")
    void initialize_setsInitializedFlag() {
        assertFalse(handler.isInitialized());

        String request = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """;
        handler.processMessage(request);

        assertTrue(handler.isInitialized());
    }

    @Test
    @DisplayName("initialize should capture client info")
    void initialize_capturesClientInfo() {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "clientInfo":{"name":"TestClient","version":"1.0"}
            }}
            """;

        handler.processMessage(request);

        assertEquals("TestClient", handler.getClientName());
        assertEquals("1.0", handler.getClientVersion());
    }

    // ========== Tools/List Tests ==========

    @Test
    @DisplayName("tools/list should return tool definitions")
    void toolsList_returnsToolDefinitions() throws Exception {
        toolRegistry.register(new MockTool("test_tool", "Test description"));

        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        JsonNode result = json.get("result");
        assertNotNull(result);
        JsonNode tools = result.get("tools");
        assertNotNull(tools);
        assertTrue(tools.isArray());
        assertEquals(1, tools.size());
        assertEquals("test_tool", tools.get(0).get("name").asText());
    }

    @Test
    @DisplayName("tools/list should return empty array when no tools")
    void toolsList_returnsEmptyArray() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        JsonNode tools = json.get("result").get("tools");
        assertTrue(tools.isArray());
        assertEquals(0, tools.size());
    }

    // ========== Tools/Call Tests ==========

    @Test
    @DisplayName("tools/call should execute tool")
    void toolsCall_executesTool() throws Exception {
        toolRegistry.register(new MockTool("execute_me", "Executable tool"));

        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"execute_me",
                "arguments":{}
            }}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("result"));
        assertNotNull(json.get("result").get("content"));
    }

    @Test
    @DisplayName("tools/call should return method not found for unknown tool")
    void toolsCall_returnsMethodNotFoundForUnknownTool() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"nonexistent_tool",
                "arguments":{}
            }}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("error"));
        assertEquals(-32601, json.get("error").get("code").asInt());
    }

    @Test
    @DisplayName("tools/call should return invalid params for missing name")
    void toolsCall_returnsInvalidParamsForMissingName() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "arguments":{}
            }}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("error"));
        assertEquals(-32602, json.get("error").get("code").asInt());
    }

    // ========== Notification Tests ==========

    @Test
    @DisplayName("notification should return null")
    void notification_returnsNull() {
        // Notification has no id
        String notification = """
            {"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}
            """;

        String response = handler.processMessage(notification);

        assertNull(response);
    }

    @Test
    @DisplayName("initialized notification should be handled")
    void initialized_notificationHandled() {
        String notification = """
            {"jsonrpc":"2.0","method":"initialized","params":{}}
            """;

        // Should not throw
        String response = handler.processMessage(notification);

        assertNull(response);
    }

    // ========== Shutdown Tests ==========

    @Test
    @DisplayName("shutdown should return success response")
    void shutdown_returnsSuccessResponse() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"shutdown","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        // Shutdown returns a response with null or empty result
        assertNotNull(response);
        assertEquals(1, json.get("id").asInt());
        assertNull(json.get("error"));
    }

    // ========== Unknown Method Tests ==========

    @Test
    @DisplayName("unknown method should return method not found")
    void unknownMethod_returnsMethodNotFound() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"unknown/method","params":{}}
            """;

        String response = handler.processMessage(request);

        JsonNode json = objectMapper.readTree(response);
        assertNotNull(json.get("error"));
        assertEquals(-32601, json.get("error").get("code").asInt());
    }

    // ========== Mock Tool ==========

    private static class MockTool implements Tool {
        private final String name;
        private final String description;

        MockTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            return ToolResponse.success(Map.of("executed", true));
        }
    }
}
