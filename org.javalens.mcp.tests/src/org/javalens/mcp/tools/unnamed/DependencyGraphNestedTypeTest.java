package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.sortedBy;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * get_dependency_graph names every node that denotes a type by the type's fully qualified name
 * with '$' between nested types, the form the type's own node takes when the graph descends into
 * it: one node per type, however the source names it (an import, a simple name, a name written
 * qualified).
 *
 * <p>Route (codegen-names) imports the member type com.example.gen.Desk.Voucher and names it in
 * three signatures, once written qualified as Desk.Voucher. At depth 2 the graph descends into
 * Voucher, whose unit Desk.java imports com.example.gen.model.Stamp and whose field has the type
 * Stamp; each import of a unit counts toward every type the graph descends into in that unit. A
 * node's kind is the kind of its first edge until the graph descends into it, then its type's
 * kind. The nodes and edges of a graph come in no defined order, so both are compared sorted.
 */
class DependencyGraphNestedTypeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(CodegenNames.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_dependency_graph of Route at depth 2 gives the member type Voucher one node, com.example.gen.Desk$Voucher, with its own edge to Stamp")
    void nestedTypeAtDepthTwo() {
        String route = "com.example.gen.Route";
        String voucher = "com.example.gen.Desk$Voucher";
        String stamp = "com.example.gen.model.Stamp";
        // Route -> Voucher: the import, the field, renew's return type and its parameter.
        // Voucher -> Stamp: Desk.java's import and the field stamp.
        Map<String, Object> data = map(
            "scope", "type",
            "root", route,
            "nodes", List.of(
                map("name", voucher, "kind", "class", "package", "com.example.gen"),
                map("name", route, "kind", "class", "package", "com.example.gen"),
                map("name", stamp, "kind", "import", "package", "com.example.gen.model")),
            "edges", List.of(
                map("from", route, "to", voucher, "count", 4),
                map("from", voucher, "to", stamp, "count", 2)),
            "summary", map("totalNodes", 3, "totalEdges", 2, "internalDependencies", 2, "externalDependencies", 0));
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", route);
        args.put("depth", 2);
        Map<String, Object> meta = map(
            "totalCount", 3,
            "returnedCount", 3,
            "suggestedNextTools", List.of(
                "find_circular_dependencies to detect cycles",
                "get_type_hierarchy for inheritance analysis"));
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, args, success(data, meta), sorted());
    }

    /** The payload with its nodes sorted by name and its edges by target. */
    private static UnaryOperator<JsonNode> sorted() {
        UnaryOperator<JsonNode> nodes = sortedBy("name", "data", "nodes");
        UnaryOperator<JsonNode> edges = sortedBy("to", "data", "edges");
        return payload -> edges.apply(nodes.apply(payload));
    }
}
