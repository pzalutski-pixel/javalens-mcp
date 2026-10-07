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
 * Pins get_dependency_graph handling of JEP 511 module imports
 * ({@code import module java.base;}), standard in Java 25.
 *
 * <p>A module import is not a package import: it names a module, not a package,
 * so it must not be turned into a package/type node in the graph. The JDT model
 * surfaces it as an on-demand {@code IImportDeclaration} with element name
 * {@code "java.base.*"}, which the string-stripping derivation would otherwise
 * mis-read as a dependency on package {@code java.base}.
 *
 * <p>The fixture {@code ModuleImportDemo} also has an ordinary
 * {@code import java.util.Map;} so the same run proves ordinary imports are
 * still tracked: the module-import handling must isolate the module import
 * only, not suppress real dependencies. names() returns a List, which resolves through the
 * module import, and counts() a Map.
 */
class GetDependencyGraphModuleImportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDependencyGraphTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetDependencyGraphTool(() -> service);
        envelope = new EnvelopeHarness(service);
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("module import (import module java.base;) is not emitted as a java.base node; the Map import and the List and Map return types are")
    void moduleImport_notDerivedAsPackageNode() {
        String demo = "com.example.ModuleImportDemo";
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", demo);
        args.put("includeExternal", true); // would surface a bogus java.base node if mis-derived

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNodes", 3);
        summary.put("totalEdges", 2);
        summary.put("internalDependencies", 0);
        summary.put("externalDependencies", 2);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", "type");
        data.put("root", demo);
        data.put("nodes", List.of(
            node(demo, "class", "com.example"),
            node("java.util.List", "type", "java.util"),
            node("java.util.Map", "import", "java.util")));
        data.put("edges", List.of(
            Map.of("from", demo, "to", "java.util.List", "count", 1),
            Map.of("from", demo, "to", "java.util.Map", "count", 2)));
        data.put("summary", summary);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", 3);
        meta.put("returnedCount", 3);
        meta.put("suggestedNextTools", List.of(
            "find_circular_dependencies to detect cycles",
            "get_type_hierarchy for inheritance analysis"));

        JsonNode expected = mapper.valueToTree(Map.of("success", true, "data", data, "meta", meta));
        JsonNode direct;
        try {
            direct = sorted(mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = sorted(envelope.assertEnvelopeFidelity("get_dependency_graph", args));
        assertAll(
            () -> assertEquals(expected, direct, "execute() payload for " + args),
            () -> assertEquals(expected, wire, "envelope payload for " + args));
    }

    private static Map<String, Object> node(String name, String kind, String pkg) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("kind", kind);
        node.put("package", pkg);
        return node;
    }

    /** The payload with its nodes sorted by name and its edges by target: a graph has no defined order. */
    private static JsonNode sorted(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        if (copy.path("data") instanceof ObjectNode data) {
            for (String[] field : new String[][] {{"nodes", "name"}, {"edges", "to"}}) {
                if (data.get(field[0]) instanceof ArrayNode array) {
                    List<JsonNode> elements = new ArrayList<>();
                    array.forEach(elements::add);
                    elements.sort(Comparator.comparing(element -> element.path(field[1]).asText()));
                    data.putArray(field[0]).addAll(elements);
                }
            }
        }
        return copy;
    }
}
