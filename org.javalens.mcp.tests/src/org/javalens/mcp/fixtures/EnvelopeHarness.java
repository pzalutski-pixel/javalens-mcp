package org.javalens.mcp.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.mcp.tools.Tool;
import org.javalens.mcp.tools.ToolRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Drives MCP tools through the REAL JSON-RPC envelope and the production
 * {@link JavaLensApplication#registerTools()} wiring — the same path a client
 * exercises, and the seam that surfaced the {@code health_check} desync that
 * replica-callback registrations could not catch.
 *
 * <p>Per-tool tests use this to assert exact, authored ground-truth values on
 * the payload a {@code tools/call} actually returns, proving the wire and
 * serialization preserve the correct answer. {@link #payload} returns the
 * parsed tool result ({@code result.content[0].text}) after asserting the
 * JSON-RPC envelope carried no error; {@link #call} returns the raw envelope
 * for error-contract assertions.
 */
public final class EnvelopeHarness {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpProtocolHandler handler;
    private final ToolRegistry registry;

    public EnvelopeHarness(JdtServiceImpl service) {
        this(service, null);
    }

    /**
     * Wire the harness as {@link #EnvelopeHarness(JdtServiceImpl)} does, with load_project
     * loading each project through a service from {@code loadProjectServices}, the
     * application's package-private seam, instead of a new default service.
     */
    public EnvelopeHarness(JdtServiceImpl service, Supplier<JdtServiceImpl> loadProjectServices) {
        try {
            JavaLensApplication app = new JavaLensApplication();
            Field svcField = JavaLensApplication.class.getDeclaredField("jdtService");
            svcField.setAccessible(true);
            svcField.set(app, service);

            if (loadProjectServices != null) {
                Field servicesField = JavaLensApplication.class.getDeclaredField("loadProjectServices");
                servicesField.setAccessible(true);
                servicesField.set(app, loadProjectServices);
            }

            Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
            registryField.setAccessible(true);
            ToolRegistry r = new ToolRegistry();
            registryField.set(app, r);

            Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
            registerTools.setAccessible(true);
            registerTools.invoke(app);

            this.registry = r;
            this.handler = new McpProtocolHandler(r);
            handler.processMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to wire EnvelopeHarness through the real registerTools()", e);
        }
    }

    /** The production-registered tool registry (every tool, real wiring). */
    public ToolRegistry registry() {
        return registry;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    /** Build an arguments node; tests fill it before calling. */
    public ObjectNode args() {
        return objectMapper.createObjectNode();
    }

    /** The raw JSON-RPC response envelope for a {@code tools/call}. */
    public JsonNode call(String toolName, ObjectNode arguments) {
        try {
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", 1);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", toolName);
            params.set("arguments", arguments.deepCopy());

            String response = handler.processMessage(objectMapper.writeValueAsString(call));
            assertNotNull(response, () -> "protocol handler returned no response for " + toolName);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("envelope call failed for " + toolName, e);
        }
    }

    /**
     * The parsed tool payload ({@code result.content[0].text}) for a
     * {@code tools/call}, after asserting the JSON-RPC envelope carried no error.
     */
    public JsonNode payload(String toolName, ObjectNode arguments) {
        JsonNode rpc = call(toolName, arguments);
        assertNull(rpc.get("error"), () -> toolName + ": JSON-RPC envelope error: " + rpc.get("error"));
        try {
            String text = rpc.path("result").path("content").get(0).path("text").asText();
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(
                "could not parse tool payload for " + toolName + ": " + rpc, e);
        }
    }

    /**
     * Drive the tool through the envelope AND through {@code execute()} on the same
     * production-registered instance with the same arguments, and assert the two
     * payloads are canonically identical — proving the ENTIRE wire/serialization
     * path preserves every field, not merely a headline value. Returns the parsed
     * envelope payload so the caller can additionally assert its authored
     * ground-truth values.
     *
     * <p>This makes the per-tool envelope seam symmetric with its {@code execute()}
     * oracle: the execute() test pins every field exactly, and this proves all of
     * them survive the JSON-RPC envelope unchanged.
     */
    public JsonNode assertEnvelopeFidelity(String toolName, ObjectNode arguments) {
        JsonNode envelopePayload = payload(toolName, arguments);
        Tool tool = registry.getTool(toolName)
            .orElseThrow(() -> new IllegalStateException("tool not registered: " + toolName));
        ToolResponse direct = tool.execute(arguments.deepCopy());
        JsonNode executePayload = objectMapper.valueToTree(direct);
        assertEquals(
            PayloadCanonicalizer.canonical(executePayload),
            PayloadCanonicalizer.canonical(envelopePayload),
            () -> "MCP envelope payload diverged from execute() for tool '" + toolName
                + "'\n  execute = " + PayloadCanonicalizer.canonical(executePayload)
                + "\n  envelope = " + PayloadCanonicalizer.canonical(envelopePayload));
        return envelopePayload;
    }
}
