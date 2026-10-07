package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_di_registrations on simple-maven, which has no DI annotation types, and on framework-maven,
 * whose stubbed Spring annotations register @Component WiredConsumer (line 5), @Service
 * GreetingService (line 4), @RestController OrderController (line 7) and StatusController (line
 * 5), @Configuration AppConfig (line 5), AppConfig's @Bean method (line 8) and WiredConsumer's
 * @Autowired field (line 8). Every expectation is the whole payload, through execute() and through
 * the JSON-RPC envelope. Each registration is matched on the annotation's type name, one column
 * after the @; raw offsets depend on line endings, so they are removed.
 */
class GetDiRegistrationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDiRegistrationsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiRegistrationsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("should return empty results for non-Spring project")
    void returnsEmptyForNonSpringProject() {
        assertPayload(tool, envelope, objectMapper.createObjectNode(), none());
    }

    @Test
    @DisplayName("should return all expected categories in output, each empty for simple-maven")
    void returnsAllCategories() {
        assertPayload(tool, envelope, objectMapper.createObjectNode(), none());
    }

    @Test
    @DisplayName("maxResults=1 on a project without registrations cuts nothing")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        assertPayload(tool, envelope, args, none());
    }

    @Test
    @DisplayName("summary keys are exactly {components, configurations, beans, injectionPoints}")
    void summary_hasExactKeys() {
        assertPayload(tool, envelope, objectMapper.createObjectNode(), none());
    }

    @Test
    @DisplayName("Top-level data has summary + four category lists; lists are all empty for non-Spring project")
    void categories_emptyListsForNonSpring() {
        assertPayload(tool, envelope, objectMapper.createObjectNode(), none());
    }

    @Test
    @DisplayName("Each summary count equals the corresponding list's size")
    void summaryCounts_matchListSizes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 200);
        assertPayload(tool, envelope, args, none());
    }

    @Test
    @DisplayName("framework-maven: exact DI inventory - 4 components, 1 configuration, 1 bean, 1 injection point")
    void frameworkMaven_exactRegistrationInventory() throws Exception {
        JdtServiceImpl svc = helper.loadProject("framework-maven");
        assertPayload(new GetDiRegistrationsTool(() -> svc), new EnvelopeHarness(svc),
            objectMapper.createObjectNode(), frameworkMaven());
    }

    @Test
    @DisplayName("Through the real registerTools() wiring: the framework-maven DI inventory (4/1/1/1) survives the envelope")
    void envelope_frameworkMaven_exactInventory() throws Exception {
        JdtServiceImpl svc = helper.loadProject("framework-maven");
        EnvelopeHarness fwEnvelope = new EnvelopeHarness(svc);
        assertPayload(new GetDiRegistrationsTool(() -> svc), fwEnvelope, fwEnvelope.args(), frameworkMaven());
    }

    // ========== exact payload helpers ==========

    private static final List<String> NEXT_TOOLS = List.of(
        "find_references to trace a specific dependency",
        "analyze_type for details on a registered component");

    /** No registration in any category. */
    private static Map<String, Object> none() {
        return payload(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
    }

    /** framework-maven's seven registrations, each annotation type's matches by file path. */
    private static Map<String, Object> frameworkMaven() {
        String fw = "src/main/java/com/fw/";
        return payload(4, 1, 1, 1,
            List.of(
                registration(fw + "WiredConsumer.java", "Component", 5, 1),
                registration(fw + "GreetingService.java", "Service", 4, 1),
                registration(fw + "OrderController.java", "RestController", 7, 1),
                registration(fw + "StatusController.java", "RestController", 5, 1)),
            List.of(registration(fw + "AppConfig.java", "Configuration", 5, 1)),
            List.of(registration(fw + "AppConfig.java", "Bean", 8, 5)),
            List.of(registration(fw + "WiredConsumer.java", "Autowired", 8, 5)));
    }

    private static Map<String, Object> registration(String file, String annotation, int line, int column) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("filePath", file);
        entry.put("length", annotation.length());
        entry.put("line", line);
        entry.put("column", column);
        entry.put("context", "@" + annotation);
        entry.put("annotation", "@" + annotation);
        return entry;
    }

    /** The success payload listing every registration of each category, none cut. */
    private static Map<String, Object> payload(int components, int configurations, int beans, int injectionPoints,
                                               List<Map<String, Object>> componentList,
                                               List<Map<String, Object>> configurationList,
                                               List<Map<String, Object>> beanList,
                                               List<Map<String, Object>> injectionPointList) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("components", components);
        summary.put("configurations", configurations);
        summary.put("beans", beans);
        summary.put("injectionPoints", injectionPoints);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("components", componentList);
        data.put("configurations", configurationList);
        data.put("beans", beanList);
        data.put("injectionPoints", injectionPointList);
        int total = components + configurations + beans + injectionPoints;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", total);
        meta.put("truncated", false);
        meta.put("suggestedNextTools", NEXT_TOOLS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload of {@code diTool} through execute(), read back from the JSON text it serializes
     * to, and through {@code harness}'s JSON-RPC envelope both equal {@code expected}, each
     * without its matches' raw offsets.
     */
    private void assertPayload(GetDiRegistrationsTool diTool, EnvelopeHarness harness, ObjectNode args,
                               Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = withoutOffsets(objectMapper.readTree(
                objectMapper.writeValueAsString(diTool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = withoutOffsets(harness.assertEnvelopeFidelity("get_di_registrations", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        for (String category : List.of("components", "configurations", "beans", "injectionPoints")) {
            copy.path("data").path(category).forEach(match -> ((ObjectNode) match).remove("offset"));
        }
        return copy;
    }
}
