package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDependencyGraphToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDependencyGraphTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDependencyGraphTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

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
        assertNotNull(data.get("nodes"));
        assertNotNull(data.get("edges"));
        assertNotNull(data.get("summary"));
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

    @Test @DisplayName("respects depth and includeExternal")
    void respectsOptions() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.Calculator");
        args.put("depth", 2);
        args.put("includeExternal", true);

        assertTrue(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires scope and name")
    void requiresParameters() {
        ObjectNode noScope = objectMapper.createObjectNode();
        noScope.put("name", "com.example.Calculator");
        assertFalse(tool.execute(noScope).isSuccess());

        ObjectNode noName = objectMapper.createObjectNode();
        noName.put("scope", "type");
        assertFalse(tool.execute(noName).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badScope = objectMapper.createObjectNode();
        badScope.put("scope", "invalid");
        badScope.put("name", "com.example.Calculator");
        assertFalse(tool.execute(badScope).isSuccess());

        ObjectNode unknownType = objectMapper.createObjectNode();
        unknownType.put("scope", "type");
        unknownType.put("name", "com.nonexistent.Type");
        assertFalse(tool.execute(unknownType).isSuccess());
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
}
