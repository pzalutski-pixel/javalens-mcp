package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MCP integration detection gate: every one of the 75 tools is driven
 * through the REAL {@link McpProtocolHandler#processMessage} against the
 * deterministic simple-maven fixture, and its payload is asserted EXACT
 * against a frozen, reviewed golden file. This is independent of the tool's
 * own code path: a golden is a fixed known-good value, so any drift — a wrong
 * count, an empty list on a valid query (the #32 shape), a swapped name, a
 * dropped field — fails. (Contrast the differential ProtocolParityTest, whose
 * "expected" is the live tool and therefore moves with a bug.)
 *
 * <p>Goldens live in {@code test-resources/protocol-goldens/<tool>.json} as
 * canonical payloads (object keys sorted; array elements sorted, since
 * search-match order is not a tool contract; volatile time fields and nulls
 * dropped). Regenerate intentionally and review the diff:
 * {@code mvn ... -Djavalens.regen.goldens=true -Dtest=McpGoldenIntegrationTest}.
 */
class McpGoldenIntegrationTest {

    private static final Set<String> VOLATILE_FIELDS =
        Set.of("uptime", "startedAt", "startTime", "loadedAt");
    /**
     * Tools whose payload is inherently environment-specific (absolute JDK /
     * classpath paths that vary by machine) and so cannot be pinned to a
     * portable golden. Their correctness is asserted structurally in their own
     * unit test (e.g. GetClasspathInfoToolTest). Excluded from the golden gate,
     * NOT from registration coverage.
     */
    private static final Set<String> ENV_SPECIFIC = Set.of("get_classpath_info");
    /** Per-session workspace project name (javalens-<fixture>-<8hex>); masked for determinism. */
    private static final java.util.regex.Pattern WORKSPACE_UUID =
        java.util.regex.Pattern.compile("javalens-[A-Za-z0-9._-]+?-[0-9a-f]{8}");
    private static final boolean REGEN = Boolean.getBoolean("javalens.regen.goldens");

    private static ToolRegistry registry;
    private static McpProtocolHandler handler;
    private static ObjectMapper objectMapper;
    private static Map<String, ObjectNode> inputs;
    private static Path goldensDir;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        TestProjectHelper helper = new TestProjectHelper();
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
        goldensDir = resolveGoldensDir();
    }

    private static Path resolveGoldensDir() {
        for (String candidate : new String[]{
            "test-resources/protocol-goldens",
            "org.javalens.mcp.tests/test-resources/protocol-goldens",
            "../org.javalens.mcp.tests/test-resources/protocol-goldens"
        }) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p) || REGEN) {
                return p.toAbsolutePath();
            }
        }
        return Path.of("test-resources/protocol-goldens").toAbsolutePath();
    }

    /** The canonical payload an MCP tools/call returns for the given tool. */
    private static JsonNode envelopePayload(String name, ObjectNode args) throws Exception {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("jsonrpc", "2.0");
        call.put("id", 1);
        call.put("method", "tools/call");
        ObjectNode params = call.putObject("params");
        params.put("name", name);
        params.set("arguments", args.deepCopy());
        JsonNode rpc = objectMapper.readTree(handler.processMessage(objectMapper.writeValueAsString(call)));
        assertNull(rpc.get("error"), () -> name + ": JSON-RPC envelope error: " + rpc.get("error"));
        String text = rpc.path("result").path("content").get(0).path("text").asText();
        return canonical(objectMapper.readTree(text));
    }

    @Test
    @DisplayName("every tool's MCP payload matches its frozen golden exactly (deterministic detection gate)")
    void everyToolMatchesItsGolden() throws Exception {
        if (REGEN) {
            Files.createDirectories(goldensDir);
        }
        Map<String, String> mismatches = new TreeMap<>();
        List<String> regenerated = new ArrayList<>();

        for (String name : new TreeSet<>(registry.getToolNames())) {
            if (ENV_SPECIFIC.contains(name)) {
                continue;
            }
            ObjectNode args = inputs.get(name);
            assertNotNull(args, name + ": no ToolInvocationInputs entry (every tool must have one)");

            String actual = normalize(objectMapper.writeValueAsString(envelopePayload(name, args)));
            Path golden = goldensDir.resolve(name + ".json");

            if (REGEN) {
                Files.writeString(golden, actual + "\n");
                regenerated.add(name);
                continue;
            }
            if (!Files.exists(golden)) {
                mismatches.put(name, "MISSING golden " + golden + " — run with -Djavalens.regen.goldens=true and review");
                continue;
            }
            String expected = Files.readString(golden).strip();
            if (!expected.equals(actual.strip())) {
                mismatches.put(name, "payload drifted from golden\n--- expected (golden)\n" + expected
                    + "\n--- actual (MCP call)\n" + actual);
            }
        }

        if (REGEN) {
            System.out.println("[goldens] regenerated " + regenerated.size() + " golden payloads in " + goldensDir);
            return;
        }
        assertTrue(mismatches.isEmpty(),
            "Tools whose MCP payload no longer matches their reviewed golden:\n"
                + String.join("\n\n", mismatches.entrySet().stream()
                    .map(e -> "### " + e.getKey() + "\n" + e.getValue()).toList()));
    }

    @Test
    @DisplayName("a golden exists for every registered tool (no tool ships without an MCP detection anchor)")
    void everyToolHasAGolden() {
        if (REGEN) {
            return;
        }
        Set<String> missing = new TreeSet<>();
        for (String name : registry.getToolNames()) {
            if (ENV_SPECIFIC.contains(name)) {
                continue;
            }
            if (!Files.exists(goldensDir.resolve(name + ".json"))) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
            "Registered tools with no golden — add one (regen + review): " + missing);
    }

    /** Mask the per-session workspace project name so goldens are deterministic across runs/machines. */
    private static String normalize(String payload) {
        return WORKSPACE_UUID.matcher(payload).replaceAll("javalens-WS");
    }

    /**
     * Canonical form: object keys sorted, ARRAY ELEMENTS sorted (match order
     * is not a tool contract; the few order-contractual tools assert order in
     * their dedicated protocol tests), volatile and null fields dropped.
     */
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (!VOLATILE_FIELDS.contains(key) && !value.isNull()) {
                    out.set(key, canonical(value));
                }
            }
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(e -> elements.add(canonical(e)));
            elements.sort((a, b) -> a.toString().compareTo(b.toString()));
            ArrayNode out = objectMapper.createArrayNode();
            elements.forEach(out::add);
            return out;
        }
        return node;
    }
}
