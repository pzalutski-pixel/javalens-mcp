package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * For a library JDT cannot index (a file that is not a zip archive), load_project and
 * health_check report LIBRARY_INDEX_INCOMPLETE naming the library, exactly as the load's
 * warnings, and every response of the other tools names it in
 * {@code meta.libraryIndexIncomplete}, through execute() and through the JSON-RPC envelope
 * alike. A reload without the library leaves no warning and no notice.
 */
class LibraryIndexIncompleteReportTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String GRAPH = "src/main/java/org/example/indexing/Graph.java";

    private static final List<String> SEARCH_SYMBOLS_NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    private static final List<String> SUGGEST_IMPORTS_NEXT_TOOLS = List.of(
        "apply_quick_fix with fixId to add the import",
        "get_quick_fixes to see all available fixes");

    private static final List<String> VALIDATE_SYNTAX_NEXT_TOOLS = List.of(
        "get_diagnostics for type checking",
        "get_document_symbols to explore structure");

    /** search_symbols("Graph"): the class declared on Graph.java's line 8 (0-based), its name at column 19. */
    private static final Map<String, Object> GRAPH_SEARCH_RESULT = Map.of(
        "name", "Graph",
        "kind", "class",
        "filePath", GRAPH,
        "line", 8,
        "column", 19,
        "qualifiedName", "org.example.indexing.Graph",
        "package", "org.example.indexing");

    @Test
    @DisplayName("a library that is not a zip archive is reported LIBRARY_INDEX_INCOMPLETE by load_project and health_check and named in meta.libraryIndexIncomplete by an index-backed tool, a tool that reads no index and an error response; a reload without it leaves no warning and no notice")
    void unreadableLibrary_reportedEverywhere_goneAfterReloadWithoutIt() throws IOException {
        Path broken = helper.getTempDirectory().resolve("libs/broken.jar");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "not a zip archive");
        Path root = helper.getFixturePath("index-fidelity");
        AtomicBoolean withBroken = new AtomicBoolean(true);
        EnvelopeHarness envelope = new EnvelopeHarness(null,
            () -> new JdtServiceImpl(withBroken.get() ? withLibrary(broken) : new ProjectImporter()));
        ObjectNode loadArgs = envelope.args().put("projectPath", root.toString());
        ObjectNode importsArgs = envelope.args().put("typeName", "List");
        ObjectNode searchArgs = envelope.args().put("query", "Graph");
        ObjectNode syntaxArgs = envelope.args().put("filePath", root.resolve(GRAPH).toString());

        JsonNode brokenLoad = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode brokenHealth = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        JsonNode brokenImports = envelope.assertEnvelopeFidelity("suggest_imports", importsArgs);
        JsonNode brokenSearch = envelope.assertEnvelopeFidelity("search_symbols", searchArgs);
        JsonNode brokenSyntax = envelope.assertEnvelopeFidelity("validate_syntax", syntaxArgs);
        JsonNode brokenError = envelope.assertEnvelopeFidelity("search_symbols", envelope.args());

        withBroken.set(false);
        JsonNode load = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode health = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        JsonNode imports = envelope.assertEnvelopeFidelity("suggest_imports", importsArgs);
        JsonNode search = envelope.assertEnvelopeFidelity("search_symbols", searchArgs);
        JsonNode syntax = envelope.assertEnvelopeFidelity("validate_syntax", syntaxArgs);
        JsonNode error = envelope.assertEnvelopeFidelity("search_symbols", envelope.args());

        String shown = broken.toAbsolutePath().normalize().toString().replace('\\', '/');
        Map<String, Object> warning = new LinkedHashMap<>();
        warning.put("code", "LIBRARY_INDEX_INCOMPLETE");
        warning.put("message", "The search index of 1 library could not be built: " + shown
            + "; search-based results do not include its types");
        warning.put("remediation", "Make sure each library is a readable archive or class folder, "
            + "then reload the project; if the warning persists, restart JavaLens.");
        JsonNode warnings = json(List.of(warning));
        List<String> notice = List.of(shown);
        assertAll(
            () -> assertEquals(true, brokenLoad.path("data").path("loaded").asBoolean(), "load_project loaded"),
            () -> assertEquals(warnings, brokenLoad.path("data").get("warnings"), "load_project warnings"),
            () -> assertEquals(warnings, brokenHealth.path("data").path("project").get("warnings"),
                "health_check warnings"),
            () -> assertEquals(json(importsPayload(notice)), brokenImports, "suggest_imports with the library"),
            () -> assertEquals(json(searchPayload(notice)), brokenSearch, "search_symbols with the library"),
            () -> assertEquals(json(syntaxPayload(notice)), brokenSyntax, "validate_syntax with the library"),
            () -> assertEquals(json(missingQueryPayload(notice)), brokenError, "search_symbols error with the library"),
            () -> assertEquals(true, load.path("data").path("loaded").asBoolean(), "load_project without the library"),
            () -> assertEquals(false, load.path("data").has("warnings"), "load_project warnings without the library"),
            () -> assertEquals(false, health.path("data").path("project").has("warnings"),
                "health_check warnings without the library"),
            () -> assertEquals(json(importsPayload(null)), imports, "suggest_imports without the library"),
            () -> assertEquals(json(searchPayload(null)), search, "search_symbols without the library"),
            () -> assertEquals(json(syntaxPayload(null)), syntax, "validate_syntax without the library"),
            () -> assertEquals(json(missingQueryPayload(null)), error, "search_symbols error without the library"));
    }

    // ========== Payloads ==========

    /**
     * suggest_imports("List")'s payload, with the notice when it is not null: the JDK's
     * java.util.List (an interface, relevance 100), then java.awt.List (a class, 70);
     * index-fidelity declares no List.
     */
    private static Map<String, Object> importsPayload(List<String> notice) {
        List<Map<String, Object>> candidates = List.of(
            candidate("java.util", 100, true, false),
            candidate("java.awt", 70, false, true));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("typeName", "List");
        data.put("totalCandidates", 2);
        data.put("candidates", candidates);
        return success(data, meta(2, SUGGEST_IMPORTS_NEXT_TOOLS, notice));
    }

    private static Map<String, Object> candidate(String pkg, int relevance, boolean isInterface, boolean isClass) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("fullyQualifiedName", pkg + ".List");
        candidate.put("packageName", pkg);
        candidate.put("simpleName", "List");
        candidate.put("relevance", relevance);
        candidate.put("isInterface", isInterface);
        candidate.put("isClass", isClass);
        candidate.put("isEnum", false);
        candidate.put("fixId", "add_import:" + pkg + ".List");
        return candidate;
    }

    /** search_symbols("Graph")'s payload, with the notice when it is not null. */
    private static Map<String, Object> searchPayload(List<String> notice) {
        return success(Map.of(
                "query", "Graph",
                "results", List.of(GRAPH_SEARCH_RESULT),
                "pagination", Map.of("offset", 0, "returned", 1, "hasMore", false)),
            meta(1, SEARCH_SYMBOLS_NEXT_TOOLS, notice));
    }

    /** validate_syntax(Graph.java)'s payload: no syntax error; with the notice when it is not null. */
    private static Map<String, Object> syntaxPayload(List<String> notice) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("suggestedNextTools", VALIDATE_SYNTAX_NEXT_TOOLS);
        if (notice != null) {
            meta.put("libraryIndexIncomplete", notice);
        }
        return success(Map.of("valid", true, "fileName", "Graph.java", "errorCount", 0, "errors", List.of()), meta);
    }

    /** search_symbols' refusal of a call without a query, with the notice when it is not null. */
    private static Map<String, Object> missingQueryPayload(List<String> notice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'query': Required parameter missing"));
        if (notice != null) {
            payload.put("meta", Map.of("libraryIndexIncomplete", notice));
        }
        return payload;
    }

    /** A complete result's meta: every result returned, none truncated, and the notice when it is not null. */
    private static Map<String, Object> meta(int count, List<String> nextTools, List<String> notice) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", count);
        meta.put("returnedCount", count);
        meta.put("truncated", false);
        meta.put("suggestedNextTools", nextTools);
        if (notice != null) {
            meta.put("libraryIndexIncomplete", notice);
        }
        return meta;
    }

    private static Map<String, Object> success(Map<String, Object> data, Map<String, Object> meta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    /** An importer whose resolved build model carries {@code library} after its own classpath. */
    private static ProjectImporter withLibrary(Path library) {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                BuildModel model = super.resolveModel(projectPath);
                List<String> classpath = new ArrayList<>(model.classpath());
                classpath.add(library.toAbsolutePath().normalize().toString());
                return new BuildModel(model.buildSystem(), model.provenance(), model.modules(), classpath,
                    model.processorJars(), model.analysisLevel(), model.inputs(), model.warnings());
            }
        };
    }
}
