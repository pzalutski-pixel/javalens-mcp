package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.core.exceptions.ProjectNotLoadedException;
import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AbstractTool}'s execute() dispatch + parameter helpers.
 * These are foundational: every concrete tool inherits them and depends on the
 * documented semantics. Previously only exercised indirectly through individual
 * tool tests, which tend to assume the helpers work and assert downstream
 * effects. This file pins the helpers themselves.
 */
class AbstractToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Minimal AbstractTool subclass that captures executeWithService's arguments. */
    private static class TestTool extends AbstractTool {
        IJdtService capturedService;
        JsonNode capturedArgs;

        TestTool(Supplier<IJdtService> supplier) {
            super(supplier);
        }
        @Override public String getName() { return "test_tool"; }
        @Override public String getDescription() { return "test"; }
        @Override public Map<String, Object> getInputSchema() { return Map.of(); }
        @Override
        protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
            capturedService = service;
            capturedArgs = arguments;
            return ToolResponse.success(Map.of("ok", true));
        }

        // Expose protected helpers for direct testing.
        String exposedGetString(JsonNode args, String name) { return getStringParam(args, name); }
        String exposedGetStringDefault(JsonNode args, String name, String def) {
            return getStringParam(args, name, def);
        }
        int exposedGetInt(JsonNode args, String name, int def) { return getIntParam(args, name, def); }
        boolean exposedGetBool(JsonNode args, String name, boolean def) { return getBooleanParam(args, name, def); }
        ToolResponse exposedRequireParam(JsonNode args, String name) { return requireParam(args, name); }
        IJdtService exposedRequireService() { return requireService(); }
        IJdtService exposedGetService() { return getService(); }
    }

    // ========== execute() dispatch ==========

    @Test
    @DisplayName("execute() with null service returns project-not-loaded (default loading-state branch)")
    void execute_nullService_returnsNotLoaded() {
        // JavaLensApplication.getLoadingState() returns NOT_LOADED when its static
        // `instance` field is null (no app started) — which is the test environment.
        // The switch hits the `default` arm and produces ToolResponse.projectNotLoaded().
        TestTool tool = new TestTool(() -> null);
        ToolResponse response = tool.execute(objectMapper.createObjectNode());

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
        assertEquals(ErrorInfo.PROJECT_NOT_LOADED, response.getError().getCode(),
            "Default loading-state branch must produce PROJECT_NOT_LOADED");
    }

    @Test
    @DisplayName("execute() with loaded service delegates to executeWithService with same args")
    void execute_loadedService_delegates() {
        IJdtService fakeService = new MinimalFakeService();
        TestTool tool = new TestTool(() -> fakeService);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("k", "v");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        assertEquals(fakeService, tool.capturedService,
            "execute() must pass the supplier's service to executeWithService");
        assertEquals(args, tool.capturedArgs,
            "execute() must pass the raw arguments through unchanged");
    }

    // ========== requireService / getService ==========

    @Test
    @DisplayName("requireService() throws ProjectNotLoadedException when supplier returns null")
    void requireService_nullSupplier_throws() {
        TestTool tool = new TestTool(() -> null);
        assertThrows(ProjectNotLoadedException.class, tool::exposedRequireService);
    }

    @Test
    @DisplayName("requireService() returns the service when non-null")
    void requireService_nonNull_returns() {
        IJdtService fakeService = new MinimalFakeService();
        TestTool tool = new TestTool(() -> fakeService);
        assertEquals(fakeService, tool.exposedRequireService());
    }

    @Test
    @DisplayName("getService() returns null without throwing (caller handles)")
    void getService_nullSupplier_returnsNull() {
        TestTool tool = new TestTool(() -> null);
        assertNull(tool.exposedGetService(),
            "getService() must NOT throw on null supplier — that's requireService's job");
    }

    // ========== getStringParam ==========

    @Test
    @DisplayName("getStringParam returns null when arguments node is null")
    void getStringParam_nullNode_returnsNull() {
        TestTool tool = new TestTool(() -> null);
        assertNull(tool.exposedGetString(null, "anyKey"));
    }

    @Test
    @DisplayName("getStringParam returns null when key is absent")
    void getStringParam_missingKey_returnsNull() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        assertNull(tool.exposedGetString(args, "absent"));
    }

    @Test
    @DisplayName("getStringParam returns the value when key is present")
    void getStringParam_present_returnsValue() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("key", "value");
        assertEquals("value", tool.exposedGetString(args, "key"));
    }

    @Test
    @DisplayName("getStringParam with default falls back when missing")
    void getStringParamWithDefault_missing_returnsDefault() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        assertEquals("fallback",
            tool.exposedGetStringDefault(args, "absent", "fallback"));
    }

    @Test
    @DisplayName("getStringParam with default returns value when present")
    void getStringParamWithDefault_present_returnsValue() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("k", "v");
        assertEquals("v", tool.exposedGetStringDefault(args, "k", "fallback"));
    }

    // ========== getIntParam ==========

    @Test
    @DisplayName("getIntParam returns default when arguments node is null")
    void getIntParam_nullNode_returnsDefault() {
        TestTool tool = new TestTool(() -> null);
        assertEquals(42, tool.exposedGetInt(null, "k", 42));
    }

    @Test
    @DisplayName("getIntParam returns default when key absent")
    void getIntParam_missing_returnsDefault() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        assertEquals(100, tool.exposedGetInt(args, "absent", 100));
    }

    @Test
    @DisplayName("getIntParam returns the value when present")
    void getIntParam_present_returnsValue() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("n", 7);
        assertEquals(7, tool.exposedGetInt(args, "n", 100));
    }

    // ========== getBooleanParam ==========

    @Test
    @DisplayName("getBooleanParam returns default when arguments node is null")
    void getBooleanParam_nullNode_returnsDefault() {
        TestTool tool = new TestTool(() -> null);
        assertTrue(tool.exposedGetBool(null, "k", true));
        assertFalse(tool.exposedGetBool(null, "k", false));
    }

    @Test
    @DisplayName("getBooleanParam returns the value when present")
    void getBooleanParam_present_returnsValue() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("flag", true);
        assertTrue(tool.exposedGetBool(args, "flag", false));
    }

    // ========== requireParam ==========

    @Test
    @DisplayName("requireParam returns INVALID_PARAMETER response when missing")
    void requireParam_missing_returnsError() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse error = tool.exposedRequireParam(args, "x");
        assertNotNull(error, "Missing required param must return an error response");
        assertFalse(error.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, error.getError().getCode());
        assertTrue(error.getError().getMessage().contains("x"),
            "Error message must include the missing param name; got: " + error.getError().getMessage());
    }

    @Test
    @DisplayName("requireParam returns null when the parameter is present (no error)")
    void requireParam_present_returnsNull() {
        TestTool tool = new TestTool(() -> null);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("x", "v");
        assertNull(tool.exposedRequireParam(args, "x"),
            "Present required param must return null (no error)");
    }

    @Test
    @DisplayName("requireParam returns INVALID_PARAMETER when arguments node itself is null")
    void requireParam_nullNode_returnsError() {
        TestTool tool = new TestTool(() -> null);
        ToolResponse error = tool.exposedRequireParam(null, "x");
        assertNotNull(error);
        assertEquals(ErrorInfo.INVALID_PARAMETER, error.getError().getCode());
    }

    // ========== executeWithService default unsupported-op ==========

    @Test
    @DisplayName("The default executeWithService body throws UnsupportedOperationException")
    void defaultExecuteWithService_throws() {
        // A tool that DOESN'T override executeWithService inherits the abstract base's
        // default body — which throws. Catches a regression where the default got changed
        // to swallow + return success.
        IJdtService fakeService = new MinimalFakeService();
        AbstractTool barebones = new AbstractTool(() -> fakeService) {
            @Override public String getName() { return "bare"; }
            @Override public String getDescription() { return ""; }
            @Override public Map<String, Object> getInputSchema() { return Map.of(); }
            // Note: NOT overriding executeWithService.
        };
        // execute() with non-null service delegates to executeWithService which throws.
        assertThrows(UnsupportedOperationException.class,
            () -> barebones.execute(objectMapper.createObjectNode()));
    }

    /** Bare minimal IJdtService — no logic, just non-null for tests that care only about that. */
    private static class MinimalFakeService implements IJdtService {
        @Override public org.javalens.core.IPathUtils getPathUtils() { return null; }
        @Override public List<org.javalens.core.project.model.LoadWarning> getWarnings() { return List.of(); }
        @Override public java.nio.file.Path getProjectRoot() { return null; }
        @Override public int getTimeoutSeconds() { return 30; }
        @Override public <T> T executeWithTimeout(java.util.concurrent.Callable<T> op, String name) { return null; }
        @Override public org.eclipse.jdt.core.IJavaProject getJavaProject() { return null; }
        @Override public org.javalens.core.search.SearchService getSearchService() { return null; }
        @Override public org.eclipse.jdt.core.ICompilationUnit getCompilationUnit(java.nio.file.Path p) { return null; }
        @Override public org.eclipse.jdt.core.IJavaElement getElementAtPosition(java.nio.file.Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType getTypeAtPosition(java.nio.file.Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType findType(String n) { return null; }
        @Override public String getContextLine(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return ""; }
        @Override public int getOffset(org.eclipse.jdt.core.ICompilationUnit cu, int l, int c) { return 0; }
        @Override public int getLineNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public int getColumnNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public List<java.nio.file.Path> getAllJavaFiles() { return List.of(); }
    }
}
