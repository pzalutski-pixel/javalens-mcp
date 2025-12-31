package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.javalens.mcp.models.ToolResponse;

import java.util.Map;

/**
 * Interface for all JavaLens tools.
 * Each tool provides semantic code analysis or modification capabilities.
 */
public interface Tool {

    /**
     * Get the unique name of this tool.
     * This is used in tools/call requests.
     */
    String getName();

    /**
     * Get the human-readable description of this tool.
     * This should include USAGE, OUTPUT, and WORKFLOW information
     * to help AI agents understand how to use the tool effectively.
     */
    String getDescription();

    /**
     * Get the JSON Schema for the tool's input parameters.
     * Returns a map representing the schema in JSON Schema format.
     */
    Map<String, Object> getInputSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments The arguments from the tools/call request
     * @return The tool response with data or error
     */
    ToolResponse execute(JsonNode arguments);
}
