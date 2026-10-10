package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_dependency_graph on simple-maven. Every expectation is the whole payload, through execute()
 * and through the JSON-RPC envelope; a graph has no defined order, so nodes are compared sorted by
 * name and edges by target.
 *
 * <p>UserService imports Calculator, ArrayList and List; its fields are a List and a Calculator,
 * its methods take Strings, return a List and otherwise primitives. Calculator depends on no
 * type. At package scope com.example covers its subpackages: cycledemo.a and cycledemo.b import
 * each other, service imports com.example.Calculator, staticcycle.x and staticcycle.y statically
 * import each other's methods, and the test sources in com.example import org.junit (4 imports),
 * org.junit.jupiter.api (8), org.junit.jupiter.params (1) and org.junit.jupiter.params.provider
 * (1), none of them resolvable. org.junit is not a JDK package, so every edge is internal.
 */
class GetDependencyGraphToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDependencyGraphTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDependencyGraphTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @Test @DisplayName("analyzes type dependencies: UserService depends on Calculator only, by its import and its field")
    void analyzesTypeDependencies() {
        assertPayload(args("type", USER_SERVICE, null, null), USER_SERVICE_INTERNAL);
    }

    @Test @DisplayName("analyzes package dependencies of com.example and its subpackages")
    void analyzesPackageDependencies() {
        String example = "com.example";
        assertPayload(args("package", example, null, null), graph("package", example,
            List.of(
                pkg(example),
                pkg("com.example.cycledemo.a"),
                pkg("com.example.cycledemo.b"),
                pkg("com.example.service"),
                pkg("com.example.staticcycle.x"),
                pkg("com.example.staticcycle.y"),
                pkg("org.junit"),
                pkg("org.junit.jupiter.api"),
                pkg("org.junit.jupiter.params"),
                pkg("org.junit.jupiter.params.provider")),
            List.of(
                edge("com.example.service", example, 1),
                edge("com.example.cycledemo.b", "com.example.cycledemo.a", 1),
                edge("com.example.cycledemo.a", "com.example.cycledemo.b", 1),
                edge("com.example.staticcycle.y", "com.example.staticcycle.x", 1),
                edge("com.example.staticcycle.x", "com.example.staticcycle.y", 1),
                edge(example, "org.junit", 4),
                edge(example, "org.junit.jupiter.api", 8),
                edge(example, "org.junit.jupiter.params", 1),
                edge(example, "org.junit.jupiter.params.provider", 1)),
            9, 0));
    }

    @Test @DisplayName("includeExternal=true surfaces JDK nodes (List/ArrayList/String) with exact summary")
    void respectsOptions() {
        // Calculator has no own deps and JDK types have no source CU, so depth=2 adds no
        // dependency to UserService's direct ones: Calculator by import and field, ArrayList by
        // import, List by import, field and getUsers' return type, String by two parameters.
        // Visiting Calculator at depth 2 gives its node its own kind, class.
        assertPayload(args("type", USER_SERVICE, 2, true), graph("type", USER_SERVICE,
            List.of(
                node(CALCULATOR, "class", "com.example"),
                node(USER_SERVICE, "class", "com.example.service"),
                node("java.lang.String", "type", "java.lang"),
                node("java.util.ArrayList", "import", "java.util"),
                node("java.util.List", "import", "java.util")),
            List.of(
                edge(USER_SERVICE, CALCULATOR, 2),
                edge(USER_SERVICE, "java.lang.String", 2),
                edge(USER_SERVICE, "java.util.ArrayList", 1),
                edge(USER_SERVICE, "java.util.List", 3)),
            1, 3));
    }

    @Test @DisplayName("missing scope/name each yield exact INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noScope = objectMapper.createObjectNode();
        noScope.put("name", CALCULATOR);
        ObjectNode noName = objectMapper.createObjectNode();
        noName.put("scope", "type");
        assertAll(
            () -> assertPayload(noScope, refusal("Invalid parameter 'scope': Required (type or package)")),
            () -> assertPayload(noName, refusal("Invalid parameter 'name': Required")));
    }

    @Test @DisplayName("bad scope -> INVALID_PARAMETER; unknown type -> SYMBOL_NOT_FOUND")
    void handlesInvalidInputs() {
        assertAll(
            () -> assertPayload(args("invalid", CALCULATOR, null, null),
                refusal("Invalid parameter 'scope': Must be 'type' or 'package'")),
            () -> assertPayload(args("type", "com.nonexistent.Type", null, null),
                symbolNotFound("com.nonexistent.Type")));
    }

    @Test @DisplayName("a package with no source package of that name, unknown or a library's such as java.util, is SYMBOL_NOT_FOUND")
    void unknownPackage_isSymbolNotFound() {
        assertAll(
            () -> assertPayload(args("package", "com.nonexistent", null, null),
                symbolNotFound("No source package named com.nonexistent")),
            () -> assertPayload(args("package", "java.util", null, null),
                symbolNotFound("No source package named java.util")));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("UserService dependency graph at depth 2: Calculator, used as field type and import, is its one internal node, visited as a class")
    void userService_dependencyOnCalculator() {
        assertPayload(args("type", USER_SERVICE, 2, null), graph("type", USER_SERVICE,
            List.of(node(CALCULATOR, "class", "com.example"), node(USER_SERVICE, "class", "com.example.service")),
            List.of(edge(USER_SERVICE, CALCULATOR, 2)), 1, 0));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Top-level response has scope, root, nodes, edges, summary")
    void responseShape_includesAllFields() {
        assertPayload(args("type", USER_SERVICE, null, null), USER_SERVICE_INTERNAL);
    }

    @Test
    @DisplayName("includeExternal=false omits JDK nodes (java.lang, java.util)")
    void includeExternalFalse_omitsJdkNodes() {
        assertPayload(args("type", USER_SERVICE, null, false), USER_SERVICE_INTERNAL);
    }

    @Test
    @DisplayName("scope='invalid' rejected; missing scope rejected; unknown type rejected")
    void invalidArgs_rejected() {
        ObjectNode noScope = objectMapper.createObjectNode();
        noScope.put("name", CALCULATOR);
        assertAll(
            () -> assertPayload(args("invalid", CALCULATOR, null, null),
                refusal("Invalid parameter 'scope': Must be 'type' or 'package'")),
            () -> assertPayload(noScope, refusal("Invalid parameter 'scope': Required (type or package)")),
            () -> assertPayload(args("type", "com.nonexistent.Whatever", null, null),
                symbolNotFound("com.nonexistent.Whatever")));
    }

    @Test
    @DisplayName("Package scope: static-import dependency resolves to the target package, not to the type")
    void packageScope_staticImportEdgeResolvesToTargetPackage() {
        // staticcycle.x imports `static com.example.staticcycle.y.Y.yValue;`: the dependency is
        // on the package com.example.staticcycle.y, not on the class Y.
        String x = "com.example.staticcycle.x";
        String y = "com.example.staticcycle.y";
        assertPayload(args("package", x, null, null), graph("package", x,
            List.of(pkg(x), pkg(y)), List.of(edge(x, y, 1)), 1, 0));
    }

    @Test
    @DisplayName("Calculator scope=type: root is reported verbatim and Calculator depends on nothing")
    void rootEchoed() {
        assertPayload(args("type", CALCULATOR, null, null), graph("type", CALCULATOR,
            List.of(node(CALCULATOR, "class", "com.example")), List.of(), 0, 0));
    }

    // ========== Exact graph content ==========

    @Test
    @DisplayName("UserService type graph: nodes are exactly {UserService, Calculator}, one edge count 2")
    void userService_exactInternalGraph() {
        // Calculator is first met as an import, so its kind is import; the field adds the
        // second count.
        assertPayload(args("type", USER_SERVICE, null, null), USER_SERVICE_INTERNAL);
    }

    @Test
    @DisplayName("Through the real MCP envelope: UserService->Calculator edge has count 2")
    void envelope_userService_edgeCountTwo() {
        ObjectNode args = envelope.args();
        args.put("scope", "type");
        args.put("name", USER_SERVICE);
        assertPayload(args, USER_SERVICE_INTERNAL);
    }

    // ========== exact payload helpers ==========

    private static final String USER_SERVICE = "com.example.service.UserService";
    private static final String CALCULATOR = "com.example.Calculator";

    private static final Map<String, Object> USER_SERVICE_INTERNAL = graph("type", USER_SERVICE,
        List.of(node(CALCULATOR, "import", "com.example"), node(USER_SERVICE, "class", "com.example.service")),
        List.of(edge(USER_SERVICE, CALCULATOR, 2)), 1, 0);

    private ObjectNode args(String scope, String name, Integer depth, Boolean includeExternal) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", scope);
        args.put("name", name);
        if (depth != null) {
            args.put("depth", depth);
        }
        if (includeExternal != null) {
            args.put("includeExternal", includeExternal);
        }
        return args;
    }

    private static Map<String, Object> node(String name, String kind, String pkg) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("kind", kind);
        node.put("package", pkg);
        return node;
    }

    /** A node denoting a package: it has no package of its own. */
    private static Map<String, Object> pkg(String name) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("kind", "package");
        return node;
    }

    private static Map<String, Object> edge(String from, String to, int count) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("count", count);
        return edge;
    }

    /** The success payload of a graph whose nodes are sorted by name and edges by target. */
    private static Map<String, Object> graph(String scope, String root, List<Map<String, Object>> nodes,
                                             List<Map<String, Object>> edges, int internal, int external) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNodes", nodes.size());
        summary.put("totalEdges", edges.size());
        summary.put("internalDependencies", internal);
        summary.put("externalDependencies", external);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("root", root);
        data.put("nodes", nodes);
        data.put("edges", edges);
        data.put("summary", summary);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", nodes.size());
        meta.put("returnedCount", nodes.size());
        meta.put("suggestedNextTools", List.of(
            "find_circular_dependencies to detect cycles",
            "get_type_hierarchy for inheritance analysis"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private static Map<String, Object> refusal(String message) {
        return Map.of("success", false, "error", Map.of("code", "INVALID_PARAMETER", "message", message));
    }

    private static Map<String, Object> symbolNotFound(String name) {
        return Map.of("success", false, "error", Map.of(
            "code", "SYMBOL_NOT_FOUND",
            "message", "Symbol not found: " + name,
            "hint", "Use search_symbols to find available symbols"));
    }

    /**
     * The payload through execute(), read back from the JSON text it serializes to, and through
     * the JSON-RPC envelope both equal {@code expected}, nodes sorted by name and edges by target.
     */
    private void assertPayload(ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = sorted(objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = sorted(envelope.assertEnvelopeFidelity("get_dependency_graph", args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private static JsonNode sorted(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        if (copy.path("data") instanceof ObjectNode data) {
            sort(data, "nodes", "name");
            sort(data, "edges", "to");
        }
        return copy;
    }

    private static void sort(ObjectNode data, String field, String key) {
        if (data.get(field) instanceof ArrayNode array) {
            List<JsonNode> elements = new ArrayList<>();
            array.forEach(elements::add);
            elements.sort(Comparator.comparing(element -> element.path(key).asText()));
            data.putArray(field).addAll(elements);
        }
    }
}
