package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lists that a tool cuts or filters, on the capped-lists fixture: every count is the exact
 * total, a cut list reports truncated, a list is ranked before it is cut, and an enum-like
 * parameter accepts its documented values in any case and refuses any other value.
 *
 * <p>The fixture: Hot.hit() is called 501 times by Calls.all(), one call a line on lines 9 to
 * 509. Loader calls each public overload of Class.forName once: forName(String) on line 8,
 * forName(String, boolean, ClassLoader) on line 12 and forName(Module, String) on line 16.
 * Twelve types are named Widget: javax.capped.Widget and org.capped.w01.Widget to
 * org.capped.w11.Widget. Assembly.java declares Assembly, which names Widget without importing it
 * (line 7), and Spare, which never reads its private field spare (line 15). JDT leaves out the
 * flow analysis, and so the unused-field warning, of a type holding an error, so the warning has
 * a type of its own. Under org.capped.tree, Api (package api) imports
 * model.Item, Item (package model) imports nothing and Cli (package cli) imports only
 * java.util.List. Every expectation is the whole payload, through execute() and through the
 * JSON-RPC envelope. Positions are zero-based.
 */
class CappedListsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String MAIN = "src/main/java/";
    private static final String HOT = MAIN + "org/capped/impact/Hot.java";
    private static final String CALLS = MAIN + "org/capped/impact/Calls.java";
    private static final String LOADER = MAIN + "org/capped/reflect/Loader.java";
    private static final String ASSEMBLY = MAIN + "org/capped/use/Assembly.java";

    /** IProblem.UndefinedType, IProblem.UnusedPrivateField. */
    private static final int UNDEFINED_TYPE = 16777218;
    private static final int UNUSED_PRIVATE_FIELD = 570425421;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("capped-lists");
        envelope = new EnvelopeHarness(service);
        root = helper.getFixturePath("capped-lists");
    }

    // ========== analyze_change_impact ==========

    @Test
    @DisplayName("analyze_change_impact reports all 501 call sites of Hot.hit and counts them exactly")
    void changeImpact_everyReference() {
        // Each call site's length runs from the method name to the closing parenthesis. Raw
        // offsets depend on line endings, so they are removed; line and column pin each site.
        List<Map<String, Object>> callSites = new ArrayList<>();
        for (int line = 9; line <= 509; line++) {
            callSites.add(map(
                "filePath", CALLS,
                "length", 5,
                "line", line,
                "column", 17,
                "context", "n += Hot.hit();",
                "depth", 1,
                "enclosingMethod", "all",
                "enclosingType", "org.capped.impact.Calls"));
        }
        Map<String, Object> data = map(
            "symbol", "hit",
            "symbolType", "SourceMethod",
            "depth", 1,
            "directReferences", 501,
            "totalReferences", 501,
            "affectedFiles", List.of(map("filePath", CALLS, "referenceCount", 501)),
            "callSites", callSites);
        Map<String, Object> meta = map(
            "totalCount", 501,
            "returnedCount", 501,
            "suggestedNextTools", List.of(
                "rename_symbol for safe renaming across all affected files",
                "find_references for simple reference list without grouping"));
        assertPayload("analyze_change_impact", position(HOT, 7, 22), success(data, meta),
            without("offset", "data", "callSites"));
    }

    // ========== find_reflection_usage ==========

    @Test
    @DisplayName("find_reflection_usage with maxResults=1 counts the calls of every Class.forName overload and returns the first in source order")
    void reflection_countsEveryOverload() {
        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 1);
        Map<String, Object> data = map(
            "totalCalls", 3,
            "summary", map("Class.forName", 3),
            "reflectionCalls", List.of(forName(8, 13, "return Class.forName(name);")));
        assertPayload("find_reflection_usage", args, success(data, reflectionMeta(3, 1, true)),
            without("offset", "data", "reflectionCalls"));
    }

    @Test
    @DisplayName("find_reflection_usage with maxResults equal to the three Class.forName calls returns them all in source order, not truncated")
    void reflection_capEqualToTotal() {
        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 3);
        Map<String, Object> data = map(
            "totalCalls", 3,
            "summary", map("Class.forName", 3),
            "reflectionCalls", List.of(
                forName(8, 13, "return Class.forName(name);"),
                forName(12, 27, "return Class.forName(name, true, loader);"),
                forName(16, 21, "return Class.forName(module, name);")));
        assertPayload("find_reflection_usage", args, success(data, reflectionMeta(3, 3, false)),
            without("offset", "data", "reflectionCalls"));
    }

    // ========== suggest_imports ==========

    @Test
    @DisplayName("suggest_imports ranks all twelve Widget types before cutting: maxResults=1 returns javax.capped.Widget, the most relevant, of 12")
    void suggestImports_rankThenCut() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "Widget");
        args.put("maxResults", 1);
        Map<String, Object> data = map(
            "typeName", "Widget",
            "totalCandidates", 12,
            "candidates", List.of(candidate("javax.capped", 65)));
        assertPayload("suggest_imports", args, success(data, suggestMeta(12, 1, true)));
    }

    @Test
    @DisplayName("suggest_imports lists every Widget type, most relevant first and equally relevant ones by name")
    void suggestImports_allCandidatesRanked() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "Widget");
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(candidate("javax.capped", 65));
        for (int i = 1; i <= 11; i++) {
            candidates.add(candidate(String.format("org.capped.w%02d", i), 45));
        }
        Map<String, Object> data = map(
            "typeName", "Widget",
            "totalCandidates", 12,
            "candidates", candidates);
        assertPayload("suggest_imports", args, success(data, suggestMeta(12, 12, false)));
    }

    // ========== get_quick_fixes ==========

    @Test
    @DisplayName("get_quick_fixes ranks all twelve Widget imports before keeping ten: javax.capped.Widget first, then w01 to w09 by name, 12 fixes in all")
    void quickFixes_rankThenCut() {
        List<Map<String, Object>> fixes = new ArrayList<>();
        fixes.add(importFix("javax.capped", 65));
        for (int i = 1; i <= 9; i++) {
            fixes.add(importFix(String.format("org.capped.w%02d", i), 40));
        }
        Map<String, Object> data = map(
            "filePath", ASSEMBLY,
            "line", 7,
            "problemCount", 1,
            "problems", List.of(map(
                "problemId", UNDEFINED_TYPE,
                "message", "Widget cannot be resolved to a type",
                "severity", "error")),
            "fixes", fixes);
        Map<String, Object> meta = map(
            "totalCount", 12,
            "returnedCount", 10,
            "truncated", true,
            "suggestedNextTools", List.of(
                "apply_quick_fix with fixId to apply a fix",
                "get_diagnostics to see all problems"));
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(ASSEMBLY).toString());
        args.put("line", 7);
        assertPayload("get_quick_fixes", args, success(data, meta));
    }

    // ========== get_dependency_graph ==========

    @Test
    @DisplayName("get_dependency_graph at package scope lists the subpackage api, the source of the edge to model, as a node")
    void dependencyGraph_edgeSourcesAreNodes() {
        Map<String, Object> data = map(
            "scope", "package",
            "root", "org.capped.tree",
            "nodes", List.of(
                map("name", "org.capped.tree", "kind", "package"),
                map("name", "org.capped.tree.api", "kind", "package"),
                map("name", "org.capped.tree.model", "kind", "package")),
            "edges", List.of(map("from", "org.capped.tree.api", "to", "org.capped.tree.model", "count", 1)),
            "summary", map("totalNodes", 3, "totalEdges", 1, "internalDependencies", 1, "externalDependencies", 0));
        assertPayload("get_dependency_graph", packageArgs(false), success(data, graphMeta(3)), graphSorted());
    }

    @Test
    @DisplayName("get_dependency_graph at package scope with external dependencies also lists cli, the source of the edge to java.util")
    void dependencyGraph_externalEdgeSourceIsNode() {
        Map<String, Object> data = map(
            "scope", "package",
            "root", "org.capped.tree",
            "nodes", List.of(
                map("name", "java.util", "kind", "package"),
                map("name", "org.capped.tree", "kind", "package"),
                map("name", "org.capped.tree.api", "kind", "package"),
                map("name", "org.capped.tree.cli", "kind", "package"),
                map("name", "org.capped.tree.model", "kind", "package")),
            "edges", List.of(
                map("from", "org.capped.tree.cli", "to", "java.util", "count", 1),
                map("from", "org.capped.tree.api", "to", "org.capped.tree.model", "count", 1)),
            "summary", map("totalNodes", 5, "totalEdges", 2, "internalDependencies", 1, "externalDependencies", 1));
        assertPayload("get_dependency_graph", packageArgs(true), success(data, graphMeta(5)), graphSorted());
    }

    // ========== get_diagnostics severity ==========

    @Test
    @DisplayName("get_diagnostics severity is case-insensitive: Error on Assembly gives only the unresolved Widget")
    void diagnostics_severityErrorAnyCase() throws Exception {
        ObjectNode args = diagnosticsArgs("Error");
        assertPayload("get_diagnostics", args, diagnostics(0, List.of(widgetError())));
    }

    @Test
    @DisplayName("get_diagnostics severity is case-insensitive: WARNING on Assembly gives only the unused private field")
    void diagnostics_severityWarningAnyCase() throws Exception {
        ObjectNode args = diagnosticsArgs("WARNING");
        assertPayload("get_diagnostics", args, diagnostics(1, List.of(unusedSpare())));
    }

    @Test
    @DisplayName("get_diagnostics severity all on Assembly gives the unresolved Widget and the unused private field in source order")
    void diagnostics_severityAll() throws Exception {
        ObjectNode args = diagnosticsArgs("All");
        assertPayload("get_diagnostics", args, diagnostics(1, List.of(widgetError(), unusedSpare())));
    }

    @Test
    @DisplayName("get_diagnostics refuses an undocumented severity with INVALID_PARAMETER naming the accepted values")
    void diagnostics_unknownSeverityRefused() {
        assertPayload("get_diagnostics", diagnosticsArgs("fatal"), map(
            "success", false,
            "error", map(
                "code", "INVALID_PARAMETER",
                "message", "Invalid parameter 'severity': Must be one of error, warning, all "
                    + "(case-insensitive); got: fatal")),
            UnaryOperator.identity());
    }

    // ========== helpers ==========

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private ObjectNode packageArgs(boolean includeExternal) {
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "package");
        args.put("name", "org.capped.tree");
        args.put("includeExternal", includeExternal);
        return args;
    }

    private ObjectNode diagnosticsArgs(String severity) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(ASSEMBLY).toString());
        args.put("severity", severity);
        return args;
    }

    /** A Class.forName call in Loader at {@code line}, its match running from forName for {@code length}. */
    private static Map<String, Object> forName(int line, int length, String context) {
        return map(
            "filePath", LOADER,
            "length", length,
            "line", line,
            "column", 21,
            "context", context,
            "reflectionMethod", "Class.forName");
    }

    private static Map<String, Object> reflectionMeta(int total, int returned, boolean truncated) {
        return map(
            "totalCount", total,
            "returnedCount", returned,
            "truncated", truncated,
            "suggestedNextTools", List.of(
                "analyze_change_impact to assess risk of renaming reflected types",
                "get_symbol_info at a reflection call site for context"));
    }

    private static Map<String, Object> candidate(String pkg, int relevance) {
        return map(
            "fullyQualifiedName", pkg + ".Widget",
            "packageName", pkg,
            "simpleName", "Widget",
            "relevance", relevance,
            "isInterface", false,
            "isClass", true,
            "isEnum", false,
            "fixId", "add_import:" + pkg + ".Widget");
    }

    private static Map<String, Object> suggestMeta(int total, int returned, boolean truncated) {
        return map(
            "totalCount", total,
            "returnedCount", returned,
            "truncated", truncated,
            "suggestedNextTools", List.of(
                "apply_quick_fix with fixId to add the import",
                "get_quick_fixes to see all available fixes"));
    }

    private static Map<String, Object> importFix(String pkg, int relevance) {
        return map(
            "fixId", "add_import:" + pkg + ".Widget",
            "label", "Import 'Widget' (" + pkg + ")",
            "category", "IMPORT",
            "relevance", relevance,
            "problemId", UNDEFINED_TYPE);
    }

    private static Map<String, Object> graphMeta(int nodes) {
        return map(
            "totalCount", nodes,
            "returnedCount", nodes,
            "suggestedNextTools", List.of(
                "find_circular_dependencies to detect cycles",
                "get_type_hierarchy for inheritance analysis"));
    }

    /** The payload with its nodes sorted by name and its edges by target: a graph has no defined order. */
    private static UnaryOperator<JsonNode> graphSorted() {
        UnaryOperator<JsonNode> nodes = sortedBy("name", "data", "nodes");
        UnaryOperator<JsonNode> edges = sortedBy("to", "data", "edges");
        return payload -> edges.apply(nodes.apply(payload));
    }

    /** The one-file get_diagnostics payload of Assembly listing {@code diagnostics}. */
    private static Map<String, Object> diagnostics(int warnings, List<Map<String, Object>> diagnostics) {
        Map<String, Object> data = map(
            "totalDiagnostics", diagnostics.size(),
            "errorCount", diagnostics.size() - warnings,
            "warningCount", warnings,
            "filesChecked", 1,
            "diagnostics", diagnostics);
        Map<String, Object> meta = map(
            "totalCount", diagnostics.size(),
            "returnedCount", diagnostics.size(),
            "truncated", false,
            "suggestedNextTools", List.of(
                "go_to_definition to navigate to error location",
                "get_document_symbols to see file structure"));
        return success(data, meta);
    }

    /**
     * The private field spare, never read: JDT reports it on the field's name, its end offset
     * inclusive. Offsets are read from the fixture as checked out, so line endings do not matter.
     */
    private Map<String, Object> unusedSpare() throws Exception {
        int start = assemblySource().indexOf("int spare;") + "int ".length();
        return diagnostic(15, start, start + "spare".length() - 1, "warning",
            "The value of the field Spare.spare is not used", UNUSED_PRIVATE_FIELD, "UNNECESSARY_CODE");
    }

    /** The unresolved type Widget, reported on its name. */
    private Map<String, Object> widgetError() throws Exception {
        int start = assemblySource().indexOf("Widget part;");
        return diagnostic(7, start, start + "Widget".length() - 1, "error",
            "Widget cannot be resolved to a type", UNDEFINED_TYPE, "TYPE");
    }

    private String assemblySource() throws Exception {
        return Files.readString(root.resolve(ASSEMBLY));
    }

    private static Map<String, Object> diagnostic(int line, int start, int end, String severity, String message,
                                                  int problemId, String category) {
        return map(
            "filePath", ASSEMBLY,
            "line", line,
            "startOffset", start,
            "endOffset", end,
            "severity", severity,
            "message", message,
            "problemId", problemId,
            "category", category);
    }

    /**
     * The payload of the registered tool {@code toolName} for {@code args}, through execute() and
     * through the JSON-RPC envelope, both equal {@code expected} after {@code normalize}. The
     * execute() result is compared as the JSON text it serializes to, as a client reads it.
     */
    private void assertPayload(String toolName, ObjectNode args, Map<String, Object> expected,
                               UnaryOperator<JsonNode> normalize) {
        JsonNode expectedJson = mapper.valueToTree(expected);
        Tool tool = envelope.registry().getTool(toolName).orElseThrow();
        JsonNode direct;
        try {
            direct = normalize.apply(mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy()))));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = normalize.apply(envelope.assertEnvelopeFidelity(toolName, args));
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }

    private void assertPayload(String toolName, ObjectNode args, Map<String, Object> expected) {
        assertPayload(toolName, args, expected, UnaryOperator.identity());
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> success(Map<String, Object> data, Map<String, Object> meta) {
        return map("success", true, "data", data, "meta", meta);
    }

    /** {@code payload} with the field {@code field} removed from every element of the array at {@code path}. */
    private static UnaryOperator<JsonNode> without(String field, String... path) {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            JsonNode node = copy;
            for (String name : path) {
                node = node.path(name);
            }
            node.forEach(element -> {
                if (element instanceof ObjectNode object) {
                    object.remove(field);
                }
            });
            return copy;
        };
    }

    /** {@code payload} with the array at {@code path} sorted by the text of each element's {@code key}. */
    private static UnaryOperator<JsonNode> sortedBy(String key, String... path) {
        return payload -> {
            JsonNode copy = payload.deepCopy();
            JsonNode node = copy;
            for (int i = 0; i < path.length - 1; i++) {
                node = node.path(path[i]);
            }
            if (!(node instanceof ObjectNode parent) || !(parent.get(path[path.length - 1]) instanceof ArrayNode array)) {
                return copy;
            }
            List<JsonNode> elements = new ArrayList<>();
            array.forEach(elements::add);
            elements.sort(Comparator.comparing(element -> element.path(key).asText()));
            ArrayNode sorted = parent.putArray(path[path.length - 1]);
            elements.forEach(sorted::add);
            return copy;
        };
    }
}
