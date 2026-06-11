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
import org.javalens.mcp.tools.ApplyCleanupTool;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.javalens.mcp.tools.DiagnoseAndFixTool;
import org.javalens.mcp.tools.EncapsulateFieldTool;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.javalens.mcp.tools.ExtractSuperclassTool;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.IntroduceParameterObjectTool;
import org.javalens.mcp.tools.MoveTypeToNewFileTool;
import org.javalens.mcp.tools.PullUpTool;
import org.javalens.mcp.tools.PushDownTool;
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
                (Function<TestContext, Tool>) ctx -> new AnalyzeDataFlowTool(() -> ctx.service)),
            // 1.4.1 descriptor refactorings + compound tools (added by the 1.4.2 drift guard)
            Arguments.of("apply_cleanup",
                (Function<TestContext, Tool>) ctx -> new ApplyCleanupTool(() -> ctx.service)),
            Arguments.of("apply_quick_fix",
                (Function<TestContext, Tool>) ctx -> new ApplyQuickFixTool(() -> ctx.service)),
            Arguments.of("change_method_signature",
                (Function<TestContext, Tool>) ctx -> new ChangeMethodSignatureTool(() -> ctx.service)),
            Arguments.of("diagnose_and_fix",
                (Function<TestContext, Tool>) ctx -> new DiagnoseAndFixTool(() -> ctx.service)),
            Arguments.of("encapsulate_field",
                (Function<TestContext, Tool>) ctx -> new EncapsulateFieldTool(() -> ctx.service)),
            Arguments.of("extract_superclass",
                (Function<TestContext, Tool>) ctx -> new ExtractSuperclassTool(() -> ctx.service)),
            Arguments.of("introduce_parameter_object",
                (Function<TestContext, Tool>) ctx -> new IntroduceParameterObjectTool(() -> ctx.service)),
            Arguments.of("move_type_to_new_file",
                (Function<TestContext, Tool>) ctx -> new MoveTypeToNewFileTool(() -> ctx.service)),
            Arguments.of("pull_up",
                (Function<TestContext, Tool>) ctx -> new PullUpTool(() -> ctx.service)),
            Arguments.of("push_down",
                (Function<TestContext, Tool>) ctx -> new PushDownTool(() -> ctx.service)),
            // 1.4.2 additions
            Arguments.of("find_affected_tests",
                (Function<TestContext, Tool>) ctx -> new FindAffectedTestsTool(() -> ctx.service))
        );
    }

    /** Reflectively build the full production registry against the given service. */
    private static org.javalens.mcp.tools.ToolRegistry buildRegistry(JdtServiceImpl svc) throws Exception {
        org.javalens.mcp.JavaLensApplication app = new org.javalens.mcp.JavaLensApplication();
        java.lang.reflect.Field svcField =
            org.javalens.mcp.JavaLensApplication.class.getDeclaredField("jdtService");
        svcField.setAccessible(true);
        svcField.set(app, svc);
        java.lang.reflect.Field registryField =
            org.javalens.mcp.JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        org.javalens.mcp.tools.ToolRegistry registry = new org.javalens.mcp.tools.ToolRegistry();
        registryField.set(app, registry);
        java.lang.reflect.Method registerTools =
            org.javalens.mcp.JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        return registry;
    }

    private static final java.util.Set<String> SERVICE_INDEPENDENT =
        java.util.Set.of("health_check", "load_project");

    @SuppressWarnings("unchecked")
    private static java.util.List<String> requiredParams(Tool tool) {
        Object required = tool.getInputSchema().get("required");
        return required instanceof java.util.List<?> list
            ? (java.util.List<String>) list : java.util.List.of();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the required-params enumeration matches the live registry exactly (no drift)")
    void enumerationMatchesRegistry() throws Exception {
        java.util.Set<String> enumerated = toolsWithRequiredParams()
            .map(args -> (String) args.get()[0])
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        java.util.Set<String> fromRegistry = new java.util.TreeSet<>();
        org.javalens.mcp.tools.ToolRegistry registry = buildRegistry(service);
        for (String name : registry.getToolNames()) {
            if (SERVICE_INDEPENDENT.contains(name)) {
                continue;
            }
            if (!requiredParams(registry.getTool(name).orElseThrow()).isEmpty()) {
                fromRegistry.add(name);
            }
        }
        assertEquals(fromRegistry, enumerated,
            "toolsWithRequiredParams() must list exactly the registry's required-param tools; "
                + "missing rows = drift, extra rows = stale");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("every service-dependent tool reports exactly PROJECT_NOT_LOADED without a service")
    void noService_isExactlyProjectNotLoaded() throws Exception {
        org.javalens.mcp.tools.ToolRegistry serviceless = buildRegistry(null);
        java.util.Map<String, String> wrongCodes = new java.util.TreeMap<>();
        for (String name : serviceless.getToolNames()) {
            if (SERVICE_INDEPENDENT.contains(name)) {
                continue;
            }
            var response = serviceless.getTool(name).orElseThrow()
                .execute(objectMapper.createObjectNode());
            String code = response.isSuccess() ? "<success>"
                : response.getError() == null ? "<no error info>" : response.getError().getCode();
            if (!"PROJECT_NOT_LOADED".equals(code)) {
                wrongCodes.put(name, code);
            }
        }
        assertEquals(java.util.Map.of(), wrongCodes,
            "every service-dependent tool must report PROJECT_NOT_LOADED without a service");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("filePath-requiring tools report a documented input-error code for a nonexistent file")
    void nonexistentFile_isDocumentedInputError() throws Exception {
        java.util.Set<String> allowedCodes =
            java.util.Set.of("FILE_NOT_FOUND", "SYMBOL_NOT_FOUND", "INVALID_PARAMETER");
        java.util.Map<String, String> violations = new java.util.TreeMap<>();
        java.util.Set<String> probed = new java.util.TreeSet<>();

        org.javalens.mcp.tools.ToolRegistry registry = buildRegistry(service);
        for (String name : registry.getToolNames()) {
            if (SERVICE_INDEPENDENT.contains(name)) {
                continue;
            }
            Tool tool = registry.getTool(name).orElseThrow();
            java.util.List<String> required = requiredParams(tool);
            if (!required.contains("filePath")) {
                continue;
            }
            probed.add(name);

            ObjectNode args = objectMapper.createObjectNode();
            for (String param : required) {
                if (param.equals("filePath")) {
                    args.put("filePath", "no/such/Missing.java");
                } else if (param.equals("line") || param.equals("column")
                    || param.equals("endLine") || param.equals("endColumn")) {
                    args.put(param, 0);
                } else {
                    args.put(param, "x");
                }
            }

            var response = tool.execute(args);
            String code = response.isSuccess() ? "<success>"
                : response.getError() == null ? "<no error info>" : response.getError().getCode();
            if (!allowedCodes.contains(code)) {
                violations.put(name, code);
            }
        }

        assertFalse(probed.isEmpty(), "probe found no filePath-requiring tools - audit the schema scan");
        assertEquals(java.util.Map.of(), violations,
            "nonexistent filePath must yield a documented input-error code " + allowedCodes);
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
        // The AI consumer needs the message to identify what's missing — a regression that
        // emitted code=INVALID_PARAMETER with a blank/null message would still satisfy the
        // code check but leave callers unable to fix their request. Pin both.
        assertNotNull(err.getMessage(),
            toolName + ": error message must not be null on INVALID_PARAMETER response");
        assertFalse(err.getMessage().isBlank(),
            toolName + ": error message must not be blank on INVALID_PARAMETER response");
        // Error response must NOT carry data — a stale success-payload leaked into an error
        // response would mislead consumers. Pin null/absent.
        assertEquals(null, response.getData(),
            toolName + ": error response must not carry data; got: " + response.getData());
    }

    record TestContext(JdtServiceImpl service, Path projectPath, ObjectMapper objectMapper) {}
}
