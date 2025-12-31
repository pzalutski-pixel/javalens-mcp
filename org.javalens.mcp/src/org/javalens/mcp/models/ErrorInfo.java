package org.javalens.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error information for tool failures.
 * Provides machine-readable code, human-readable message, and helpful hint for AI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorInfo {

    private final String code;
    private final String message;
    private final String hint;

    public ErrorInfo(String code, String message, String hint) {
        this.code = code;
        this.message = message;
        this.hint = hint;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getHint() {
        return hint;
    }

    // Standard error codes
    public static final String PROJECT_NOT_LOADED = "PROJECT_NOT_LOADED";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String SYMBOL_NOT_FOUND = "SYMBOL_NOT_FOUND";
    public static final String INVALID_COORDINATES = "INVALID_COORDINATES";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String SECURITY_VIOLATION = "SECURITY_VIOLATION";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String REFACTORING_FAILED = "REFACTORING_FAILED";

    // Factory methods for common errors
    public static ErrorInfo projectNotLoaded() {
        return new ErrorInfo(
            PROJECT_NOT_LOADED,
            "No project loaded. Call load_project first.",
            "Use load_project with the path to your Java project root"
        );
    }

    public static ErrorInfo fileNotFound(String path) {
        return new ErrorInfo(
            FILE_NOT_FOUND,
            "File not found: " + path,
            "Verify the file path is correct and the file exists"
        );
    }

    public static ErrorInfo symbolNotFound(String symbol) {
        return new ErrorInfo(
            SYMBOL_NOT_FOUND,
            "Symbol not found: " + symbol,
            "Use search_symbols to find available symbols"
        );
    }

    public static ErrorInfo invalidCoordinates(int line, int column, String reason) {
        return new ErrorInfo(
            INVALID_COORDINATES,
            String.format("Invalid coordinates (line=%d, column=%d): %s", line, column, reason),
            "Remember: coordinates are zero-based. Editor line 1 = line 0 in API"
        );
    }

    public static ErrorInfo invalidParameter(String param, String reason) {
        return new ErrorInfo(
            INVALID_PARAMETER,
            String.format("Invalid parameter '%s': %s", param, reason),
            null
        );
    }

    public static ErrorInfo securityViolation(String reason) {
        return new ErrorInfo(
            SECURITY_VIOLATION,
            "Security violation: " + reason,
            "Ensure file paths are within the project directory"
        );
    }

    public static ErrorInfo timeout(String operation, int seconds) {
        return new ErrorInfo(
            TIMEOUT,
            String.format("Operation '%s' timed out after %d seconds", operation, seconds),
            "Try reducing the scope of the operation or increase timeout"
        );
    }

    public static ErrorInfo internalError(String message) {
        return new ErrorInfo(
            INTERNAL_ERROR,
            "Internal error: " + message,
            "This may be a bug. Check server logs for details."
        );
    }

    public static ErrorInfo refactoringFailed(String reason) {
        return new ErrorInfo(
            REFACTORING_FAILED,
            "Refactoring failed: " + reason,
            "Check preconditions: valid identifier, no conflicts"
        );
    }
}
