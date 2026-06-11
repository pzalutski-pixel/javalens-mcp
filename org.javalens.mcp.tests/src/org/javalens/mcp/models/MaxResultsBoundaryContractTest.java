package org.javalens.mcp.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.javalens.mcp.tools.FindCastsTool;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.javalens.mcp.tools.FindUnreachableCodeTool;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.javalens.mcp.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code maxResults} boundary behavior for every tool that declares
 * the parameter. Asserts:
 *  - {@code maxResults=0} succeeds (no crash, no error) and returns 0 results,
 *    unless the tool documents a minimum cap.
 *  - {@code maxResults=1} returns at most 1 result.
 *  - {@code maxResults=Integer.MAX_VALUE} succeeds (no overflow/crash) and
 *    yields a response whose {@code returnedCount} equals {@code totalCount}
 *    (i.e., no truncation when the cap is effectively infinite).
 *
 * <p>Per-tool quirks encoded explicitly:
 * <ul>
 *   <li>{@code minResultsAtZero}: some tools clamp {@code maxResults} to
 *       {@code [1, N]} silently. {@code SearchSymbolsTool} does this at
 *       {@code [1, 1000]}; 5 others (find_implementations, find_references,
 *       find_field_writes, get_call_hierarchy_incoming, get_document_symbols)
 *       do {@code [1, N]} too. At {@code maxResults=0} they return up to 1.
 *       Captured as B-11 in the plan; this test pins the current behavior.</li>
 *   <li>{@code scale}: {@code find_reflection_usage} caps PER reflection method
 *       (per label), so {@code maxResults=1} can yield up to
 *       {@code REFLECTION_METHODS.length} results in aggregate. This test
 *       multiplies the expected ceiling by the scale.</li>
 * </ul>
 */
class MaxResultsBoundaryContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectPath;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        projectPath = helper.getFixturePath("simple-maven");
        objectMapper = new ObjectMapper();
    }

    static Stream<Arguments> provideToolArgs() {
        // row(name, argsBuilder, toolBuilder, minResultsAtZero, scale)
        // minResultsAtZero: how many results the tool returns when maxResults=0
        //   — post-B-11 fix all tools honor maxResults=0 literally and return 0.
        // scale: per-maxResults multiplier — most tools: 1; per-category tools higher
        //   (find_reflection_usage caps per reflection method, 18 methods → scale 18)
        return Stream.of(
            row("search_symbols",
                ctx -> ctx.args().put("query", "Calculator"),
                ctx -> new SearchSymbolsTool(() -> ctx.service),  0, 1),
            row("find_implementations",
                ctx -> argsAt(ctx, "src/main/java/com/example/IShape.java", 2, 17),
                ctx -> new FindImplementationsTool(() -> ctx.service),  0, 1),
            row("get_document_symbols",
                ctx -> ctx.args().put("filePath",
                    ctx.projectPath.resolve("src/main/java/com/example/Calculator.java").toString()),
                ctx -> new GetDocumentSymbolsTool(() -> ctx.service),  0, 1),
            row("get_diagnostics",
                ctx -> ctx.args().put("filePath",
                    ctx.projectPath.resolve("src/main/java/com/example/Calculator.java").toString()),
                ctx -> new GetDiagnosticsTool(() -> ctx.service),  0, 1),
            row("find_references",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 5, 13),
                ctx -> new FindReferencesTool(() -> ctx.service),  0, 1),
            row("find_field_writes",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 6, 16),
                ctx -> new FindFieldWritesTool(() -> ctx.service),  0, 1),
            row("get_call_hierarchy_incoming",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15),
                ctx -> new GetCallHierarchyIncomingTool(() -> ctx.service),  0, 1),
            row("find_method_references",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15),
                ctx -> new FindMethodReferencesTool(() -> ctx.service),  0, 1),
            row("find_casts",
                ctx -> ctx.args().put("typeName", "com.example.Calculator"),
                ctx -> new FindCastsTool(() -> ctx.service),  0, 1),
            row("find_instanceof_checks",
                ctx -> ctx.args().put("typeName", "com.example.Calculator"),
                ctx -> new FindInstanceofChecksTool(() -> ctx.service),  0, 1),
            row("find_type_instantiations",
                ctx -> ctx.args().put("typeName", "com.example.Calculator"),
                ctx -> new FindTypeInstantiationsTool(() -> ctx.service),  0, 1),
            row("find_throws_declarations",
                ctx -> ctx.args().put("typeName", "java.io.IOException"),
                ctx -> new FindThrowsDeclarationsTool(() -> ctx.service),  0, 1),
            row("find_catch_blocks",
                ctx -> ctx.args().put("typeName", "java.lang.Exception"),
                ctx -> new FindCatchBlocksTool(() -> ctx.service),  0, 1),
            row("find_type_arguments",
                ctx -> ctx.args().put("typeName", "com.example.Calculator"),
                ctx -> new FindTypeArgumentsTool(() -> ctx.service),  0, 1),
            row("find_annotation_usages",
                ctx -> ctx.args().put("typeName", "com.example.Marker"),
                ctx -> new FindAnnotationUsagesTool(() -> ctx.service),  0, 1),
            row("find_reflection_usage",
                ctx -> ctx.args(),
                ctx -> new FindReflectionUsageTool(() -> ctx.service),  0, 18),
            row("get_di_registrations",
                ctx -> ctx.args(),
                ctx -> new GetDiRegistrationsTool(() -> ctx.service),  0, 1),
            row("suggest_imports",
                ctx -> ctx.args().put("typeName", "List"),
                ctx -> new SuggestImportsTool(() -> ctx.service),  0, 1),
            // 1.4.2 additions (the #29 audit found these missing from the registry)
            row("analyze_change_impact",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15)
                    .put("transitive", true),
                ctx -> new AnalyzeChangeImpactTool(() -> ctx.service),  0, 1),
            row("find_unreachable_code",
                ctx -> ctx.args(),
                ctx -> new FindUnreachableCodeTool(() -> ctx.service),  0, 1),
            row("find_affected_tests",
                ctx -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15),
                ctx -> new FindAffectedTestsTool(() -> ctx.service),  0, 1),
            row("get_jpa_model",
                ctx -> ctx.args(),
                ctx -> new GetJpaModelTool(() -> ctx.service),  0, 1),
            row("get_http_endpoints",
                ctx -> ctx.args(),
                ctx -> new GetHttpEndpointsTool(() -> ctx.service),  0, 1)
        );
    }

    /**
     * Tools whose maxResults caps PER CATEGORY (per reflection method / per
     * annotation type): an aggregate-total cap below the total does not
     * guarantee truncation, so the below-total case is skipped for them.
     */
    private static final java.util.Set<String> PER_CATEGORY_CAP =
        java.util.Set.of("find_reflection_usage", "get_di_registrations");

    @ParameterizedTest(name = "{0}: maxResults at total, total+1, total-1")
    @MethodSource("provideToolArgs")
    @DisplayName("maxResults at the exact total and one above return everything; one below truncates")
    void totalBoundaries_exact(String toolName,
                               Function<TestContext, ObjectNode> argsBuilder,
                               Function<TestContext, Tool> toolBuilder,
                               int minResultsAtZero, int scale) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);

        var reference = toolBuilder.apply(ctx)
            .execute(argsBuilder.apply(ctx).put("maxResults", Integer.MAX_VALUE));
        assertTrue(reference.isSuccess(),
            toolName + ": reference call must succeed; got: " + describe(reference));
        int total = reference.getMeta().getTotalCount();

        var atTotal = toolBuilder.apply(ctx)
            .execute(argsBuilder.apply(ctx).put("maxResults", total));
        assertTrue(atTotal.isSuccess(),
            toolName + " must succeed at maxResults=total; got: " + describe(atTotal));
        assertTrue(Integer.valueOf(total).equals(atTotal.getMeta().getReturnedCount()),
            toolName + ": maxResults=total(" + total + ") must return everything; got returnedCount="
                + atTotal.getMeta().getReturnedCount());
        if (!PER_CATEGORY_CAP.contains(toolName)) {
            assertTrue(!Boolean.TRUE.equals(atTotal.getMeta().getTruncated()),
                toolName + ": maxResults=total must not flag truncation");
        }

        var aboveTotal = toolBuilder.apply(ctx)
            .execute(argsBuilder.apply(ctx).put("maxResults", total + 1));
        assertTrue(aboveTotal.isSuccess(),
            toolName + " must succeed at maxResults=total+1; got: " + describe(aboveTotal));
        assertTrue(Integer.valueOf(total).equals(aboveTotal.getMeta().getReturnedCount()),
            toolName + ": maxResults=total+1 must return everything; got returnedCount="
                + aboveTotal.getMeta().getReturnedCount());
        assertTrue(!Boolean.TRUE.equals(aboveTotal.getMeta().getTruncated()),
            toolName + ": maxResults=total+1 must not flag truncation");

        if (total >= 1 && !PER_CATEGORY_CAP.contains(toolName)) {
            var belowTotal = toolBuilder.apply(ctx)
                .execute(argsBuilder.apply(ctx).put("maxResults", total - 1));
            assertTrue(belowTotal.isSuccess(),
                toolName + " must succeed at maxResults=total-1; got: " + describe(belowTotal));
            assertTrue(Integer.valueOf(total - 1).equals(belowTotal.getMeta().getReturnedCount()),
                toolName + ": maxResults=total-1 must return exactly total-1(" + (total - 1)
                    + "); got returnedCount=" + belowTotal.getMeta().getReturnedCount());
            assertTrue(Boolean.TRUE.equals(belowTotal.getMeta().getTruncated()),
                toolName + ": maxResults=total-1 must flag truncation");
        }
    }

    @ParameterizedTest(name = "{0}: maxResults=0 → returnedCount<={3}")
    @MethodSource("provideToolArgs")
    @DisplayName("maxResults=0 returns at most the tool's documented minimum cap")
    void zero_returnsAtMostMinCap(String toolName,
                                  Function<TestContext, ObjectNode> argsBuilder,
                                  Function<TestContext, Tool> toolBuilder,
                                  int minResultsAtZero, int scale) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        ObjectNode args = argsBuilder.apply(ctx).put("maxResults", 0);
        Tool tool = toolBuilder.apply(ctx);
        var response = tool.execute(args);
        assertTrue(response.isSuccess(),
            toolName + " must succeed at maxResults=0; got: " + describe(response));
        Integer returned = response.getMeta().getReturnedCount();
        assertNotNull(returned, toolName + ": returnedCount must be populated");
        assertTrue(returned <= minResultsAtZero,
            toolName + ": maxResults=0 must return at most " + minResultsAtZero
                + " results; got returnedCount=" + returned);
    }

    @ParameterizedTest(name = "{0}: maxResults=-1 → INVALID_PARAMETER")
    @MethodSource("provideToolArgs")
    @DisplayName("Negative maxResults is rejected with INVALID_PARAMETER (B-11)")
    void negative_isRejected(String toolName,
                              Function<TestContext, ObjectNode> argsBuilder,
                              Function<TestContext, Tool> toolBuilder,
                              int minResultsAtZero, int scale) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        ObjectNode args = argsBuilder.apply(ctx).put("maxResults", -1);
        Tool tool = toolBuilder.apply(ctx);
        var response = tool.execute(args);
        assertTrue(!response.isSuccess(),
            toolName + ": maxResults=-1 must fail (not silently clamp); got: " + describe(response));
        ErrorInfo err = response.getError();
        assertNotNull(err, toolName + ": negative maxResults must produce an error");
        assertTrue(ErrorInfo.INVALID_PARAMETER.equals(err.getCode()),
            toolName + ": negative maxResults must report INVALID_PARAMETER; got code: "
                + err.getCode() + " message: " + err.getMessage());
    }

    @ParameterizedTest(name = "{0}: maxResults=1 → returnedCount<={4} (scale)")
    @MethodSource("provideToolArgs")
    @DisplayName("maxResults=1 returns at most 1 * scale results")
    void one_returnsAtMostScale(String toolName,
                                 Function<TestContext, ObjectNode> argsBuilder,
                                 Function<TestContext, Tool> toolBuilder,
                                 int minResultsAtZero, int scale) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        ObjectNode args = argsBuilder.apply(ctx).put("maxResults", 1);
        Tool tool = toolBuilder.apply(ctx);
        var response = tool.execute(args);
        assertTrue(response.isSuccess(),
            toolName + " must succeed at maxResults=1; got: " + describe(response));
        Integer returned = response.getMeta().getReturnedCount();
        assertNotNull(returned);
        assertTrue(returned <= scale,
            toolName + ": maxResults=1 must return at most " + scale
                + " (scale=" + scale + "); got returnedCount=" + returned);
    }

    @ParameterizedTest(name = "{0}: maxResults=MAX_VALUE → no truncation, returnedCount == totalCount")
    @MethodSource("provideToolArgs")
    @DisplayName("maxResults=Integer.MAX_VALUE succeeds, returnedCount equals totalCount, truncated=false")
    void maxValue_doesNotCrash(String toolName,
                               Function<TestContext, ObjectNode> argsBuilder,
                               Function<TestContext, Tool> toolBuilder,
                               int minResultsAtZero, int scale) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        ObjectNode args = argsBuilder.apply(ctx).put("maxResults", Integer.MAX_VALUE);
        Tool tool = toolBuilder.apply(ctx);
        var response = tool.execute(args);
        assertTrue(response.isSuccess(),
            toolName + " must succeed at maxResults=MAX_VALUE without overflow; got: "
                + describe(response));
        Integer returned = response.getMeta().getReturnedCount();
        Integer total = response.getMeta().getTotalCount();
        assertNotNull(returned, toolName + ": returnedCount must be populated");
        assertNotNull(total, toolName + ": totalCount must be populated");
        assertTrue(returned >= 0,
            toolName + ": returnedCount must be non-negative; got " + returned);
        // The class Javadoc promises: at MAX_VALUE the cap is effectively infinite, so
        // returnedCount equals totalCount (no truncation). Previously only the >= 0
        // sanity-check was enforced — the contract claim was untested. Pin it now.
        assertTrue(returned.equals(total),
            toolName + ": at maxResults=MAX_VALUE the cap must not truncate; "
                + "returnedCount=" + returned + " totalCount=" + total);
        Boolean truncated = response.getMeta().getTruncated();
        assertNotNull(truncated, toolName + ": truncated must be populated");
        assertTrue(!truncated,
            toolName + ": at maxResults=MAX_VALUE truncated must be false; got " + truncated);
    }

    private static String describe(org.javalens.mcp.models.ToolResponse r) {
        if (r.isSuccess()) return "success(meta=" + r.getMeta() + ")";
        ErrorInfo err = r.getError();
        return err == null ? "<null error>"
            : "[" + err.getCode() + "] " + err.getMessage();
    }

    private static Arguments row(String name,
                                  Function<TestContext, ObjectNode> argsBuilder,
                                  Function<TestContext, Tool> toolBuilder,
                                  int minResultsAtZero, int scale) {
        return Arguments.of(name, argsBuilder, toolBuilder, minResultsAtZero, scale);
    }

    private static ObjectNode argsAt(TestContext ctx, String relativePath, int line, int column) {
        return ctx.args()
            .put("filePath", ctx.projectPath.resolve(relativePath).toString())
            .put("line", line)
            .put("column", column);
    }

    record TestContext(JdtServiceImpl service, Path projectPath, ObjectMapper objectMapper) {
        ObjectNode args() {
            return objectMapper.createObjectNode();
        }
    }
}
