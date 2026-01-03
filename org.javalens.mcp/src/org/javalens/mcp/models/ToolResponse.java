package org.javalens.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard response wrapper for all tool operations.
 * Provides consistent structure with success/error status, data, and metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResponse {

    private boolean success;
    private Object data;
    private ErrorInfo error;
    private ResponseMeta meta;

    private ToolResponse() {
        // Use factory methods
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getData() {
        return data;
    }

    public ErrorInfo getError() {
        return error;
    }

    public ResponseMeta getMeta() {
        return meta;
    }

    /**
     * Create a successful response with data and optional metadata.
     */
    public static ToolResponse success(Object data) {
        return success(data, null);
    }

    /**
     * Create a successful response with data and metadata.
     */
    public static ToolResponse success(Object data, ResponseMeta meta) {
        ToolResponse response = new ToolResponse();
        response.success = true;
        response.data = data;
        response.meta = meta;
        return response;
    }

    /**
     * Create an error response with error info.
     */
    public static ToolResponse error(ErrorInfo error) {
        ToolResponse response = new ToolResponse();
        response.success = false;
        response.error = error;
        return response;
    }

    /**
     * Create an error response with code, message, and optional hint.
     */
    public static ToolResponse error(String code, String message, String hint) {
        return error(new ErrorInfo(code, message, hint));
    }

    /**
     * Create a project not loaded error.
     */
    public static ToolResponse projectNotLoaded() {
        return error(ErrorInfo.projectNotLoaded());
    }

    /**
     * Create a project loading in progress response.
     * Used when auto-load is still running asynchronously.
     */
    public static ToolResponse projectLoading() {
        return error("PROJECT_LOADING",
            "Project is loading, please wait...",
            "The project is being loaded asynchronously. Call health_check to monitor loading status.");
    }

    /**
     * Create a project load failed error.
     */
    public static ToolResponse projectLoadFailed(String errorMessage) {
        return error("PROJECT_LOAD_FAILED",
            "Project failed to load: " + errorMessage,
            "Check the project path and ensure it's a valid Java project.");
    }

    /**
     * Create a file not found error.
     */
    public static ToolResponse fileNotFound(String path) {
        return error(ErrorInfo.fileNotFound(path));
    }

    /**
     * Create a symbol not found error.
     */
    public static ToolResponse symbolNotFound(String symbol) {
        return error(ErrorInfo.symbolNotFound(symbol));
    }

    /**
     * Create an invalid coordinates error.
     */
    public static ToolResponse invalidCoordinates(int line, int column, String reason) {
        return error(ErrorInfo.invalidCoordinates(line, column, reason));
    }

    /**
     * Create an invalid parameter error.
     */
    public static ToolResponse invalidParameter(String param, String reason) {
        return error(ErrorInfo.invalidParameter(param, reason));
    }

    /**
     * Create a security violation error.
     */
    public static ToolResponse securityViolation(String reason) {
        return error(ErrorInfo.securityViolation(reason));
    }

    /**
     * Create an internal error response.
     */
    public static ToolResponse internalError(String message) {
        return error(ErrorInfo.internalError(message));
    }

    /**
     * Create an internal error response from an exception.
     */
    public static ToolResponse internalError(Throwable e) {
        return error(ErrorInfo.internalError(e.getMessage()));
    }
}
