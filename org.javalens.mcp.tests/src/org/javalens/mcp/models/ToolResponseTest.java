package org.javalens.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolResponse.
 * Tests response creation and factory methods.
 */
class ToolResponseTest {

    // ========== Success Response Tests ==========

    @Test
    @DisplayName("success should create response with success=true")
    void success_createsSuccessResponse() {
        Map<String, String> data = Map.of("key", "value");

        ToolResponse response = ToolResponse.success(data);

        assertTrue(response.isSuccess());
        assertEquals(data, response.getData());
        assertNull(response.getError());
    }

    @Test
    @DisplayName("success with meta should include metadata")
    void success_withMeta_includesMeta() {
        Map<String, String> data = Map.of("key", "value");
        ResponseMeta meta = ResponseMeta.builder()
            .totalCount(100)
            .returnedCount(5)
            .build();

        ToolResponse response = ToolResponse.success(data, meta);

        assertTrue(response.isSuccess());
        assertEquals(data, response.getData());
        assertNotNull(response.getMeta());
        assertEquals(100, response.getMeta().getTotalCount());
        assertEquals(5, response.getMeta().getReturnedCount());
    }

    @Test
    @DisplayName("success should allow null data")
    void success_allowsNullData() {
        ToolResponse response = ToolResponse.success(null);

        assertTrue(response.isSuccess());
        assertNull(response.getData());
    }

    // ========== Error Response Tests ==========

    @Test
    @DisplayName("error should create response with success=false")
    void error_createsErrorResponse() {
        ErrorInfo errorInfo = new ErrorInfo("TEST_ERROR", "Test message", "Test hint");

        ToolResponse response = ToolResponse.error(errorInfo);

        assertFalse(response.isSuccess());
        assertNull(response.getData());
        assertNotNull(response.getError());
        assertEquals("TEST_ERROR", response.getError().getCode());
    }

    @Test
    @DisplayName("error with code/message/hint should set all fields")
    void error_withCodeMessageHint_setsAllFields() {
        ToolResponse response = ToolResponse.error("CODE", "Message", "Hint");

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
        assertEquals("CODE", response.getError().getCode());
        assertEquals("Message", response.getError().getMessage());
        assertEquals("Hint", response.getError().getHint());
    }

    // ========== Factory Method Tests ==========

    @Test
    @DisplayName("projectNotLoaded should create standard error")
    void projectNotLoaded_createsStandardError() {
        ToolResponse response = ToolResponse.projectNotLoaded();

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
        assertEquals(ErrorInfo.PROJECT_NOT_LOADED, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("No project loaded"));
    }

    @Test
    @DisplayName("fileNotFound should include path in message")
    void fileNotFound_includesPath() {
        String path = "/path/to/file.java";

        ToolResponse response = ToolResponse.fileNotFound(path);

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.FILE_NOT_FOUND, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains(path));
    }

    @Test
    @DisplayName("symbolNotFound should include symbol in message")
    void symbolNotFound_includesSymbol() {
        String symbol = "MyClass";

        ToolResponse response = ToolResponse.symbolNotFound(symbol);

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.SYMBOL_NOT_FOUND, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains(symbol));
    }

    @Test
    @DisplayName("invalidCoordinates should include line and column")
    void invalidCoordinates_includesDetails() {
        ToolResponse response = ToolResponse.invalidCoordinates(10, 5, "out of range");

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.INVALID_COORDINATES, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("10"));
        assertTrue(response.getError().getMessage().contains("5"));
        assertTrue(response.getError().getMessage().contains("out of range"));
    }

    @Test
    @DisplayName("invalidParameter should include param name")
    void invalidParameter_includesParamName() {
        ToolResponse response = ToolResponse.invalidParameter("filePath", "must not be null");

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("filePath"));
        assertTrue(response.getError().getMessage().contains("must not be null"));
    }

    @Test
    @DisplayName("internalError from exception should use exception message")
    void internalError_fromException() {
        Exception ex = new RuntimeException("Something went wrong");

        ToolResponse response = ToolResponse.internalError(ex);

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.INTERNAL_ERROR, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("Something went wrong"));
    }

    @Test
    @DisplayName("securityViolation should include reason")
    void securityViolation_includesReason() {
        ToolResponse response = ToolResponse.securityViolation("path traversal attempt");

        assertFalse(response.isSuccess());
        assertEquals(ErrorInfo.SECURITY_VIOLATION, response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("path traversal attempt"));
    }
}
