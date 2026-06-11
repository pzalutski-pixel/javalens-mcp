package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP-protocol integration validation for analyze_data_flow's followCalls
 * mode (issue #23) against the flow-maven fixture: the interprocedural flow
 * payload is asserted through the real JSON-RPC envelope.
 */
class FlowAnalysisProtocolIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;
    private volatile IJdtService sharedService;
    private Path projectPath;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        sharedService = null;

        toolRegistry.register(new LoadProjectTool(service -> this.sharedService = service));
        toolRegistry.register(new AnalyzeDataFlowTool(() -> this.sharedService));

        projectPath = helper.getFixturePath("flow-maven");
    }

    private JsonNode toolPayload(String request) throws Exception {
        String response = handler.processMessage(request);
        assertNotNull(response);
        JsonNode rpc = objectMapper.readTree(response);
        assertNull(rpc.get("error"), () -> "JSON-RPC error: " + rpc);
        String text = rpc.get("result").get("content").get(0).get("text").asText();
        return objectMapper.readTree(text);
    }

    @Test
    @DisplayName("tools/call analyze_data_flow followCalls returns the null flow through the envelope")
    void toolsCall_followCalls_nullFlow() throws Exception {
        String loadRequest = String.format("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"load_project",
                "arguments":{"projectPath":"%s"}
            }}
            """, projectPath.toString().replace("\\", "\\\\"));
        assertTrue(toolPayload(loadRequest).get("success").asBoolean());

        String nullFlowFile = projectPath.resolve("src/main/java/com/flow/NullFlow.java")
            .toString().replace("\\", "\\\\");
        JsonNode payload = toolPayload(String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"analyze_data_flow",
                "arguments":{"filePath":"%s","line":4,"column":19,"followCalls":true}
            }}
            """, nullFlowFile));

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertTrue(data.get("followCalls").asBoolean());

        JsonNode flows = data.get("interproceduralFlows");
        assertEquals(1, flows.size(), () -> "flows: " + flows);
        JsonNode flow = flows.get(0);
        assertEquals("null", flow.get("fact").asText());
        assertEquals("value", flow.get("sourceVariable").asText());
        assertEquals("dereference", flow.get("sink").get("kind").asText());
        assertEquals("text.length()", flow.get("sink").get("expression").asText());
    }
}
