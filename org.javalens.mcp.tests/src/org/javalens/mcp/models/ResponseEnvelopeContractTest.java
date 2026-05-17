package org.javalens.mcp.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeFileTool;
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
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for tools that declare a {@code maxResults} input parameter:
 * the success response MUST carry {@code truncated}, {@code totalCount}, and
 * {@code returnedCount} in {@link ResponseMeta}. Any tool that takes
 * {@code maxResults} but omits one of these fails this test.
 *
 * <p>If a new tool with {@code maxResults} is added, register it in
 * {@link #provideToolArgs()} so the contract is enforced.
 */
class ResponseEnvelopeContractTest {

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
        return Stream.of(
            row("search_symbols",
                (ctx) -> ctx.args().put("query", "Calculator")
                    .put("maxResults", 1),
                ctx -> new SearchSymbolsTool(() -> ctx.service)),
            row("find_implementations",
                (ctx) -> argsAt(ctx, "src/main/java/com/example/IShape.java", 2, 17)
                    .put("maxResults", 1),
                ctx -> new FindImplementationsTool(() -> ctx.service)),
            row("get_document_symbols",
                (ctx) -> ctx.args().put("filePath",
                    ctx.projectPath.resolve("src/main/java/com/example/Calculator.java").toString())
                    .put("maxResults", 1),
                ctx -> new GetDocumentSymbolsTool(() -> ctx.service)),
            row("get_diagnostics",
                (ctx) -> ctx.args().put("filePath",
                    ctx.projectPath.resolve("src/main/java/com/example/Calculator.java").toString())
                    .put("maxResults", 1),
                ctx -> new GetDiagnosticsTool(() -> ctx.service)),
            // Calculator.java positions (zero-based):
            //   line 5 col 13 → "Calculator" type name in `public class Calculator {`
            //   line 6 col 16 → "lastResult" field decl in `    private int lastResult;`
            //   line 14 col 15 → "add" method decl in `    public int add(int a, int b) {`
            row("find_references",
                (ctx) -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 5, 13)
                    .put("maxResults", 1),
                ctx -> new FindReferencesTool(() -> ctx.service)),
            row("find_field_writes",
                (ctx) -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 6, 16)
                    .put("maxResults", 1),
                ctx -> new FindFieldWritesTool(() -> ctx.service)),
            row("get_call_hierarchy_incoming",
                (ctx) -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15)
                    .put("maxResults", 1),
                ctx -> new GetCallHierarchyIncomingTool(() -> ctx.service)),
            row("find_method_references",
                (ctx) -> argsAt(ctx, "src/main/java/com/example/Calculator.java", 14, 15)
                    .put("maxResults", 1),
                ctx -> new FindMethodReferencesTool(() -> ctx.service)),
            row("find_casts",
                (ctx) -> ctx.args().put("typeName", "com.example.Calculator")
                    .put("maxResults", 1),
                ctx -> new FindCastsTool(() -> ctx.service)),
            row("find_instanceof_checks",
                (ctx) -> ctx.args().put("typeName", "com.example.Calculator")
                    .put("maxResults", 1),
                ctx -> new FindInstanceofChecksTool(() -> ctx.service)),
            row("find_type_instantiations",
                (ctx) -> ctx.args().put("typeName", "com.example.Calculator")
                    .put("maxResults", 1),
                ctx -> new FindTypeInstantiationsTool(() -> ctx.service)),
            row("find_throws_declarations",
                (ctx) -> ctx.args().put("typeName", "java.io.IOException")
                    .put("maxResults", 1),
                ctx -> new FindThrowsDeclarationsTool(() -> ctx.service)),
            row("find_catch_blocks",
                (ctx) -> ctx.args().put("typeName", "java.lang.Exception")
                    .put("maxResults", 1),
                ctx -> new FindCatchBlocksTool(() -> ctx.service)),
            row("find_type_arguments",
                (ctx) -> ctx.args().put("typeName", "com.example.Calculator")
                    .put("maxResults", 1),
                ctx -> new FindTypeArgumentsTool(() -> ctx.service)),
            row("find_annotation_usages",
                (ctx) -> ctx.args().put("typeName", "com.example.Marker")
                    .put("maxResults", 1),
                ctx -> new FindAnnotationUsagesTool(() -> ctx.service)),
            row("find_reflection_usage",
                (ctx) -> ctx.args().put("maxResults", 1),
                ctx -> new FindReflectionUsageTool(() -> ctx.service)),
            row("get_di_registrations",
                (ctx) -> ctx.args().put("maxResults", 1),
                ctx -> new GetDiRegistrationsTool(() -> ctx.service)),
            row("suggest_imports",
                (ctx) -> ctx.args().put("typeName", "List")
                    .put("filePath",
                        ctx.projectPath.resolve("src/main/java/com/example/Calculator.java").toString())
                    .put("maxResults", 1),
                ctx -> new SuggestImportsTool(() -> ctx.service))
        );
    }

    @ParameterizedTest(name = "{0}: response.meta carries truncated/totalCount/returnedCount")
    @MethodSource("provideToolArgs")
    @DisplayName("Tools with maxResults must populate truncated, totalCount, returnedCount")
    void contractIsEnforced(String toolName,
                            Function<TestContext, ObjectNode> argsBuilder,
                            Function<TestContext, Tool> toolBuilder) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        JsonNode args = argsBuilder.apply(ctx);
        Tool tool = toolBuilder.apply(ctx);

        var response = tool.execute(args);
        if (!response.isSuccess()) {
            ErrorInfo err = response.getError();
            String detail = err == null ? "<null error>"
                : "[" + err.getCode() + "] " + err.getMessage()
                    + (err.getHint() != null ? " (hint: " + err.getHint() + ")" : "");
            assertTrue(false,
                toolName + " must return a successful response with the chosen args; got error: "
                    + detail);
        }
        ResponseMeta meta = response.getMeta();
        assertNotNull(meta,
            toolName + ": tool with maxResults must populate ResponseMeta. Update tool source.");
        assertNotNull(meta.getTotalCount(),
            toolName + ": ResponseMeta.totalCount must be set. Got meta: " + describe(meta));
        assertNotNull(meta.getReturnedCount(),
            toolName + ": ResponseMeta.returnedCount must be set. Got meta: " + describe(meta));
        assertNotNull(meta.getTruncated(),
            toolName + ": ResponseMeta.truncated must be set. Got meta: " + describe(meta));
    }

    private static String describe(ResponseMeta meta) {
        return "totalCount=" + meta.getTotalCount()
            + ", returnedCount=" + meta.getReturnedCount()
            + ", truncated=" + meta.getTruncated();
    }

    // ========== Test infrastructure ==========

    private static Arguments row(String name,
                                  Function<TestContext, ObjectNode> argsBuilder,
                                  Function<TestContext, Tool> toolBuilder) {
        return Arguments.of(name, argsBuilder, toolBuilder);
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
