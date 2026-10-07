package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.workspace.WorkspaceManager;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.LibraryIndexRace;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AbstractTool;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.javalens.mcp.tools.SuggestImportsTool;
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
 * suggest_imports and get_quick_fixes propose the JDK's types after a load in which JDT removed
 * the JRE's index while the job indexing the JRE was walking it: a load into a workspace that
 * holds no JavaLens project, as when JavaLens starts, so JDT configures the JRE as a library
 * new to it. Every expectation is the whole payload, through execute() and through the JSON-RPC
 * envelope.
 */
class ImportsAfterLibraryIndexRaceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("suggest_imports for List after the raced first load proposes java.util.List, then java.awt.List")
    void suggestImports_afterRacedFirstLoad_jdkCandidates() throws Exception {
        JdtServiceImpl service = racedFirstLoad("simple-maven");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        assertPayload(new SuggestImportsTool(() -> service), new EnvelopeHarness(service), "suggest_imports", args,
            suggestions("List", List.of(
                candidate("java.util", "List", 100, true, false),
                candidate("java.awt", "List", 70, false, true))));
    }

    @Test
    @DisplayName("get_quick_fixes for the unresolved Date after the raced first load proposes java.util.Date, then java.sql.Date")
    void quickFixes_afterRacedFirstLoad_jdkImportFixes() throws Exception {
        JdtServiceImpl service = racedFirstLoad("broken-symbols");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("broken-symbols").resolve(BROKEN_SYMBOLS).toString());
        args.put("line", 29); // 0-based: `Date d = null;`

        assertPayload(new GetQuickFixesTool(() -> service), new EnvelopeHarness(service), "get_quick_fixes", args,
            undefinedDate());
    }

    /**
     * Learn the JRE's library from a load of {@code fixture}, remove every JavaLens project, then
     * load {@code fixture} with JDT's removal of the JRE's index falling inside the JRE's
     * indexing walk, and assert that interleaving ran.
     */
    private JdtServiceImpl racedFirstLoad(String fixture) throws Exception {
        Path root = helper.getFixturePath(fixture);
        JdtServiceImpl earlier = new JdtServiceImpl();
        earlier.loadProject(root);
        IPath jre = jreLibrary(earlier.getJavaProject());
        WorkspaceManager workspace = new WorkspaceManager();
        workspace.initialize();
        assertEquals(List.of(), workspace.removeCreatedProjectsExcept(null));

        JdtServiceImpl service = new JdtServiceImpl();
        List<String> interleaving;
        try (LibraryIndexRace race = LibraryIndexRace.arm(jre)) {
            service.loadProject(root);
            interleaving = race.events();
        }
        assertEquals(List.of("started", "held", "removed", "released"), interleaving);
        return service;
    }

    /** The JRE's library on the project's resolved classpath: the JDK's jrt-fs.jar. */
    private static IPath jreLibrary(IJavaProject project) throws JavaModelException {
        List<IPath> jre = new ArrayList<>();
        for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY
                    && "jrt-fs.jar".equals(entry.getPath().lastSegment())) {
                jre.add(entry.getPath());
            }
        }
        assertEquals(1, jre.size(), "one JRE library on " + project.getElementName() + ": " + jre);
        return jre.get(0);
    }

    // ========== exact payloads ==========

    private static final String BROKEN_SYMBOLS = "src/main/java/com/example/BrokenSymbols.java";

    /** IProblem.UndefinedType. */
    private static final int UNDEFINED_TYPE = 16777218;

    /**
     * A suggest_imports candidate. The JDK declares List in java.util (an interface), java.awt
     * (a class), jdk.jfr.internal.query and com.sun.tools.javac.util; suggest_imports leaves out
     * the internal and com.sun. packages.
     */
    private static Map<String, Object> candidate(String pkg, String simpleName, int relevance,
                                                 boolean isInterface, boolean isClass) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("fullyQualifiedName", pkg + "." + simpleName);
        candidate.put("packageName", pkg);
        candidate.put("simpleName", simpleName);
        candidate.put("relevance", relevance);
        candidate.put("isInterface", isInterface);
        candidate.put("isClass", isClass);
        candidate.put("isEnum", false);
        candidate.put("fixId", "add_import:" + pkg + "." + simpleName);
        return candidate;
    }

    /** The suggest_imports success payload for {@code typeName} listing every one of its {@code candidates}. */
    private static Map<String, Object> suggestions(String typeName, List<Map<String, Object>> candidates) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("typeName", typeName);
        data.put("totalCandidates", candidates.size());
        data.put("candidates", candidates);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", candidates.size());
        meta.put("returnedCount", candidates.size());
        meta.put("truncated", false);
        meta.put("suggestedNextTools", List.of(
            "apply_quick_fix with fixId to add the import",
            "get_quick_fixes to see all available fixes"));
        return success(data, meta);
    }

    /** BrokenSymbols line 29: Date is unresolved; java.util ranks 100, java.sql 70. */
    private static Map<String, Object> undefinedDate() {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("problemId", UNDEFINED_TYPE);
        problem.put("message", "Date cannot be resolved to a type");
        problem.put("severity", "error");
        List<Map<String, Object>> fixes = List.of(
            fix("add_import:java.util.Date", "Import 'Date' (java.util)", 100),
            fix("add_import:java.sql.Date", "Import 'Date' (java.sql)", 70));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filePath", BROKEN_SYMBOLS);
        data.put("line", 29);
        data.put("problemCount", 1);
        data.put("problems", List.of(problem));
        data.put("fixes", fixes);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", fixes.size());
        meta.put("returnedCount", fixes.size());
        meta.put("truncated", false);
        meta.put("suggestedNextTools", List.of(
            "apply_quick_fix with fixId to apply a fix",
            "get_diagnostics to see all problems"));
        return success(data, meta);
    }

    private static Map<String, Object> fix(String fixId, String label, int relevance) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("fixId", fixId);
        fix.put("label", label);
        fix.put("category", "IMPORT");
        fix.put("relevance", relevance);
        fix.put("problemId", UNDEFINED_TYPE);
        return fix;
    }

    private static Map<String, Object> success(Map<String, Object> data, Map<String, Object> meta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload of {@code tool} through execute(), read back from the JSON text it serializes
     * to, and through {@code harness}'s JSON-RPC envelope both equal {@code expected}.
     */
    private void assertPayload(AbstractTool tool, EnvelopeHarness harness, String toolName, ObjectNode args,
                               Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = harness.assertEnvelopeFidelity(toolName, args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
