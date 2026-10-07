package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.index.Index;
import org.eclipse.jdt.internal.core.search.indexing.ReadWriteMonitor;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.workspace.WorkspaceManager;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * While the search index is verified to be missing a file's declarations, load_project and
 * health_check report INDEX_INCOMPLETE naming the file, and every response of the other tools
 * names it in {@code meta.indexIncomplete}, through execute() and through the JSON-RPC
 * envelope alike. On a complete index no response carries the notice.
 *
 * <p>The gap is a real one: once the project is configured and indexed, the test removes
 * Posting.java's document from the project's index through JDT's {@link Index} API under the
 * index's write lock, as JDT's own RemoveFromIndex job does. The gap survives the load's repair
 * only through the service's package-private {@code setIndexRepairEnabled(false)} seam, which
 * keeps the repair from re-indexing; this test, outside the service's package, reaches it by
 * reflection.
 */
class IndexIncompleteNoticeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String POSTING = "src/main/java/org/example/verify/Posting.java";

    /** load_project's and health_check's report of the gap on Posting.java. */
    private static final List<Map<String, Object>> INDEX_INCOMPLETE_WARNINGS = List.of(Map.of(
        "code", "INDEX_INCOMPLETE",
        "message", "The search index is missing declarations from 1 file: " + POSTING
            + "; search-based results do not include them",
        "remediation", "Reload the project; if the warning persists, restart JavaLens."));

    private static final List<String> SEARCH_SYMBOLS_NEXT_TOOLS = List.of(
        "get_symbol_info at a result location for detailed info",
        "get_type_members for type results",
        "find_references to see all usages");

    private static final List<String> DOCUMENT_SYMBOLS_NEXT_TOOLS = List.of(
        "go_to_definition to navigate to a symbol",
        "find_references to find usages of a symbol",
        "get_type_members to see members of a specific type");

    /** Posting.java's symbols: the class (its source range starts at its Javadoc), its field, constructor and method. */
    private static final Map<String, Object> POSTING_SYMBOLS = Map.of(
        "file", POSTING,
        "totalSymbols", 4,
        "symbols", List.of(Map.of(
            "name", "Posting",
            "kind", "class",
            "modifiers", List.of("final"),
            "line", 2,
            "children", List.of(
                Map.of("name", "ledger", "kind", "field", "modifiers", List.of("private", "final"),
                    "type", "Ledger", "line", 7),
                Map.of("name", "Posting", "kind", "constructor", "modifiers", List.of(),
                    "signature", "Posting(Ledger ledger)", "line", 9),
                Map.of("name", "post", "kind", "method", "modifiers", List.of(),
                    "signature", "post(String account, long cents): void", "line", 13)))));

    /** search_symbols("Posting") once Posting.java is indexed. */
    private static final Map<String, Object> POSTING_SEARCH_RESULT = Map.of(
        "name", "Posting",
        "kind", "class",
        "filePath", POSTING,
        "line", 5,
        "column", 12,
        "qualifiedName", "org.example.verify.Posting",
        "package", "org.example.verify");

    @Test
    @DisplayName("a gap that survives the load's repair is reported by load_project and health_check and named in meta.indexIncomplete by an index-backed tool, a tool that reads no index and an error response; a reload that repairs it leaves no warning and no notice")
    void survivingGap_reportedEverywhere_goneAfterRepairingReload() {
        Path root = helper.getFixturePath("index-verification");
        AtomicBoolean repair = new AtomicBoolean(false);
        EnvelopeHarness envelope = new EnvelopeHarness(null,
            () -> withIndexRepair(new JdtServiceImpl(new PostingGapImporter()), repair.get()));
        ObjectNode loadArgs = envelope.args().put("projectPath", root.toString());
        ObjectNode searchArgs = envelope.args().put("query", "Posting");
        ObjectNode symbolsArgs = envelope.args().put("filePath", root.resolve(POSTING).toString());

        JsonNode gapLoad = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode gapHealth = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        JsonNode gapSearch = envelope.assertEnvelopeFidelity("search_symbols", searchArgs);
        JsonNode gapSymbols = envelope.assertEnvelopeFidelity("get_document_symbols", symbolsArgs);
        JsonNode gapError = envelope.assertEnvelopeFidelity("search_symbols", envelope.args());

        repair.set(true);
        JsonNode load = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        JsonNode health = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        JsonNode search = envelope.assertEnvelopeFidelity("search_symbols", searchArgs);
        JsonNode symbols = envelope.assertEnvelopeFidelity("get_document_symbols", symbolsArgs);
        JsonNode error = envelope.assertEnvelopeFidelity("search_symbols", envelope.args());

        List<String> notice = List.of(POSTING);
        assertAll(
            () -> assertEquals(json(INDEX_INCOMPLETE_WARNINGS), gapLoad.path("data").get("warnings"),
                "load_project warnings with the gap"),
            () -> assertEquals(List.of("code", "message", "remediation"),
                fieldNames(gapLoad.path("data").path("warnings").path(0)), "INDEX_INCOMPLETE fields in order"),
            () -> assertEquals(json(INDEX_INCOMPLETE_WARNINGS), gapHealth.path("data").path("project").get("warnings"),
                "health_check warnings with the gap"),
            () -> assertEquals(json(searchPayload(List.of(), notice)), gapSearch, "search_symbols with the gap"),
            () -> assertEquals(json(symbolsPayload(notice)), gapSymbols, "get_document_symbols with the gap"),
            () -> assertEquals(json(missingQueryPayload(notice)), gapError, "search_symbols error with the gap"),
            () -> assertEquals(true, load.path("data").path("loaded").asBoolean(), "load_project after the repairing reload"),
            () -> assertEquals(false, load.path("data").has("warnings"), "load_project warnings after the repairing reload"),
            () -> assertEquals(false, health.path("data").path("project").has("warnings"),
                "health_check warnings after the repairing reload"),
            () -> assertEquals(json(searchPayload(List.of(POSTING_SEARCH_RESULT), null)), search,
                "search_symbols after the repairing reload"),
            () -> assertEquals(json(symbolsPayload(null)), symbols, "get_document_symbols after the repairing reload"),
            () -> assertEquals(json(missingQueryPayload(null)), error, "search_symbols error after the repairing reload"));
    }

    @Test
    @DisplayName("on a complete index no tool's response carries meta.indexIncomplete or meta.libraryIndexIncomplete, through the envelope, for every registered tool")
    void completeIndex_noResponseCarriesTheNotice() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        EnvelopeHarness envelope = new EnvelopeHarness(service);
        Map<String, ObjectNode> inputs = ToolInvocationInputs.buildValidInputs(mapper, helper.getFixturePath("simple-maven"));

        Map<String, String> withNotice = new TreeMap<>();
        Map<String, String> withLibraryNotice = new TreeMap<>();
        int compared = 0;
        for (String name : new TreeSet<>(envelope.registry().getToolNames())) {
            JsonNode payload = envelope.payload(name, inputs.get(name));
            compared++;
            if (payload.path("meta").has("indexIncomplete")) {
                withNotice.put(name, payload.path("meta").get("indexIncomplete").toString());
            }
            if (payload.path("meta").has("libraryIndexIncomplete")) {
                withLibraryNotice.put(name, payload.path("meta").get("libraryIndexIncomplete").toString());
            }
        }

        int toolCount = compared;
        assertAll(
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getLibraryIndexIncomplete()),
            () -> assertEquals(75, toolCount, "every registered tool must be driven"),
            () -> assertEquals(Map.of(), withNotice),
            () -> assertEquals(Map.of(), withLibraryNotice));
    }

    // ========== Helpers ==========

    /** search_symbols("Posting")'s payload with {@code results}, and the notice when it is not null. */
    private static Map<String, Object> searchPayload(List<Map<String, Object>> results, List<String> notice) {
        return Map.of(
            "success", true,
            "data", Map.of(
                "query", "Posting",
                "results", results,
                "pagination", Map.of("offset", 0, "returned", results.size(), "hasMore", false)),
            "meta", meta(results.size(), SEARCH_SYMBOLS_NEXT_TOOLS, notice));
    }

    /** get_document_symbols(Posting.java)'s payload, with the notice when it is not null. */
    private static Map<String, Object> symbolsPayload(List<String> notice) {
        return Map.of(
            "success", true,
            "data", POSTING_SYMBOLS,
            "meta", meta(4, DOCUMENT_SYMBOLS_NEXT_TOOLS, notice));
    }

    /** search_symbols' refusal of a call without a query, with the notice when it is not null. */
    private static Map<String, Object> missingQueryPayload(List<String> notice) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("success", false);
        payload.put("error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter 'query': Required parameter missing"));
        if (notice != null) {
            payload.put("meta", Map.of("indexIncomplete", notice));
        }
        return payload;
    }

    /** A complete result's meta: every result returned, none truncated, and the notice when it is not null. */
    private static Map<String, Object> meta(int count, List<String> nextTools, List<String> notice) {
        Map<String, Object> meta = new TreeMap<>();
        meta.put("totalCount", count);
        meta.put("returnedCount", count);
        meta.put("truncated", false);
        meta.put("suggestedNextTools", nextTools);
        if (notice != null) {
            meta.put("indexIncomplete", notice);
        }
        return meta;
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** {@code service} with its index repair turned on or off through its package-private seam. */
    private static JdtServiceImpl withIndexRepair(JdtServiceImpl service, boolean enabled) {
        try {
            Method seam = JdtServiceImpl.class.getDeclaredMethod("setIndexRepairEnabled", boolean.class);
            seam.setAccessible(true);
            seam.invoke(service, enabled);
            return service;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the index repair seam is unavailable", e);
        }
    }

    /**
     * An importer that, once the project is configured and the indexer is idle, removes
     * Posting.java's document from the project's index under the index's write lock.
     */
    private static final class PostingGapImporter extends ProjectImporter {

        @Override
        public IJavaProject configureJavaProject(IProject project, Path projectPath, BuildModel model,
                                                 WorkspaceManager workspaceManager) throws CoreException {
            IJavaProject javaProject = super.configureJavaProject(project, projectPath, model, workspaceManager);
            new SearchEngine().searchAllTypeNames(
                null, SearchPattern.R_EXACT_MATCH,
                null, SearchPattern.R_EXACT_MATCH,
                IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(),
                new TypeNameRequestor() {
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
            Index index = JavaModelManager.getIndexManager().getIndex(project.getFullPath(), true, false);
            String document = index.containerRelativePath(
                javaProject.findType("org.example.verify.Posting").getCompilationUnit().getPath().toString());
            ReadWriteMonitor monitor = index.monitor;
            monitor.enterWrite();
            try {
                index.remove(document);
            } finally {
                monitor.exitWrite();
            }
            return javaProject;
        }
    }
}
