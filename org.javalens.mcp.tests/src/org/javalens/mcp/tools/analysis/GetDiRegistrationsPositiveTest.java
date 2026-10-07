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
 * First positive-content coverage for get_di_registrations: framework-maven
 * carries real (stubbed-annotation) Spring DI usage, so every category is
 * asserted as the whole payload, through execute() and through the JSON-RPC envelope. The zero-content path is
 * pinned by GetDiRegistrationsToolTest against simple-maven.
 *
 * <p>framework-maven applies @Component to WiredConsumer (line 5), @Service to GreetingService
 * (line 4), @RestController to OrderController (line 7) and StatusController (line 5),
 * @Configuration to AppConfig (line 5), @Bean to AppConfig's bean method (line 8) and
 * @Autowired to WiredConsumer's field (line 8). maxResults caps the matches of each annotation
 * type; each summary count is the exact number of registrations in its category.
 */
class GetDiRegistrationsPositiveTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FW = "src/main/java/com/fw/";

    private GetDiRegistrationsTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("framework-maven");
        tool = new GetDiRegistrationsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("reports exact counts and annotation labels for every DI category")
    void exactDiInventory() {
        // The default maxResults (200) holds every match of each annotation type.
        Map<String, Object> data = registrations(List.of(COMPONENT, SERVICE, ORDER_CONTROLLER, STATUS_CONTROLLER));
        assertPayload(objectMapper.createObjectNode(), payload(data, 7, false));
    }

    @Test
    @DisplayName("maxResults=1 keeps one match of each annotation type, counts every registration and reports the cut")
    void maxResultsOne_exactCountsAndTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);
        Map<String, Object> data = registrations(List.of(COMPONENT, SERVICE, ORDER_CONTROLLER));
        assertPayload(args, payload(data, 6, true));
    }

    @Test
    @DisplayName("maxResults=2 holds every match of each annotation type: all seven registrations, not truncated")
    void maxResultsTwo_everythingNotTruncated() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 2);
        Map<String, Object> data = registrations(List.of(COMPONENT, SERVICE, ORDER_CONTROLLER, STATUS_CONTROLLER));
        assertPayload(args, payload(data, 7, false));
    }

    // ========== exact payload helpers ==========

    /*
     * Each registration is matched on the annotation's type name, one column after the @. Raw
     * offsets depend on line endings, so they are removed; line and column pin each match.
     */
    private static final Map<String, Object> COMPONENT =
        registration("WiredConsumer.java", "Component", 5, 1, "@Component");
    private static final Map<String, Object> SERVICE =
        registration("GreetingService.java", "Service", 4, 1, "@Service");
    private static final Map<String, Object> ORDER_CONTROLLER =
        registration("OrderController.java", "RestController", 7, 1, "@RestController");
    private static final Map<String, Object> STATUS_CONTROLLER =
        registration("StatusController.java", "RestController", 5, 1, "@RestController");
    private static final Map<String, Object> CONFIGURATION =
        registration("AppConfig.java", "Configuration", 5, 1, "@Configuration");
    private static final Map<String, Object> BEAN = registration("AppConfig.java", "Bean", 8, 5, "@Bean");
    private static final Map<String, Object> AUTOWIRED =
        registration("WiredConsumer.java", "Autowired", 8, 5, "@Autowired");

    private static Map<String, Object> registration(String file, String annotation, int line, int column,
                                                    String context) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("filePath", FW + file);
        entry.put("length", annotation.length());
        entry.put("line", line);
        entry.put("column", column);
        entry.put("context", context);
        entry.put("annotation", "@" + annotation);
        return entry;
    }

    /** The data listing {@code components}; the other categories hold their one registration each. */
    private static Map<String, Object> registrations(List<Map<String, Object>> components) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("components", 4);
        summary.put("configurations", 1);
        summary.put("beans", 1);
        summary.put("injectionPoints", 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("components", components);
        data.put("configurations", List.of(CONFIGURATION));
        data.put("beans", List.of(BEAN));
        data.put("injectionPoints", List.of(AUTOWIRED));
        return data;
    }

    /** The success payload of {@code data}: seven registrations, {@code returned} of them listed. */
    private static Map<String, Object> payload(Map<String, Object> data, int returned, boolean truncated) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", 7);
        meta.put("returnedCount", returned);
        meta.put("truncated", truncated);
        meta.put("suggestedNextTools", List.of(
            "find_references to trace a specific dependency",
            "analyze_type for details on a registered component"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, each without its matches' raw offsets.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = withoutOffsets(objectMapper.readTree(
                objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = withoutOffsets(envelope.assertEnvelopeFidelity("get_di_registrations", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /** {@code payload} without the offset of each match in the four category lists. */
    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        for (String category : List.of("components", "configurations", "beans", "injectionPoints")) {
            copy.path("data").path(category).forEach(match -> ((ObjectNode) match).remove("offset"));
        }
        return copy;
    }
}
