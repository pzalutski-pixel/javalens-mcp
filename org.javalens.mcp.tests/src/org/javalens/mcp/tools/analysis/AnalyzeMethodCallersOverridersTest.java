package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * analyze_method draws its callers and overriding methods from every match of its searches:
 * callers are the distinct calling methods found among all references to the method, the first
 * {@code maxCallers} of them in search order, callers.count is the number of all of them, and
 * overriddenBy lists every overriding method. meta.truncated is true when the callers are cut.
 *
 * <p>On the capped-searches fixture, Dispatch.target is called four times from first() and then
 * once from second(); Handlers.handle is overridden by its twenty-one nested subclasses
 * Handler01 to Handler21. Every expectation is the whole payload, through execute() and through
 * the JSON-RPC envelope.
 */
class AnalyzeMethodCallersOverridersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String DISPATCH = "src/main/java/org/example/capped/Dispatch.java";
    private static final String HANDLERS = "src/main/java/org/example/capped/Handlers.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "analyze_type for declaring type analysis",
        "find_references for all usages",
        "go_to_definition to navigate to a callee");

    private final ObjectMapper mapper = new ObjectMapper();
    private AnalyzeMethodTool tool;
    private EnvelopeHarness envelope;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("capped-searches");
        tool = new AnalyzeMethodTool(() -> service);
        envelope = new EnvelopeHarness(service);
        root = helper.getFixturePath("capped-searches");
    }

    @Test
    @DisplayName("maxCallers=2 returns both distinct callers although the first four references are all in first()")
    void callers_areDistinctAmongEveryReference() {
        ObjectNode args = at(DISPATCH, 7, 15);
        args.put("maxCallers", 2);
        assertPayload(args, payload(
            methodInfo("target", List.of("static"), "org.example.capped.Dispatch", "target(): int", "int", DISPATCH),
            Map.of("count", 2, "list", List.of(caller("first", 12), caller("second", 16))),
            Map.of()));
    }

    @Test
    @DisplayName("maxCallers=1 returns the first distinct caller in search order, counts both callers and reports the cut")
    void callers_maxCallersOne_isTheFirstCaller() {
        ObjectNode args = at(DISPATCH, 7, 15);
        args.put("maxCallers", 1);
        assertPayload(args, payload(
            methodInfo("target", List.of("static"), "org.example.capped.Dispatch", "target(): int", "int", DISPATCH),
            Map.of("count", 2, "list", List.of(caller("first", 12))),
            Map.of()));
    }

    @Test
    @DisplayName("overriddenBy lists all 21 overriding methods, Handler01 to Handler21 in source order")
    void overriddenBy_listsEveryOverridingMethod() {
        List<Map<String, Object>> overriders = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            String type = String.format("Handler%02d", i);
            overriders.add(Map.of(
                "method", "handle",
                "qualifiedType", "org.example.capped.Handlers$" + type));
        }
        assertPayload(at(HANDLERS, 7, 16), payload(
            methodInfo("handle", List.of("public"), "org.example.capped.Handlers", "handle(): void", "void", HANDLERS),
            Map.of("count", 0, "list", List.of()),
            Map.of("overriddenBy", overriders)));
    }

    private ObjectNode at(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    /** The payload through execute() and through the JSON-RPC envelope both equal {@code expected}. */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        JsonNode direct = mapper.valueToTree(tool.execute(args.deepCopy()));
        JsonNode wire = envelope.assertEnvelopeFidelity("analyze_method", args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    /**
     * A method with no parameters, no declared exceptions and no callees; meta counts its
     * callers, all of them and those listed.
     */
    private static Map<String, Object> payload(Map<String, Object> method, Map<String, Object> callers,
                                               Map<String, Object> overrides) {
        int total = (int) callers.get("count");
        int returned = ((List<?>) callers.get("list")).size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", method);
        data.put("parameters", List.of());
        data.put("callers", callers);
        data.put("callees", Map.of("count", 0, "list", List.of()));
        data.put("overrides", overrides);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", Map.of(
            "totalCount", total,
            "returnedCount", returned,
            "truncated", returned < total,
            "suggestedNextTools", NEXT_TOOLS));
        return payload;
    }

    /** A method declared on line 7 of {@code file}. */
    private static Map<String, Object> methodInfo(String name, List<String> modifiers, String declaringType,
                                                  String signature, String returnType, String file) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("constructor", false);
        info.put("modifiers", modifiers);
        info.put("declaringType", declaringType);
        info.put("signature", signature);
        info.put("returnType", returnType);
        info.put("file", file);
        info.put("line", 7);
        return info;
    }

    /** A caller in Dispatch whose first call to target is on {@code callLine}. */
    private static Map<String, Object> caller(String method, int callLine) {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("method", method);
        caller.put("qualifiedType", "org.example.capped.Dispatch");
        caller.put("file", DISPATCH);
        caller.put("callLine", callLine);
        return caller;
    }
}
