package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.JavaLensApplication;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ToolInvocationInputs;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Universal MCP-protocol parity, BEHAVIORAL: EVERY registered tool is driven
 * through the real {@link McpProtocolHandler#processMessage} AND through a
 * direct {@code execute()} on the same registry with the same input, and the
 * two payloads must be identical (modulo timestamps). The direct execute()
 * path is the one the per-tool behavior tests already validate against known
 * fixtures, so this proves the MCP envelope delivers the real, tested result
 * for every tool — not merely a well-formed shape. A shape-only check would
 * pass a tool that returns a successful-but-wrong answer (the #32 class).
 *
 * <p>Also a permanent gate: a newly registered tool with no entry in
 * {@link ToolInvocationInputs} fails {@link #everyRegisteredToolHasAnInput},
 * so the per-tool protocol rule can no longer silently lapse.
 */
class ProtocolParityTest {

    /** Time-varying fields legitimately differing between two calls; scrubbed before compare. */
    private static final Set<String> VOLATILE_FIELDS =
        Set.of("uptime", "startedAt", "startTime", "loadedAt");

    private static TestProjectHelper helper;
    private static ToolRegistry registry;
    private static McpProtocolHandler handler;
    private static ObjectMapper objectMapper;
    private static Map<String, ObjectNode> inputs;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        helper = new TestProjectHelper();
        helper.beforeEach(null);
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path projectPath = helper.getFixturePath("simple-maven");

        JavaLensApplication app = new JavaLensApplication();
        Field svcField = JavaLensApplication.class.getDeclaredField("jdtService");
        svcField.setAccessible(true);
        svcField.set(app, service);
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        ToolRegistry r = new ToolRegistry();
        registryField.set(app, r);
        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        registry = r;

        handler = new McpProtocolHandler(registry);
        handler.processMessage("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}");

        inputs = ToolInvocationInputs.buildValidInputs(objectMapper, projectPath);
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() throws Exception {
        helper.afterEach(null);
    }

    /** Literal anchor: the exact number of tools the MCP surface must expose. */
    private static final int EXPECTED_TOOL_COUNT = 75;

    @Test
    @DisplayName("registration anchor: exactly 75 tools, and the registry set equals the covered set")
    void registrationAnchor() {
        // A LITERAL count, not derived from the registry - so a tool deleted
        // from registerTools() (count drops 75->74) fails here instead of
        // shipping green (the count-derived assertions elsewhere cannot).
        assertEquals(EXPECTED_TOOL_COUNT, registry.getToolNames().size(),
            "the registered tool count changed - if intentional, update EXPECTED_TOOL_COUNT and "
                + "ToolInvocationInputs; otherwise a tool was added/removed from registerTools()");

        // Bidirectional set equality catches a SWAP (one removed, one added,
        // count unchanged): the real registry must equal the covered set.
        Set<String> registered = new TreeSet<>(registry.getToolNames());
        Set<String> covered = new TreeSet<>(inputs.keySet());
        assertEquals(covered, registered,
            "the set of registered tools diverged from the covered/known set - a tool was added "
                + "without an input, or removed from the registry while still listed");
    }

    @Test
    @DisplayName("every tool delivers through the MCP envelope the same result its execute() path produces")
    void everyRegisteredToolBehavesIdenticallyThroughEnvelope() throws Exception {
        Map<String, String> divergent = new TreeMap<>();
        int compared = 0;

        int id = 1;
        for (String name : new TreeSet<>(registry.getToolNames())) {
            ObjectNode args = inputs.get(name);
            if (args == null) {
                continue; // covered by the gate test
            }
            compared++;

            // Behavioral oracle: the direct execute() result, the path the
            // per-tool behavior tests validate against known fixtures.
            org.javalens.mcp.models.ToolResponse direct =
                registry.getTool(name).orElseThrow().execute(args.deepCopy());
            String expected = canonical(objectMapper.valueToTree(direct));

            // Envelope path: the same input through processMessage.
            ObjectNode call = objectMapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", id++);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", name);
            params.set("arguments", args.deepCopy());
            String response = handler.processMessage(objectMapper.writeValueAsString(call));

            try {
                JsonNode rpc = objectMapper.readTree(response);
                if (rpc.has("error")) {
                    divergent.put(name, "JSON-RPC envelope error: " + rpc.get("error"));
                    continue;
                }
                JsonNode content = rpc.path("result").path("content");
                if (!content.isArray() || content.isEmpty()) {
                    divergent.put(name, "no content array");
                    continue;
                }
                String actual = canonical(objectMapper.readTree(content.get(0).path("text").asText()));
                if (!expected.equals(actual)) {
                    divergent.put(name, "envelope payload != execute() payload\n  execute=" + expected
                        + "\n  envelope=" + actual);
                }
            } catch (Exception e) {
                divergent.put(name, "unparseable response: " + e.getMessage());
            }
        }

        assertTrue(divergent.isEmpty(),
            "Tools whose MCP-envelope behavior diverged from their execute() result: " + divergent);

        // Provably ALL of them: the behavioral comparison ran for every
        // registered tool, none silently skipped. Combined with the gate test,
        // coverage cannot quietly shrink as tools are added.
        assertEquals(registry.getToolNames().size(), compared,
            "every registered tool must be behaviorally compared through the envelope");
    }

    /**
     * Canonical string form of a payload: object keys sorted, ARRAY ELEMENTS
     * sorted (search-match ordering is not part of a tool's contract and
     * legitimately differs between two invocations), volatile and null fields
     * dropped. Two payloads with the same content compare equal regardless of
     * field or element order; genuine content divergence still differs.
     */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
            node.fields().forEachRemaining(e -> {
                if (!VOLATILE_FIELDS.contains(e.getKey()) && !e.getValue().isNull()) {
                    entries.put(e.getKey(), canonical(e.getValue()));
                }
            });
            return entries.toString();
        }
        if (node.isArray()) {
            java.util.List<String> elements = new java.util.ArrayList<>();
            node.forEach(e -> elements.add(canonical(e)));
            java.util.Collections.sort(elements);
            return elements.toString();
        }
        return node.toString();
    }
}
