package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AbstractTool;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * suggest_imports and get_quick_fixes on the import-access fixture: an import candidate is a
 * type the project can import, whatever its package is named. Every expectation is the whole
 * payload, through execute() and through the JSON-RPC envelope.
 *
 * <p>The project declares org.acme.internal.parts.Gadget and org.acme.impl.parts.Gadget; Workshop (0-based
 * lines 3-5) names Gadget, Unsafe and HttpServer without importing them. The JDK 21 types named
 * Unsafe are sun.misc.Unsafe, in jdk.unsupported, which exports sun.misc to every module, and
 * jdk.internal.misc.Unsafe, in java.base, which exports jdk.internal.misc only to named JDK
 * modules, so the project's code cannot import it. The one JDK type named HttpServer is
 * com.sun.net.httpserver.HttpServer, in jdk.httpserver, which exports com.sun.net.httpserver to
 * every module. The JDK declares no Gadget.
 */
class ImportAccessTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SuggestImportsTool suggestImports;
    private GetQuickFixesTool quickFixes;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String workshopPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("import-access");
        suggestImports = new SuggestImportsTool(() -> service);
        quickFixes = new GetQuickFixesTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        workshopPath = helper.getFixturePath("import-access").resolve(WORKSHOP).toString();
    }

    @Test
    @DisplayName("suggest_imports: project types in packages under internal and impl are candidates, by fully qualified name")
    void suggestImports_projectTypesInAnyPackage() {
        assertPayload(suggestImports, "suggest_imports", typeName("Gadget"), suggestions("Gadget", List.of(
            candidate("org.acme.impl.parts", "Gadget", 45, false, true),
            candidate("org.acme.internal.parts", "Gadget", 45, false, true))));
    }

    @Test
    @DisplayName("suggest_imports: sun.misc.Unsafe, exported to every module, is the only candidate; jdk.internal.misc.Unsafe, exported only to JDK modules, is none")
    void suggestImports_onlyTypesOfExportedPackages() {
        assertPayload(suggestImports, "suggest_imports", typeName("Unsafe"), suggestions("Unsafe", List.of(
            candidate("sun.misc", "Unsafe", 30, false, true))));
    }

    @Test
    @DisplayName("suggest_imports: com.sun.net.httpserver.HttpServer, exported to every module, is a candidate")
    void suggestImports_exportedComSunPackage() {
        assertPayload(suggestImports, "suggest_imports", typeName("HttpServer"), suggestions("HttpServer", List.of(
            candidate("com.sun.net.httpserver", "HttpServer", 40, false, true))));
    }

    @Test
    @DisplayName("get_quick_fixes: unresolved Gadget gets an import fix for each project Gadget, under internal and impl alike")
    void quickFixes_projectTypesInAnyPackage() {
        assertPayload(quickFixes, "get_quick_fixes", workshopLine(3), fixes(3, "Gadget", List.of(
            importFix("org.acme.impl.parts.Gadget", "Gadget", "org.acme.impl.parts"),
            importFix("org.acme.internal.parts.Gadget", "Gadget", "org.acme.internal.parts"))));
    }

    @Test
    @DisplayName("get_quick_fixes: unresolved Unsafe gets the import of sun.misc.Unsafe only, not of jdk.internal.misc.Unsafe")
    void quickFixes_onlyTypesOfExportedPackages() {
        assertPayload(quickFixes, "get_quick_fixes", workshopLine(4), fixes(4, "Unsafe", List.of(
            importFix("sun.misc.Unsafe", "Unsafe", "sun.misc"))));
    }

    @Test
    @DisplayName("get_quick_fixes: unresolved HttpServer gets the import of com.sun.net.httpserver.HttpServer")
    void quickFixes_exportedComSunPackage() {
        assertPayload(quickFixes, "get_quick_fixes", workshopLine(5), fixes(5, "HttpServer", List.of(
            importFix("com.sun.net.httpserver.HttpServer", "HttpServer", "com.sun.net.httpserver"))));
    }

    // ========== exact payload helpers ==========

    private static final String WORKSHOP = "src/main/java/org/acme/use/Workshop.java";

    /** IProblem.UndefinedType. */
    private static final int UNDEFINED_TYPE = 16777218;

    private ObjectNode typeName(String name) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", name);
        return args;
    }

    private ObjectNode workshopLine(int line) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", workshopPath);
        args.put("line", line);
        return args;
    }

    /** suggest_imports' relevance: java.util 100, other java. 70, org. 45, com. 40, any other package 30. */
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

    /** The success payload of suggest_imports for {@code typeName}: every candidate listed. */
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /** get_quick_fixes' import fix; its relevance is 40 for every package outside java., javax. and the listed frameworks. */
    private static Map<String, Object> importFix(String importName, String simpleName, String pkg) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("fixId", "add_import:" + importName);
        fix.put("label", "Import '" + simpleName + "' (" + pkg + ")");
        fix.put("category", "IMPORT");
        fix.put("relevance", 40);
        fix.put("problemId", UNDEFINED_TYPE);
        return fix;
    }

    /** The success payload of get_quick_fixes at Workshop {@code line}: {@code unresolved} and its fixes. */
    private static Map<String, Object> fixes(int line, String unresolved, List<Map<String, Object>> fixes) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("problemId", UNDEFINED_TYPE);
        problem.put("message", unresolved + " cannot be resolved to a type");
        problem.put("severity", "error");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filePath", WORKSHOP);
        data.put("line", line);
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }

    /**
     * The payload of {@code tool} through execute(), read back from the JSON text it serializes
     * to, and through the JSON-RPC envelope both equal {@code expected}.
     */
    private void assertPayload(AbstractTool tool, String toolName, ObjectNode args, Map<String, Object> expected) {
        JsonNode expectedJson = objectMapper.valueToTree(expected);
        JsonNode direct;
        try {
            direct = objectMapper.readTree(objectMapper.writeValueAsString(tool.execute(args.deepCopy())));
        } catch (Exception e) {
            throw new AssertionError("execute() payload does not serialize for " + args, e);
        }
        JsonNode wire = envelope.assertEnvelopeFidelity(toolName, args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
