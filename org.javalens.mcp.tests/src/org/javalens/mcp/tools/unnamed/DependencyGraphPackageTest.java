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
 * get_dependency_graph gives each node that denotes a type, or a member of one, the package of
 * that type as its binding resolves it, and gives no package to a node that denotes a package.
 * A type reached by a name written qualified in the source is named by its resolved fully
 * qualified name, as a type reached by a simple name is. At package scope each import counts
 * toward the package its binding resolves to. A dependency is external when the package it
 * denotes or lies in is a JDK package (java., javax., ...), the package node's own name being
 * that package.
 *
 * <p>Graph (nested-types) imports java.util.Map.Entry, a member type whose package is java.util;
 * com.example.util on demand, a package; and the static method com.example.util.Tools.trim, a
 * member of a type in com.example.util. It extends Shelf, implements Runnable and declares fields
 * of the types Entry (resolved through its import), Tools (resolved through the on-demand
 * import) and Shelf.Folio (written qualified). Index imports the package java.util on demand,
 * the member types of the type java.util.Map on demand and the static members of the type
 * com.example.util.Tools on demand. Ledger, in the default package, declares a field of the
 * default-package type Page. The nodes and edges of a graph come in no defined order, so both
 * are compared sorted.
 */
class DependencyGraphPackageTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String DEFAULT_PACKAGE = "(default package)";

    private static final List<String> NEXT_TOOLS = List.of(
        "find_circular_dependencies to detect cycles",
        "get_type_hierarchy for inheritance analysis");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_dependency_graph of Graph gives each type node its package, a static member import its type's package, and an on-demand package import none")
    void typeScopeImportsAndMembers() {
        String graph = "com.example.nested.Graph";
        // Entry is reached twice, by its import and by the field entry. Runnable and Entry are
        // in java packages, so external; the other five targets are internal.
        Map<String, Object> data = map(
            "scope", "type",
            "root", graph,
            "nodes", List.of(
                map("name", graph, "kind", "class", "package", "com.example.nested"),
                map("name", "com.example.nested.Shelf", "kind", "class", "package", "com.example.nested"),
                map("name", "com.example.nested.Shelf.Folio", "kind", "type", "package", "com.example.nested"),
                map("name", "com.example.util", "kind", "import"),
                map("name", "com.example.util.Tools", "kind", "type", "package", "com.example.util"),
                map("name", "com.example.util.Tools.trim", "kind", "import", "package", "com.example.util"),
                map("name", "java.lang.Runnable", "kind", "interface", "package", "java.lang"),
                map("name", "java.util.Map.Entry", "kind", "import", "package", "java.util")),
            "edges", List.of(
                map("from", graph, "to", "com.example.nested.Shelf", "count", 1),
                map("from", graph, "to", "com.example.nested.Shelf.Folio", "count", 1),
                map("from", graph, "to", "com.example.util", "count", 1),
                map("from", graph, "to", "com.example.util.Tools", "count", 1),
                map("from", graph, "to", "com.example.util.Tools.trim", "count", 1),
                map("from", graph, "to", "java.lang.Runnable", "count", 1),
                map("from", graph, "to", "java.util.Map.Entry", "count", 2)),
            "summary", map("totalNodes", 8, "totalEdges", 7, "internalDependencies", 5, "externalDependencies", 2));
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, typeArgs(graph),
            success(data, meta(8)), sorted());
    }

    @Test
    @DisplayName("get_dependency_graph of Index gives the package import java.util no package, the type-import-on-demand node java.util.Map the package java.util and the static-import-on-demand node Tools com.example.util")
    void typeScopeOnDemandImports() {
        String index = "com.example.nested.Index";
        // java.util and java.util.Map are external: the package java.util itself, and a type in it.
        Map<String, Object> data = map(
            "scope", "type",
            "root", index,
            "nodes", List.of(
                map("name", index, "kind", "class", "package", "com.example.nested"),
                map("name", "com.example.util.Tools", "kind", "import", "package", "com.example.util"),
                map("name", "java.util", "kind", "import"),
                map("name", "java.util.Map", "kind", "import", "package", "java.util")),
            "edges", List.of(
                map("from", index, "to", "com.example.util.Tools", "count", 1),
                map("from", index, "to", "java.util", "count", 1),
                map("from", index, "to", "java.util.Map", "count", 1)),
            "summary", map("totalNodes", 4, "totalEdges", 3, "internalDependencies", 1, "externalDependencies", 2));
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, typeArgs(index),
            success(data, meta(4)), sorted());
    }

    @Test
    @DisplayName("get_dependency_graph of Index without external dependencies leaves out the on-demand import of the JDK package java.util")
    void typeScopeOnDemandImportsInternalOnly() {
        String index = "com.example.nested.Index";
        Map<String, Object> data = map(
            "scope", "type",
            "root", index,
            "nodes", List.of(
                map("name", index, "kind", "class", "package", "com.example.nested"),
                map("name", "com.example.util.Tools", "kind", "import", "package", "com.example.util")),
            "edges", List.of(map("from", index, "to", "com.example.util.Tools", "count", 1)),
            "summary", map("totalNodes", 2, "totalEdges", 1, "internalDependencies", 1, "externalDependencies", 0));
        ObjectNode args = typeArgs(index);
        args.put("includeExternal", false);
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, args,
            success(data, meta(2)), sorted());
    }

    @Test
    @DisplayName("get_dependency_graph of Ledger gives Ledger and its field type Page, both in the default package, the package (default package)")
    void typeScopeDefaultPackage() {
        Map<String, Object> data = map(
            "scope", "type",
            "root", "Ledger",
            "nodes", List.of(
                map("name", "Ledger", "kind", "class", "package", DEFAULT_PACKAGE),
                map("name", "Page", "kind", "type", "package", DEFAULT_PACKAGE)),
            "edges", List.of(map("from", "Ledger", "to", "Page", "count", 1)),
            "summary", map("totalNodes", 2, "totalEdges", 1, "internalDependencies", 1, "externalDependencies", 0));
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, typeArgs("Ledger"),
            success(data, meta(2)), sorted());
    }

    @Test
    @DisplayName("get_dependency_graph of the package com.example.nested gives its package nodes no package field and counts each import toward the package its binding resolves to")
    void packageScope() {
        String nested = "com.example.nested";
        // java.util: Flows' import of List, Graph's of Map.Entry, and Index's of the package
        // java.util and of Map's members.
        // com.example.util: Graph's on-demand import and static import of trim, and Index's
        // static on-demand import of Tools.
        Map<String, Object> data = map(
            "scope", "package",
            "root", nested,
            "nodes", List.of(
                map("name", nested, "kind", "package"),
                map("name", "com.example.util", "kind", "package"),
                map("name", "java.util", "kind", "package")),
            "edges", List.of(
                map("from", nested, "to", "com.example.util", "count", 3),
                map("from", nested, "to", "java.util", "count", 4)),
            "summary", map("totalNodes", 3, "totalEdges", 2, "internalDependencies", 1, "externalDependencies", 1));
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "package");
        args.put("name", nested);
        args.put("includeExternal", true);
        assertPayload(mapper, new GetDependencyGraphTool(() -> service), envelope, args,
            success(data, meta(3)), sorted());
    }

    private ObjectNode typeArgs(String name) {
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", name);
        args.put("includeExternal", true);
        return args;
    }

    private static Map<String, Object> meta(int nodes) {
        return map("totalCount", nodes, "returnedCount", nodes, "suggestedNextTools", NEXT_TOOLS);
    }

    /** The payload with its nodes sorted by name and its edges by target. */
    private static UnaryOperator<JsonNode> sorted() {
        UnaryOperator<JsonNode> nodes = sortedBy("name", "data", "nodes");
        UnaryOperator<JsonNode> edges = sortedBy("to", "data", "edges");
        return payload -> edges.apply(nodes.apply(payload));
    }
}
