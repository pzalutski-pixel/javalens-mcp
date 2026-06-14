package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private Set<String> nodeNames(Map<String, Object> data) {
        Set<String> names = new java.util.TreeSet<>();
        for (Object n : (List<Object>) data.get("nodes")) {
            names.add((String) ((Map<String, Object>) n).get("name"));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("edges");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> nodeKinds(Map<String, Object> data) {
        Map<String, String> kinds = new java.util.HashMap<>();
        for (Object n : (List<Object>) data.get("nodes")) {
            Map<String, Object> node = (Map<String, Object>) n;
            kinds.put((String) node.get("name"), (String) node.get("kind"));
        }
        return kinds;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> data) {
        return (Map<String, Object>) data.get("summary");
    }

    @Test @DisplayName("analyzes type dependencies")
    void analyzesTypeDependencies() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("type", data.get("scope"));
        assertEquals("com.example.service.UserService", data.get("root"));
        @SuppressWarnings("unchecked")
        List<?> nodes = (List<?>) data.get("nodes");
        @SuppressWarnings("unchecked")
        List<?> edges = (List<?>) data.get("edges");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(nodes, "nodes list missing");
        assertNotNull(edges, "edges list missing");
        assertNotNull(summary, "summary block missing");
        assertFalse(nodes.isEmpty(),
            "UserService has dependencies; nodes must be non-empty");
    }

    @Test @DisplayName("analyzes package dependencies")
    void analyzesPackageDependencies() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "package");
        args.put("name", "com.example");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals("package", getData(r).get("scope"));
        assertEquals("com.example", getData(r).get("root"));
    }

    @Test @DisplayName("includeExternal=true surfaces JDK nodes (List/ArrayList/String) with exact summary")
    void respectsOptions() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        args.put("depth", 2);
        args.put("includeExternal", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Calculator has no own deps and JDK types have no source CU, so depth=2 collapses
        // to UserService's direct dependencies. With externals included: imports
        // Calculator/ArrayList/List, fields List+Calculator, String method params.
        assertEquals(Set.of(
            "com.example.service.UserService", "com.example.Calculator",
            "java.util.ArrayList", "java.util.List", "java.lang.String"),
            nodeNames(data));

        Map<String, Object> s = summary(data);
        assertEquals(5, ((Number) s.get("totalNodes")).intValue());
        assertEquals(4, ((Number) s.get("totalEdges")).intValue());
        assertEquals(1, ((Number) s.get("internalDependencies")).intValue());
        assertEquals(3, ((Number) s.get("externalDependencies")).intValue());
    }

    @Test @DisplayName("missing scope/name each yield exact INVALID_PARAMETER")
    void requiresParameters() {
        ObjectNode noScope = objectMapper.createObjectNode();
        noScope.put("name", "com.example.Calculator");
        ToolResponse rNoScope = tool.execute(noScope);
        assertFalse(rNoScope.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, rNoScope.getError().getCode());
        assertEquals("Invalid parameter 'scope': Required (type or package)", rNoScope.getError().getMessage());

        ObjectNode noName = objectMapper.createObjectNode();
        noName.put("scope", "type");
        ToolResponse rNoName = tool.execute(noName);
        assertFalse(rNoName.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, rNoName.getError().getCode());
        assertEquals("Invalid parameter 'name': Required", rNoName.getError().getMessage());
    }

    @Test @DisplayName("bad scope -> INVALID_PARAMETER; unknown type -> SYMBOL_NOT_FOUND")
    void handlesInvalidInputs() {
        ObjectNode badScope = objectMapper.createObjectNode();
        badScope.put("scope", "invalid");
        badScope.put("name", "com.example.Calculator");
        ToolResponse rBad = tool.execute(badScope);
        assertFalse(rBad.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, rBad.getError().getCode());
        assertEquals("Invalid parameter 'scope': Must be 'type' or 'package'", rBad.getError().getMessage());

        ObjectNode unknownType = objectMapper.createObjectNode();
        unknownType.put("scope", "type");
        unknownType.put("name", "com.nonexistent.Type");
        ToolResponse rUnknown = tool.execute(unknownType);
        assertFalse(rUnknown.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND, rUnknown.getError().getCode());
        assertEquals("Symbol not found: com.nonexistent.Type", rUnknown.getError().getMessage());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("UserService dependency graph: nodes include Calculator (field/parameter type used)")
    void userService_dependencyOnCalculator() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        args.put("depth", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        @SuppressWarnings("unchecked")
        java.util.List<Object> nodes = (java.util.List<Object>) data.get("nodes");
        boolean hasCalculator = nodes.stream()
            .map(Object::toString)
            .anyMatch(s -> s.contains("Calculator"));
        assertTrue(hasCalculator,
            "UserService uses Calculator (as field type and method parameter); must appear in graph nodes; got: "
                + nodes);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Top-level response has scope, root, nodes, edges, summary")
    void responseShape_includesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : List.of("scope", "root", "nodes", "edges", "summary")) {
            assertNotNull(data.get(key), key + " missing on response: " + data.keySet());
        }
    }

    @Test
    @DisplayName("includeExternal=false omits JDK nodes (java.lang, java.util)")
    void includeExternalFalse_omitsJdkNodes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        args.put("includeExternal", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Object> nodes = (List<Object>) getData(r).get("nodes");
        for (Object n : nodes) {
            String name = n.toString();
            assertFalse(name.startsWith("java.lang."),
                "Internal-only graph must not include java.lang.* nodes; got: " + name);
        }
    }

    @Test
    @DisplayName("scope='invalid' rejected; missing scope rejected; unknown type rejected")
    void invalidArgs_rejected() {
        // scope=invalid
        ObjectNode bad1 = objectMapper.createObjectNode();
        bad1.put("scope", "invalid");
        bad1.put("name", "com.example.Calculator");
        assertFalse(tool.execute(bad1).isSuccess());

        // missing scope
        ObjectNode bad2 = objectMapper.createObjectNode();
        bad2.put("name", "com.example.Calculator");
        assertFalse(tool.execute(bad2).isSuccess());

        // unknown type
        ObjectNode bad3 = objectMapper.createObjectNode();
        bad3.put("scope", "type");
        bad3.put("name", "com.nonexistent.Whatever");
        assertFalse(tool.execute(bad3).isSuccess());
    }

    @Test
    @DisplayName("Package scope: static-import dependency resolves to the target package, not to the type")
    @SuppressWarnings("unchecked")
    void packageScope_staticImportEdgeResolvesToTargetPackage() {
        // staticcycle.x imports `static com.example.staticcycle.y.Y.yValue;`.
        // At package scope, the dependency must be reported as the target
        // PACKAGE (com.example.staticcycle.y), not the declaring class
        // (com.example.staticcycle.y.Y). Same enumeration gap as A-23 in
        // find_circular_dependencies.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "package");
        args.put("name", "com.example.staticcycle.x");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        List<Map<String, Object>> edges = (List<Map<String, Object>>) getData(r).get("edges");
        boolean hasPackageEdge = edges.stream().anyMatch(e ->
            "com.example.staticcycle.x".equals(e.get("from"))
                && "com.example.staticcycle.y".equals(e.get("to")));
        assertTrue(hasPackageEdge,
            "Static import must yield a package-to-package edge from staticcycle.x to staticcycle.y; "
                + "got edges: " + edges);
    }

    @Test
    @DisplayName("Calculator scope=type: root is reported verbatim")
    void rootEchoed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("com.example.Calculator", getData(r).get("root"));
        assertEquals("type", getData(r).get("scope"));
    }

    // ========== Exact graph content ==========

    @Test
    @DisplayName("UserService type graph: nodes are exactly {UserService, Calculator}, one edge count 2")
    void userService_exactInternalGraph() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "tool failed: " + r.getError());
        Map<String, Object> data = getData(r);

        // Internal dependencies only (includeExternal defaults false). UserService
        // references com.example.Calculator twice — its import and its `calculator`
        // field — collapsing to one edge with count 2. List/String/int are external
        // or primitive and must not appear as nodes.
        assertEquals(Set.of("com.example.service.UserService", "com.example.Calculator"),
            nodeNames(data), "internal graph nodes are exactly UserService and Calculator");

        // Node kinds: the root carries its TypeKindResolver kind; Calculator is first
        // touched by the import loop (putIfAbsent "import" wins over the later field "type").
        Map<String, String> kinds = nodeKinds(data);
        assertEquals("class", kinds.get("com.example.service.UserService"));
        assertEquals("import", kinds.get("com.example.Calculator"));

        List<Map<String, Object>> edges = edges(data);
        assertEquals(1, edges.size(), () -> "exactly one internal edge; got: " + edges);
        Map<String, Object> edge = edges.get(0);
        assertEquals("com.example.service.UserService", edge.get("from"));
        assertEquals("com.example.Calculator", edge.get("to"));
        assertEquals(2, ((Number) edge.get("count")).intValue(),
            "import + field reference must collapse to count 2, not 1");

        Map<String, Object> s = summary(data);
        assertEquals(2, ((Number) s.get("totalNodes")).intValue());
        assertEquals(1, ((Number) s.get("totalEdges")).intValue());
        assertEquals(1, ((Number) s.get("internalDependencies")).intValue());
        assertEquals(0, ((Number) s.get("externalDependencies")).intValue());
    }

    @Test
    @DisplayName("Through the real MCP envelope: UserService->Calculator edge has count 2")
    void envelope_userService_edgeCountTwo() {
        ObjectNode args = envelope.args();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");
        JsonNode payload = envelope.assertEnvelopeFidelity("get_dependency_graph", args);

        assertTrue(payload.get("success").asBoolean(), () -> "failed: " + payload);
        JsonNode edges = payload.get("data").get("edges");
        assertEquals(1, edges.size(), () -> "exactly one internal edge: " + edges);
        JsonNode edge = edges.get(0);
        assertEquals("com.example.Calculator", edge.get("to").asText());
        assertEquals(2, edge.get("count").asInt(),
            "edge multiplicity must survive the envelope and not collapse to 1");
    }
}
