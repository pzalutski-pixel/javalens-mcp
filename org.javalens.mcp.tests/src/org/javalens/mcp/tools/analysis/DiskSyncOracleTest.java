package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The #26 acceptance oracle: after any disk mutation, ensureFresh() followed
 * by a tool call must produce answers IDENTICAL to a freshly constructed
 * service loading the same mutated tree. Full reload is the oracle the cheap
 * path is verified against, across all three answer sources: the search
 * index (find_references), on-demand ASTs (get_diagnostics / analyze_file),
 * and the derived project graph (find_affected_tests).
 */
class DiskSyncOracleTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private JdtServiceImpl oracleService;
    private Path projectCopy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        projectCopy = service.getProjectRoot();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        // The oracle service is loaded against the same temp copy; nothing to do -
        // TestProjectHelper removes the temp tree.
    }

    private Path calculator() {
        return projectCopy.resolve("src/main/java/com/example/Calculator.java");
    }

    private Path userService() {
        return projectCopy.resolve("src/main/java/com/example/service/UserService.java");
    }

    private record Probe(Function<Supplier<org.javalens.core.IJdtService>, Tool> toolFactory,
                         ObjectNode args) {
    }

    /**
     * The oracle assertion: run every probe against the repaired service
     * FIRST, then load the oracle service once and compare. The oracle must
     * load last and only once - a second load of the same project directory
     * sweeps the sibling session's workspace project (WorkspaceManager's
     * stale-project cleanup), killing the primary service.
     */
    private void assertOracleEquivalence(Probe... probes) throws Exception {
        service.ensureFresh();
        ToolResponse[] repaired = new ToolResponse[probes.length];
        for (int i = 0; i < probes.length; i++) {
            repaired[i] = probes[i].toolFactory().apply(() -> service).execute(probes[i].args());
        }

        oracleService = new JdtServiceImpl();
        oracleService.loadProject(projectCopy);
        for (int i = 0; i < probes.length; i++) {
            ToolResponse fresh = probes[i].toolFactory().apply(() -> oracleService).execute(probes[i].args());
            ToolResponse actual = repaired[i];
            assertEquals(fresh.isSuccess(), actual.isSuccess(),
                "probe " + i + " success mismatch; repaired="
                    + (actual.getError() == null ? "ok"
                        : actual.getError().getCode() + ": " + actual.getError().getMessage())
                    + " | fresh="
                    + (fresh.getError() == null ? "ok"
                        : fresh.getError().getCode() + ": " + fresh.getError().getMessage()));
            assertEquals(fresh.getData(), actual.getData(),
                "probe " + i + ": repaired-path answer must be identical to a fresh full load");
        }
    }

    private ObjectNode argsAt(Path file, int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    // ========== Edit ==========

    @Test
    @DisplayName("edit: a new call site appears in find_references without a reload")
    void edit_indexAnswerMatchesOracle() throws Exception {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source.replace(
            "public int add(int a, int b) {",
            "public int twice(int x) { return add(x, x); }\n\n    public int add(int a, int b) {"));

        // Calculator.add's name moved down two lines; use the new position in both services.
        ObjectNode args = argsAt(calculator(), 16, 16);
        assertOracleEquivalence(new Probe(f -> new FindReferencesTool(f), args));
    }

    @Test
    @DisplayName("edit: cross-file - renaming A's method surfaces diagnostics in B")
    void edit_crossFileDiagnosticsMatchOracle() throws Exception {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source.replace("public int add(", "public int addAll("));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userService().toString());
        assertOracleEquivalence(new Probe(f -> new GetDiagnosticsTool(f), args));
    }

    // ========== Add ==========

    @Test
    @DisplayName("add: a new file in a new package is analyzable and its call sites count")
    void addNewFileInNewPackage_matchesOracle() throws Exception {
        Path brand = projectCopy.resolve("src/main/java/com/example/fresh/Brand.java");
        Files.createDirectories(brand.getParent());
        Files.writeString(brand, """
            package com.example.fresh;

            import com.example.Calculator;

            public class Brand {

                public int compute() {
                    return new Calculator().add(1, 2);
                }
            }
            """);

        assertOracleEquivalence(
            new Probe(f -> new AnalyzeFileTool(f), fileArgs(brand)),
            new Probe(f -> new FindReferencesTool(f), argsAt(calculator(), 14, 16)));
    }

    private ObjectNode fileArgs(Path file) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        return args;
    }

    // ========== Delete ==========

    @Test
    @DisplayName("delete: a removed file's call sites disappear from find_references")
    void delete_matchesOracle() throws Exception {
        Files.delete(userService());

        assertOracleEquivalence(new Probe(f -> new FindReferencesTool(f), argsAt(calculator(), 14, 16)));
    }

    // ========== Rename ==========

    @Test
    @DisplayName("rename: delete + add settles to the oracle's view")
    void renameFile_matchesOracle() throws Exception {
        Path original = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        String source = Files.readString(original);
        Path renamed = original.resolveSibling("Saluter.java");
        Files.writeString(renamed, source.replace("class Greeter", "class Saluter")
            .replace("public Greeter", "public Saluter"));
        Files.delete(original);

        assertOracleEquivalence(
            new Probe(f -> new FindReferencesTool(f), argsAt(calculator(), 14, 16)),
            new Probe(f -> new AnalyzeFileTool(f), fileArgs(renamed)));
    }

    // ========== Multi-file batch ==========

    @Test
    @DisplayName("batch: edit + add + delete repaired in one pass")
    void multiFileBatch_matchesOracle() throws Exception {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source + "// trailing comment\n");
        Path extra = projectCopy.resolve("src/main/java/com/example/Extra.java");
        Files.writeString(extra, "package com.example;\n\npublic class Extra {\n}\n");
        Files.delete(projectCopy.resolve("src/main/java/com/example/Animal.java"));

        assertOracleEquivalence(new Probe(f -> new FindReferencesTool(f), argsAt(calculator(), 14, 16)));
    }

    // ========== Derived graph (cache invalidation proof) ==========

    @Test
    @DisplayName("graph: a new covering test appears in find_affected_tests without a reload")
    void graphAnswerMatchesOracle() throws Exception {
        // Prime the graph cache BEFORE the mutation: if repair fails to
        // invalidate it, the repaired answer would come from this stale graph
        // and diverge from the oracle.
        ObjectNode args = argsAt(calculator(), 14, 16);
        new FindAffectedTestsTool(() -> service).execute(args);

        Path sampleTest = projectCopy.resolve("src/test/java/com/example/SampleTest.java");
        String source = Files.readString(sampleTest);
        Files.writeString(sampleTest, source.replace(
            "    @Test\n    void testAddition() {",
            "    @Test\n    void testFreshCoverage() {\n        assert calculator.add(7, 8) == 15;\n    }\n\n    @Test\n    void testAddition() {"));

        assertOracleEquivalence(new Probe(f -> new FindAffectedTestsTool(f), args));
    }

    // ========== No change ==========

    @Test
    @DisplayName("no change: ensureFresh is invisible")
    void noChange_matchesOracle() throws Exception {
        assertOracleEquivalence(new Probe(f -> new FindReferencesTool(f), argsAt(calculator(), 14, 16)));
    }
}
