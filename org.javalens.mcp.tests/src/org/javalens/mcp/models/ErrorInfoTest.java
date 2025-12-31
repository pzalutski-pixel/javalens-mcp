package org.javalens.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorInfo.
 * Tests error code constants and factory methods.
 */
class ErrorInfoTest {

    // ========== Constructor Tests ==========

    @Test
    @DisplayName("constructor should set all fields")
    void constructor_setsAllFields() {
        ErrorInfo error = new ErrorInfo("CODE", "Message", "Hint");

        assertEquals("CODE", error.getCode());
        assertEquals("Message", error.getMessage());
        assertEquals("Hint", error.getHint());
    }

    @Test
    @DisplayName("constructor should allow null hint")
    void constructor_allowsNullHint() {
        ErrorInfo error = new ErrorInfo("CODE", "Message", null);

        assertEquals("CODE", error.getCode());
        assertEquals("Message", error.getMessage());
        assertNull(error.getHint());
    }

    // ========== Factory Method Tests ==========

    @Test
    @DisplayName("projectNotLoaded should have correct code")
    void projectNotLoaded_hasCorrectCode() {
        ErrorInfo error = ErrorInfo.projectNotLoaded();

        assertEquals(ErrorInfo.PROJECT_NOT_LOADED, error.getCode());
        assertNotNull(error.getMessage());
        assertNotNull(error.getHint());
        assertTrue(error.getHint().contains("load_project"));
    }

    @Test
    @DisplayName("fileNotFound should include path in message")
    void fileNotFound_includesPath() {
        String path = "/some/path/File.java";

        ErrorInfo error = ErrorInfo.fileNotFound(path);

        assertEquals(ErrorInfo.FILE_NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains(path));
    }

    @Test
    @DisplayName("symbolNotFound should include symbol in message")
    void symbolNotFound_includesSymbol() {
        String symbol = "com.example.MyClass";

        ErrorInfo error = ErrorInfo.symbolNotFound(symbol);

        assertEquals(ErrorInfo.SYMBOL_NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains(symbol));
        assertTrue(error.getHint().contains("search_symbols"));
    }

    @Test
    @DisplayName("invalidCoordinates should have zero-based hint")
    void invalidCoordinates_hasZeroBasedHint() {
        ErrorInfo error = ErrorInfo.invalidCoordinates(10, 5, "out of bounds");

        assertEquals(ErrorInfo.INVALID_COORDINATES, error.getCode());
        assertTrue(error.getMessage().contains("10"));
        assertTrue(error.getMessage().contains("5"));
        assertTrue(error.getHint().contains("zero-based"));
    }

    @Test
    @DisplayName("invalidParameter should include param and reason")
    void invalidParameter_includesParamAndReason() {
        ErrorInfo error = ErrorInfo.invalidParameter("filePath", "cannot be empty");

        assertEquals(ErrorInfo.INVALID_PARAMETER, error.getCode());
        assertTrue(error.getMessage().contains("filePath"));
        assertTrue(error.getMessage().contains("cannot be empty"));
    }

    @Test
    @DisplayName("timeout should include operation and duration")
    void timeout_includesOperationAndDuration() {
        ErrorInfo error = ErrorInfo.timeout("search", 30);

        assertEquals(ErrorInfo.TIMEOUT, error.getCode());
        assertTrue(error.getMessage().contains("search"));
        assertTrue(error.getMessage().contains("30"));
    }

    @Test
    @DisplayName("securityViolation should include reason")
    void securityViolation_includesReason() {
        ErrorInfo error = ErrorInfo.securityViolation("path outside project");

        assertEquals(ErrorInfo.SECURITY_VIOLATION, error.getCode());
        assertTrue(error.getMessage().contains("path outside project"));
    }

    @Test
    @DisplayName("internalError should include message")
    void internalError_includesMessage() {
        ErrorInfo error = ErrorInfo.internalError("unexpected null pointer");

        assertEquals(ErrorInfo.INTERNAL_ERROR, error.getCode());
        assertTrue(error.getMessage().contains("unexpected null pointer"));
        assertTrue(error.getHint().contains("bug"));
    }

    @Test
    @DisplayName("refactoringFailed should include reason")
    void refactoringFailed_includesReason() {
        ErrorInfo error = ErrorInfo.refactoringFailed("name conflict");

        assertEquals(ErrorInfo.REFACTORING_FAILED, error.getCode());
        assertTrue(error.getMessage().contains("name conflict"));
    }

    // ========== Error Code Constants Tests ==========

    @Test
    @DisplayName("error code constants should be defined")
    void errorCodeConstants_areDefined() {
        assertEquals("PROJECT_NOT_LOADED", ErrorInfo.PROJECT_NOT_LOADED);
        assertEquals("FILE_NOT_FOUND", ErrorInfo.FILE_NOT_FOUND);
        assertEquals("SYMBOL_NOT_FOUND", ErrorInfo.SYMBOL_NOT_FOUND);
        assertEquals("INVALID_COORDINATES", ErrorInfo.INVALID_COORDINATES);
        assertEquals("INVALID_PARAMETER", ErrorInfo.INVALID_PARAMETER);
        assertEquals("SECURITY_VIOLATION", ErrorInfo.SECURITY_VIOLATION);
        assertEquals("TIMEOUT", ErrorInfo.TIMEOUT);
        assertEquals("INTERNAL_ERROR", ErrorInfo.INTERNAL_ERROR);
        assertEquals("REFACTORING_FAILED", ErrorInfo.REFACTORING_FAILED);
    }
}
