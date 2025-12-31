package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonRpcMessage.
 * Tests message type detection and factory methods.
 */
class JsonRpcMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ========== Factory Method Tests ==========

    @Test
    @DisplayName("request should set method and id")
    void request_setsMethodAndId() {
        JsonNode params = objectMapper.createObjectNode();

        JsonRpcMessage msg = JsonRpcMessage.request(1, "test/method", params);

        assertEquals("2.0", msg.getJsonrpc());
        assertEquals(1, msg.getId());
        assertEquals("test/method", msg.getMethod());
        assertNotNull(msg.getParams());
    }

    @Test
    @DisplayName("request should accept string id")
    void request_acceptsStringId() {
        JsonRpcMessage msg = JsonRpcMessage.request("req-123", "test/method", null);

        assertEquals("req-123", msg.getId());
    }

    @Test
    @DisplayName("successResponse should set result")
    void successResponse_setsResult() {
        Map<String, Object> result = Map.of("key", "value");

        JsonRpcMessage msg = JsonRpcMessage.successResponse(1, result);

        assertEquals("2.0", msg.getJsonrpc());
        assertEquals(1, msg.getId());
        assertEquals(result, msg.getResult());
        assertNull(msg.getError());
        assertNull(msg.getMethod());
    }

    @Test
    @DisplayName("errorResponse should set error object")
    void errorResponse_setsErrorObject() {
        JsonRpcMessage msg = JsonRpcMessage.errorResponse(1, -32600, "Invalid request", null);

        assertEquals("2.0", msg.getJsonrpc());
        assertEquals(1, msg.getId());
        assertNotNull(msg.getError());
        assertEquals(-32600, msg.getError().getCode());
        assertEquals("Invalid request", msg.getError().getMessage());
    }

    @Test
    @DisplayName("errorResponse should include data when provided")
    void errorResponse_includesData() {
        Map<String, String> data = Map.of("detail", "more info");

        JsonRpcMessage msg = JsonRpcMessage.errorResponse(1, -32602, "Invalid params", data);

        assertEquals(data, msg.getError().getData());
    }

    @Test
    @DisplayName("notification should have no id")
    void notification_hasNoId() {
        JsonNode params = objectMapper.createObjectNode();

        JsonRpcMessage msg = JsonRpcMessage.notification("test/notification", params);

        assertEquals("2.0", msg.getJsonrpc());
        assertNull(msg.getId());
        assertEquals("test/notification", msg.getMethod());
        assertNotNull(msg.getParams());
    }

    // ========== Type Detection Tests ==========

    @Test
    @DisplayName("isRequest should return true when method and id present")
    void isRequest_trueWhenMethodAndId() {
        JsonRpcMessage msg = JsonRpcMessage.request(1, "test", null);

        assertTrue(msg.isRequest());
        assertFalse(msg.isNotification());
        assertFalse(msg.isResponse());
    }

    @Test
    @DisplayName("isNotification should return true when method but no id")
    void isNotification_trueWhenMethodNoId() {
        JsonRpcMessage msg = JsonRpcMessage.notification("test", null);

        assertFalse(msg.isRequest());
        assertTrue(msg.isNotification());
        assertFalse(msg.isResponse());
    }

    @Test
    @DisplayName("isResponse should return true when result present")
    void isResponse_trueWhenResult() {
        JsonRpcMessage msg = JsonRpcMessage.successResponse(1, "result");

        assertFalse(msg.isRequest());
        assertFalse(msg.isNotification());
        assertTrue(msg.isResponse());
    }

    @Test
    @DisplayName("isResponse should return true when error present")
    void isResponse_trueWhenError() {
        JsonRpcMessage msg = JsonRpcMessage.errorResponse(1, -32600, "Error", null);

        assertFalse(msg.isRequest());
        assertFalse(msg.isNotification());
        assertTrue(msg.isResponse());
    }

    // ========== Error Code Constants Tests ==========

    @Test
    @DisplayName("error codes should have correct values")
    void errorCodes_haveCorrectValues() {
        assertEquals(-32700, JsonRpcMessage.JsonRpcError.PARSE_ERROR);
        assertEquals(-32600, JsonRpcMessage.JsonRpcError.INVALID_REQUEST);
        assertEquals(-32601, JsonRpcMessage.JsonRpcError.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpcMessage.JsonRpcError.INVALID_PARAMS);
        assertEquals(-32603, JsonRpcMessage.JsonRpcError.INTERNAL_ERROR);
    }

    // ========== JsonRpcError Tests ==========

    @Test
    @DisplayName("JsonRpcError constructor should set all fields")
    void jsonRpcError_constructorSetsFields() {
        JsonRpcMessage.JsonRpcError error = new JsonRpcMessage.JsonRpcError(-32600, "Test message", "data");

        assertEquals(-32600, error.getCode());
        assertEquals("Test message", error.getMessage());
        assertEquals("data", error.getData());
    }

    @Test
    @DisplayName("JsonRpcError setters should update fields")
    void jsonRpcError_settersWork() {
        JsonRpcMessage.JsonRpcError error = new JsonRpcMessage.JsonRpcError();

        error.setCode(-32601);
        error.setMessage("Method not found");
        error.setData(Map.of("method", "unknown"));

        assertEquals(-32601, error.getCode());
        assertEquals("Method not found", error.getMessage());
        assertNotNull(error.getData());
    }

    // ========== Getter/Setter Tests ==========

    @Test
    @DisplayName("getters and setters should work correctly")
    void gettersAndSetters_work() {
        JsonRpcMessage msg = new JsonRpcMessage();

        msg.setJsonrpc("2.0");
        msg.setId(42);
        msg.setMethod("test/method");

        assertEquals("2.0", msg.getJsonrpc());
        assertEquals(42, msg.getId());
        assertEquals("test/method", msg.getMethod());
    }

    @Test
    @DisplayName("setResult should update result field")
    void setResult_works() {
        JsonRpcMessage msg = new JsonRpcMessage();
        Map<String, String> result = Map.of("status", "ok");

        msg.setResult(result);

        assertEquals(result, msg.getResult());
    }

    @Test
    @DisplayName("setError should update error field")
    void setError_works() {
        JsonRpcMessage msg = new JsonRpcMessage();
        JsonRpcMessage.JsonRpcError error = new JsonRpcMessage.JsonRpcError(-32700, "Parse error", null);

        msg.setError(error);

        assertNotNull(msg.getError());
        assertEquals(-32700, msg.getError().getCode());
    }

    @Test
    @DisplayName("setParams should update params field")
    void setParams_works() {
        JsonRpcMessage msg = new JsonRpcMessage();
        JsonNode params = objectMapper.createObjectNode();

        msg.setParams(params);

        assertNotNull(msg.getParams());
    }
}
