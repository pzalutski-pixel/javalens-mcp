package org.javalens.mcp.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.AnalyzeControlFlowTool;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.javalens.mcp.tools.FindCastsTool;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetFieldAtPositionTool;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.javalens.mcp.tools.GetJavadocTool;
import org.javalens.mcp.tools.GetMethodAtPositionTool;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.javalens.mcp.tools.GetSignatureHelpTool;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.javalens.mcp.tools.InlineMethodTool;
import org.javalens.mcp.tools.InlineVariableTool;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.javalens.mcp.tools.RenameSymbolTool;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the error-code contract per tool. For every tool that has at least one
 * required input parameter, verifies that calling with empty args returns
 * {@code INVALID_PARAMETER}.
 *
 * <p>Today error codes are unit-tested at the {@code ToolResponse} helper level
 * only ({@code ToolResponseTest}). This test goes per-tool and asserts the
 * documented code surfaces on the documented failure path. Catches:
 *  - Tools that swallow the missing-param check and return
 *    {@code INTERNAL_ERROR} or {@code SYMBOL_NOT_FOUND} instead.
 *  - Tools that don't validate required params at all.
 */
class ErrorCodeContractTest {

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

    static Stream<Arguments> toolsWithRequiredParams() {
        // (name, toolBuilder) — tools that have at least one required input
        // parameter. Calling with empty args must return INVALID_PARAMETER.
        return Stream.of(
            Arguments.of("search_symbols",
                (Function<TestContext, Tool>) ctx -> new SearchSymbolsTool(() -> ctx.service)),
            Arguments.of("go_to_definition",
                (Function<TestContext, Tool>) ctx -> new GoToDefinitionTool(() -> ctx.service)),
            Arguments.of("find_references",
                (Function<TestContext, Tool>) ctx -> new FindReferencesTool(() -> ctx.service)),
            Arguments.of("find_implementations",
                (Function<TestContext, Tool>) ctx -> new FindImplementationsTool(() -> ctx.service)),
            Arguments.of("get_document_symbols",
                (Function<TestContext, Tool>) ctx -> new GetDocumentSymbolsTool(() -> ctx.service)),
            Arguments.of("get_type_members",
                (Function<TestContext, Tool>) ctx -> new GetTypeMembersTool(() -> ctx.service)),
            Arguments.of("get_symbol_info",
                (Function<TestContext, Tool>) ctx -> new GetSymbolInfoTool(() -> ctx.service)),
            Arguments.of("get_type_at_position",
                (Function<TestContext, Tool>) ctx -> new GetTypeAtPositionTool(() -> ctx.service)),
            Arguments.of("get_method_at_position",
                (Function<TestContext, Tool>) ctx -> new GetMethodAtPositionTool(() -> ctx.service)),
            Arguments.of("get_field_at_position",
                (Function<TestContext, Tool>) ctx -> new GetFieldAtPositionTool(() -> ctx.service)),
            Arguments.of("get_hover_info",
                (Function<TestContext, Tool>) ctx -> new GetHoverInfoTool(() -> ctx.service)),
            Arguments.of("get_javadoc",
                (Function<TestContext, Tool>) ctx -> new GetJavadocTool(() -> ctx.service)),
            Arguments.of("get_signature_help",
                (Function<TestContext, Tool>) ctx -> new GetSignatureHelpTool(() -> ctx.service)),
            Arguments.of("get_enclosing_element",
                (Function<TestContext, Tool>) ctx -> new GetEnclosingElementTool(() -> ctx.service)),
            Arguments.of("get_super_method",
                (Function<TestContext, Tool>) ctx -> new GetSuperMethodTool(() -> ctx.service)),
            Arguments.of("get_call_hierarchy_incoming",
                (Function<TestContext, Tool>) ctx -> new GetCallHierarchyIncomingTool(() -> ctx.service)),
            Arguments.of("get_call_hierarchy_outgoing",
                (Function<TestContext, Tool>) ctx -> new GetCallHierarchyOutgoingTool(() -> ctx.service)),
            Arguments.of("find_field_writes",
                (Function<TestContext, Tool>) ctx -> new FindFieldWritesTool(() -> ctx.service)),
            Arguments.of("rename_symbol",
                (Function<TestContext, Tool>) ctx -> new RenameSymbolTool(() -> ctx.service)),
            Arguments.of("organize_imports",
                (Function<TestContext, Tool>) ctx -> new OrganizeImportsTool(() -> ctx.service)),
            Arguments.of("extract_variable",
                (Function<TestContext, Tool>) ctx -> new ExtractVariableTool(() -> ctx.service)),
            Arguments.of("extract_method",
                (Function<TestContext, Tool>) ctx -> new ExtractMethodTool(() -> ctx.service)),
            Arguments.of("extract_constant",
                (Function<TestContext, Tool>) ctx -> new ExtractConstantTool(() -> ctx.service)),
            Arguments.of("extract_interface",
                (Function<TestContext, Tool>) ctx -> new ExtractInterfaceTool(() -> ctx.service)),
            Arguments.of("inline_variable",
                (Function<TestContext, Tool>) ctx -> new InlineVariableTool(() -> ctx.service)),
            Arguments.of("inline_method",
                (Function<TestContext, Tool>) ctx -> new InlineMethodTool(() -> ctx.service)),
            Arguments.of("convert_anonymous_to_lambda",
                (Function<TestContext, Tool>) ctx -> new ConvertAnonymousToLambdaTool(() -> ctx.service)),
            Arguments.of("find_annotation_usages",
                (Function<TestContext, Tool>) ctx -> new FindAnnotationUsagesTool(() -> ctx.service)),
            Arguments.of("find_type_instantiations",
                (Function<TestContext, Tool>) ctx -> new FindTypeInstantiationsTool(() -> ctx.service)),
            Arguments.of("find_casts",
                (Function<TestContext, Tool>) ctx -> new FindCastsTool(() -> ctx.service)),
            Arguments.of("find_instanceof_checks",
                (Function<TestContext, Tool>) ctx -> new FindInstanceofChecksTool(() -> ctx.service)),
            Arguments.of("find_throws_declarations",
                (Function<TestContext, Tool>) ctx -> new FindThrowsDeclarationsTool(() -> ctx.service)),
            Arguments.of("find_catch_blocks",
                (Function<TestContext, Tool>) ctx -> new FindCatchBlocksTool(() -> ctx.service)),
            Arguments.of("find_method_references",
                (Function<TestContext, Tool>) ctx -> new FindMethodReferencesTool(() -> ctx.service)),
            Arguments.of("find_type_arguments",
                (Function<TestContext, Tool>) ctx -> new FindTypeArgumentsTool(() -> ctx.service)),
            Arguments.of("analyze_file",
                (Function<TestContext, Tool>) ctx -> new AnalyzeFileTool(() -> ctx.service)),
            Arguments.of("analyze_type",
                (Function<TestContext, Tool>) ctx -> new AnalyzeTypeTool(() -> ctx.service)),
            Arguments.of("analyze_method",
                (Function<TestContext, Tool>) ctx -> new AnalyzeMethodTool(() -> ctx.service)),
            Arguments.of("get_type_usage_summary",
                (Function<TestContext, Tool>) ctx -> new GetTypeUsageSummaryTool(() -> ctx.service)),
            Arguments.of("suggest_imports",
                (Function<TestContext, Tool>) ctx -> new SuggestImportsTool(() -> ctx.service)),
            Arguments.of("get_quick_fixes",
                (Function<TestContext, Tool>) ctx -> new GetQuickFixesTool(() -> ctx.service)),
            Arguments.of("get_complexity_metrics",
                (Function<TestContext, Tool>) ctx -> new GetComplexityMetricsTool(() -> ctx.service)),
            Arguments.of("get_dependency_graph",
                (Function<TestContext, Tool>) ctx -> new GetDependencyGraphTool(() -> ctx.service)),
            Arguments.of("analyze_change_impact",
                (Function<TestContext, Tool>) ctx -> new AnalyzeChangeImpactTool(() -> ctx.service)),
            Arguments.of("analyze_control_flow",
                (Function<TestContext, Tool>) ctx -> new AnalyzeControlFlowTool(() -> ctx.service)),
            Arguments.of("analyze_data_flow",
                (Function<TestContext, Tool>) ctx -> new AnalyzeDataFlowTool(() -> ctx.service))
        );
    }

    @ParameterizedTest(name = "{0}: empty args → INVALID_PARAMETER")
    @MethodSource("toolsWithRequiredParams")
    @DisplayName("Tools with required params: empty args returns INVALID_PARAMETER")
    void emptyArgs_returnsInvalidParameter(String toolName, Function<TestContext, Tool> toolBuilder) {
        TestContext ctx = new TestContext(service, projectPath, objectMapper);
        ObjectNode emptyArgs = objectMapper.createObjectNode();
        Tool tool = toolBuilder.apply(ctx);
        var response = tool.execute(emptyArgs);
        assertFalse(response.isSuccess(),
            toolName + " must fail when called with empty args");
        ErrorInfo err = response.getError();
        assertNotNull(err, toolName + ": error response must carry ErrorInfo");
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            toolName + ": missing required param must yield INVALID_PARAMETER; got code=" + err.getCode()
                + ", message=" + err.getMessage());
    }

    record TestContext(JdtServiceImpl service, Path projectPath, ObjectMapper objectMapper) {}
}
