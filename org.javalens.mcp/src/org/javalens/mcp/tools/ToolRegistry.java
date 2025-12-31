package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry for all JavaLens tools.
 * Handles tool registration, listing, and dispatching calls.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Register a tool with the registry.
     */
    public void register(Tool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    /**
     * Register multiple tools.
     */
    public void registerAll(Tool... toolsToRegister) {
        for (Tool tool : toolsToRegister) {
            register(tool);
        }
    }

    /**
     * Get a tool by name.
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get the number of registered tools.
     */
    public int getToolCount() {
        return tools.size();
    }

    /**
     * Get tool definitions for the tools/list response.
     * Returns a list of tool definitions in MCP format.
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (Tool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.getName());
            def.put("description", tool.getDescription());
            def.put("inputSchema", tool.getInputSchema());
            definitions.add(def);
        }

        return definitions;
    }

    /**
     * Call a tool by name with the given arguments.
     *
     * @param name The tool name
     * @param arguments The tool arguments
     * @return The tool response
     * @throws ToolNotFoundException if the tool is not registered
     */
    public ToolResponse callTool(String name, JsonNode arguments) throws ToolNotFoundException {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolNotFoundException("Tool not found: " + name);
        }

        log.info("Executing tool: {}", name);
        long startTime = System.currentTimeMillis();

        try {
            ToolResponse response = tool.execute(arguments);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool {} completed in {}ms, success={}", name, duration, response.isSuccess());
            return response;
        } catch (Exception e) {
            log.error("Tool {} failed with exception", name, e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Exception thrown when a tool is not found.
     */
    public static class ToolNotFoundException extends Exception {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }
}
