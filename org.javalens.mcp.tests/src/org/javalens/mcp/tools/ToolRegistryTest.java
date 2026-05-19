package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolRegistry.
 * Tests tool registration, lookup, and execution.
 */
class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        objectMapper = new ObjectMapper();
    }

    // ========== Registration Tests ==========

    @Test
    @DisplayName("register should add tool to registry")
    void register_addsTool() {
        Tool tool = new MockTool("test_tool", "Test tool description");

        registry.register(tool);

        assertTrue(registry.hasTool("test_tool"));
        assertEquals(1, registry.getToolCount());
    }

    @Test
    @DisplayName("register should overwrite existing tool with same name")
    void register_overwritesExisting() {
        Tool tool1 = new MockTool("same_name", "First");
        Tool tool2 = new MockTool("same_name", "Second");

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(1, registry.getToolCount());
        Optional<Tool> retrieved = registry.getTool("same_name");
        assertTrue(retrieved.isPresent());
        assertEquals("Second", retrieved.get().getDescription());
    }

    @Test
    @DisplayName("registerAll should add multiple tools")
    void registerAll_addsMultipleTools() {
        Tool tool1 = new MockTool("tool1", "First");
        Tool tool2 = new MockTool("tool2", "Second");
        Tool tool3 = new MockTool("tool3", "Third");

        registry.registerAll(tool1, tool2, tool3);

        assertEquals(3, registry.getToolCount());
        assertTrue(registry.hasTool("tool1"));
        assertTrue(registry.hasTool("tool2"));
        assertTrue(registry.hasTool("tool3"));
    }

    // ========== Lookup Tests ==========

    @Test
    @DisplayName("getTool should return registered tool")
    void getTool_returnsRegisteredTool() {
        Tool tool = new MockTool("my_tool", "My tool");
        registry.register(tool);

        Optional<Tool> result = registry.getTool("my_tool");

        assertTrue(result.isPresent());
        assertEquals("my_tool", result.get().getName());
    }

    @Test
    @DisplayName("getTool should return empty for missing tool")
    void getTool_returnsEmptyForMissing() {
        Optional<Tool> result = registry.getTool("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("hasTool should return true for registered tool")
    void hasTool_returnsTrueForRegistered() {
        registry.register(new MockTool("exists", "Exists"));

        assertTrue(registry.hasTool("exists"));
    }

    @Test
    @DisplayName("hasTool should return false for missing tool")
    void hasTool_returnsFalseForMissing() {
        assertFalse(registry.hasTool("missing"));
    }

    // ========== Enumeration Tests ==========

    @Test
    @DisplayName("getToolNames should return all registered names")
    void getToolNames_returnsAllNames() {
        registry.register(new MockTool("alpha", "Alpha"));
        registry.register(new MockTool("beta", "Beta"));
        registry.register(new MockTool("gamma", "Gamma"));

        Set<String> names = registry.getToolNames();

        assertEquals(3, names.size());
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        assertTrue(names.contains("gamma"));
    }

    @Test
    @DisplayName("getToolNames should return empty set for empty registry")
    void getToolNames_returnsEmptyForEmpty() {
        Set<String> names = registry.getToolNames();

        assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("getToolCount should return correct count")
    void getToolCount_returnsCorrectCount() {
        assertEquals(0, registry.getToolCount());

        registry.register(new MockTool("one", "One"));
        assertEquals(1, registry.getToolCount());

        registry.register(new MockTool("two", "Two"));
        assertEquals(2, registry.getToolCount());
    }

    // ========== Tool Definitions Tests ==========

    @Test
    @DisplayName("getToolDefinitions should return MCP format")
    void getToolDefinitions_returnsMcpFormat() {
        registry.register(new MockTool("test_tool", "Test description"));

        List<Map<String, Object>> definitions = registry.getToolDefinitions();

        assertEquals(1, definitions.size());
        Map<String, Object> def = definitions.get(0);
        assertEquals("test_tool", def.get("name"));
        assertEquals("Test description", def.get("description"));
        assertNotNull(def.get("inputSchema"));
    }

    @Test
    @DisplayName("getToolDefinitions should include all tools")
    void getToolDefinitions_includesAllTools() {
        registry.register(new MockTool("tool1", "First"));
        registry.register(new MockTool("tool2", "Second"));

        List<Map<String, Object>> definitions = registry.getToolDefinitions();

        assertEquals(2, definitions.size());
    }

    // ========== Tool Execution Tests ==========

    @Test
    @DisplayName("callTool should execute registered tool")
    void callTool_executesTool() throws Exception {
        Tool tool = new MockTool("executor", "Executor");
        registry.register(tool);
        JsonNode args = objectMapper.createObjectNode();

        ToolResponse response = registry.callTool("executor", args);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
    }

    @Test
    @DisplayName("callTool should throw ToolNotFoundException for missing tool")
    void callTool_throwsForMissing() {
        JsonNode args = objectMapper.createObjectNode();

        assertThrows(ToolRegistry.ToolNotFoundException.class,
            () -> registry.callTool("missing_tool", args));
    }

    @Test
    @DisplayName("callTool should handle tool exceptions gracefully and report INTERNAL_ERROR")
    void callTool_handlesExceptions() throws Exception {
        Tool failingTool = new FailingTool();
        registry.register(failingTool);
        JsonNode args = objectMapper.createObjectNode();

        ToolResponse response = registry.callTool("failing_tool", args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
        // Pin the specific error code. Previously the test only verified non-null error,
        // which would pass even if the registry emitted a different code. The source
        // explicitly wraps via ToolResponse.internalError(e), giving INTERNAL_ERROR.
        assertEquals(org.javalens.mcp.models.ErrorInfo.INTERNAL_ERROR,
            response.getError().getCode(),
            "Unhandled tool exception must be wrapped as INTERNAL_ERROR; got: "
                + response.getError().getCode());
        // Original exception's message should bubble up into the error message.
        assertTrue(response.getError().getMessage().contains("Intentional failure"),
            "Wrapped error must surface the original exception message; got: "
                + response.getError().getMessage());
    }

    @Test
    @DisplayName("getToolNames returns an unmodifiable view (mutation throws)")
    void getToolNames_unmodifiable() {
        registry.register(new MockTool("alpha", "A"));

        Set<String> names = registry.getToolNames();
        assertEquals(Set.of("alpha"), names);
        assertThrows(UnsupportedOperationException.class,
            () -> names.add("forbidden"),
            "getToolNames must return an unmodifiable view per the source's "
                + "Collections.unmodifiableSet wrapping");
    }

    @Test
    @DisplayName("getToolDefinitions preserves registration order (LinkedHashMap)")
    void getToolDefinitions_preservesOrder() {
        registry.register(new MockTool("zeta", "Z"));
        registry.register(new MockTool("alpha", "A"));
        registry.register(new MockTool("mu", "M"));

        List<Map<String, Object>> defs = registry.getToolDefinitions();
        // The source uses LinkedHashMap → iteration is insertion order. Pinning so an
        // optimization to HashMap (alphabetical or random) would surface as a test
        // failure rather than silently shuffling the tools/list response.
        List<String> orderedNames = defs.stream()
            .map(d -> (String) d.get("name"))
            .toList();
        assertEquals(List.of("zeta", "alpha", "mu"), orderedNames,
            "Tool definitions must come out in registration order; got: " + orderedNames);
    }

    // ========== Mock Tool Implementation ==========

    /**
     * Simple mock tool for testing.
     */
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
            return Map.of(
                "type", "object",
                "properties", Map.of()
            );
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            return ToolResponse.success(Map.of("executed", true, "tool", name));
        }
    }

    /**
     * Tool that throws an exception for testing error handling.
     */
    private static class FailingTool implements Tool {
        @Override
        public String getName() {
            return "failing_tool";
        }

        @Override
        public String getDescription() {
            return "A tool that always fails";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of();
        }

        @Override
        public ToolResponse execute(JsonNode arguments) {
            throw new RuntimeException("Intentional failure");
        }
    }
}
