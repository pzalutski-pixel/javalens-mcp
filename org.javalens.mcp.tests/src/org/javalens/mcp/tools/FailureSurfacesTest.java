package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.FaultyService;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A failure while a tool reads what it answers is the answer: INTERNAL_ERROR with the failure's
 * message, never a success that leaves out what could not be read. Each test loads simple-maven
 * (broken-symbols where it says) into a {@link FaultyService} that fails one service call, one
 * call of the Java elements it hands out or of its search service, or whose compilation units
 * answer reconcile with no syntax tree, and asserts the whole payload through execute() and
 * through the JSON-RPC envelope. A JDT failure that reaches the tool is reported with JDT's status
 * (plug-in, code, severity, message); one read by a helper that cannot throw it names what could
 * not be read.
 */
class FailureSurfacesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String METHOD_REF_TARGET = "src/main/java/com/example/MethodRefTarget.java";
    private static final String ISHAPE = "src/main/java/com/example/IShape.java";
    private static final String GREETING_RECORD = "src/main/java/com/example/GreetingRecord.java";
    private static final String BROKEN_SYMBOLS = "src/main/java/com/example/BrokenSymbols.java";

    private static final Map<String, Object> INJECTED = internalError("Internal error: " + FaultyService.FAILURE);

    /** The answer when a Java element, or the search service, fails with JDT's exception. */
    private static final Map<String, Object> JDT_INJECTED = Map.of("success", false, "error", Map.of(
        "code", "INTERNAL_ERROR",
        "message", "Internal JDT error [" + FaultyService.PLUGIN + ":0, ERROR]: " + FaultyService.FAILURE,
        "hint", "This is a JDT-layer failure; the plugin id + code identifies the JDT subsystem."));

    private static final Map<String, Object> NO_CALCULATOR_TREE =
        internalError("Internal error: JDT produced no syntax tree for " + CALCULATOR);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("find_references whose reference location cannot be read is INTERNAL_ERROR")
    void findReferences_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getContextLine");
        assertFails(service, FindReferencesTool::new, at(CALCULATOR, 14, 15), INJECTED);
    }

    @Test
    @DisplayName("find_method_references whose match location cannot be read is INTERNAL_ERROR")
    void findMethodReferences_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getContextLine");
        assertFails(service, FindMethodReferencesTool::new, at(METHOD_REF_TARGET, 8, 25), INJECTED);
    }

    @Test
    @DisplayName("get_di_registrations whose annotation type lookup fails is INTERNAL_ERROR")
    void diRegistrations_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, GetDiRegistrationsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("find_reflection_usage whose reflection type lookup fails is INTERNAL_ERROR")
    void reflectionUsage_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, FindReflectionUsageTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("get_jpa_model whose entity annotation type lookup fails is INTERNAL_ERROR")
    void jpaModel_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, GetJpaModelTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("get_http_endpoints whose route annotation type lookup fails is INTERNAL_ERROR")
    void httpEndpoints_failedLookup() throws Exception {
        FaultyService service = load().failing("findType");
        assertFails(service, GetHttpEndpointsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("rename_symbol whose edit position cannot be read is INTERNAL_ERROR")
    void renameSymbol_unreadablePosition() throws Exception {
        FaultyService service = load().failing("getLineNumber");
        ObjectNode args = at(CALCULATOR, 14, 15);
        args.put("newName", "sum");
        assertFails(service, RenameSymbolTool::new, args, INJECTED);
    }

    @Test
    @DisplayName("find_possible_bugs over the project, a file of which cannot be read, is INTERNAL_ERROR")
    void possibleBugs_unreadableFile() throws Exception {
        FaultyService service = load().failing("getCompilationUnit");
        assertFails(service, FindPossibleBugsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("get_quick_fixes on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void quickFixes_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(CALCULATOR).toString());
        args.put("line", 14);
        assertFails(service, GetQuickFixesTool::new, args,
            internalError("Internal error: JDT produced no syntax tree for " + CALCULATOR));
    }

    @Test
    @DisplayName("get_diagnostics on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void diagnostics_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(CALCULATOR).toString());
        assertFails(service, GetDiagnosticsTool::new, args,
            internalError("Internal error: JDT produced no syntax tree for " + CALCULATOR));
    }

    // ========== analyze_method, analyze_type, analyze_file ==========

    @Test
    @DisplayName("analyze_method whose caller's call line cannot be read is INTERNAL_ERROR")
    void analyzeMethod_unreadableCallLine() throws Exception {
        FaultyService service = load().failingFrom("getLineNumber", 2);
        assertFails(service, AnalyzeMethodTool::new, at(CALCULATOR, 14, 15), INJECTED);
    }

    @Test
    @DisplayName("analyze_method whose hierarchy search fails is INTERNAL_ERROR")
    void analyzeMethod_failedHierarchy() throws Exception {
        FaultyService service = load().failingSearch("hierarchyOf");
        assertFails(service, AnalyzeMethodTool::new, at(CALCULATOR, 14, 15), JDT_INJECTED);
    }

    @Test
    @DisplayName("analyze_type whose hierarchy search fails is INTERNAL_ERROR")
    void analyzeType_failedHierarchy() throws Exception {
        FaultyService service = load().failingSearch("hierarchyOf");
        assertFails(service, AnalyzeTypeTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("analyze_type whose usage search fails is INTERNAL_ERROR")
    void analyzeType_failedUsageSearch() throws Exception {
        FaultyService service = load().failingSearch("findReferences");
        assertFails(service, AnalyzeTypeTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("analyze_type whose file cannot be reconciled is INTERNAL_ERROR")
    void analyzeType_failedReconcile() throws Exception {
        FaultyService service = load().failingModel("ICompilationUnit.reconcile");
        assertFails(service, AnalyzeTypeTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("analyze_type on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void analyzeType_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        assertFails(service, AnalyzeTypeTool::new, typeName("com.example.Calculator"), NO_CALCULATOR_TREE);
    }

    @Test
    @DisplayName("analyze_file whose file cannot be reconciled is INTERNAL_ERROR")
    void analyzeFile_failedReconcile() throws Exception {
        FaultyService service = load().failingModel("ICompilationUnit.reconcile");
        assertFails(service, AnalyzeFileTool::new, file(CALCULATOR), JDT_INJECTED);
    }

    @Test
    @DisplayName("analyze_file on a file JDT reconciles to no syntax tree is INTERNAL_ERROR naming the file")
    void analyzeFile_noSyntaxTree() throws Exception {
        FaultyService service = load().withoutSyntaxTrees();
        assertFails(service, AnalyzeFileTool::new, file(CALCULATOR), NO_CALCULATOR_TREE);
    }

    // ========== find_field_writes, find_implementations ==========

    @Test
    @DisplayName("find_field_writes whose field type cannot be read is INTERNAL_ERROR")
    void fieldWrites_unreadableFieldType() throws Exception {
        FaultyService service = load().failingModel("IField.getTypeSignature");
        assertFails(service, FindFieldWritesTool::new, at(CALCULATOR, 6, 16), JDT_INJECTED);
    }

    @Test
    @DisplayName("find_field_writes whose write location cannot be read is INTERNAL_ERROR")
    void fieldWrites_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getContextLine");
        assertFails(service, FindFieldWritesTool::new, at(CALCULATOR, 6, 16), INJECTED);
    }

    @Test
    @DisplayName("find_implementations whose target's kind cannot be read is INTERNAL_ERROR")
    void implementations_unreadableKind() throws Exception {
        FaultyService service = load().failingModel("IType.isInterface");
        assertFails(service, FindImplementationsTool::new, at(ISHAPE, 2, 17), JDT_INJECTED);
    }

    @Test
    @DisplayName("find_implementations whose implementing type's location cannot be read is INTERNAL_ERROR")
    void implementations_unreadableTypeLocation() throws Exception {
        FaultyService service = load().failing("getLineNumber");
        assertFails(service, FindImplementationsTool::new, at(ISHAPE, 2, 17), INJECTED);
    }

    @Test
    @DisplayName("find_implementations whose overriding method's location cannot be read is INTERNAL_ERROR")
    void implementations_unreadableMethodLocation() throws Exception {
        FaultyService service = load().failing("getLineNumber");
        assertFails(service, FindImplementationsTool::new, at(ISHAPE, 4, 9), INJECTED);
    }

    // ========== find_tests, find_unused_code, find_unreachable_code ==========

    @Test
    @DisplayName("find_tests over the project, a file of which cannot be read, is INTERNAL_ERROR")
    void findTests_unreadableFile() throws Exception {
        FaultyService service = load().failing("getCompilationUnit");
        assertFails(service, FindTestsTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("find_unused_code over the project, a file of which cannot be read, is INTERNAL_ERROR")
    void unusedCode_unreadableFile() throws Exception {
        FaultyService service = load().failing("getCompilationUnit");
        assertFails(service, FindUnusedCodeTool::new, mapper.createObjectNode(), INJECTED);
    }

    @Test
    @DisplayName("find_unreachable_code, a file of which cannot be read for its test methods, is INTERNAL_ERROR")
    void unreachableCode_unreadableTestFile() throws Exception {
        FaultyService service = load().failing("getCompilationUnit");
        assertFails(service, FindUnreachableCodeTool::new, mapper.createObjectNode(), INJECTED);
    }

    // ========== get_call_hierarchy_incoming ==========

    @Test
    @DisplayName("get_call_hierarchy_incoming whose call location cannot be read is INTERNAL_ERROR")
    void incomingCalls_unreadableLocation() throws Exception {
        FaultyService service = load().failing("getLineNumber");
        assertFails(service, GetCallHierarchyIncomingTool::new, at(CALCULATOR, 14, 15), INJECTED);
    }

    // ========== get_classpath_info, get_project_structure ==========

    @Test
    @DisplayName("get_classpath_info whose output location cannot be read is INTERNAL_ERROR")
    void classpath_unreadableOutputLocation() throws Exception {
        FaultyService service = load().failingModel("IJavaProject.getOutputLocation");
        assertFails(service, GetClasspathInfoTool::new, mapper.createObjectNode(), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_classpath_info whose resolved classpath cannot be read is INTERNAL_ERROR")
    void classpath_unreadableResolvedClasspath() throws Exception {
        FaultyService service = load().failingModel("IJavaProject.getResolvedClasspath");
        assertFails(service, GetClasspathInfoTool::new, mapper.createObjectNode(), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_classpath_info whose system modules cannot be read is INTERNAL_ERROR")
    void classpath_unreadableSystemModules() throws Exception {
        FaultyService service = load().failingModel("IJavaProject.getAllPackageFragmentRoots");
        assertFails(service, GetClasspathInfoTool::new, mapper.createObjectNode(), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_project_structure whose package's files cannot be read is INTERNAL_ERROR")
    void projectStructure_unreadablePackage() throws Exception {
        FaultyService service = load().handingOutProject()
            .failingModelFrom("IPackageFragment.getCompilationUnits", 2);
        assertFails(service, GetProjectStructureTool::new, mapper.createObjectNode(), JDT_INJECTED);
    }

    // ========== get_dependency_graph ==========

    @Test
    @DisplayName("get_dependency_graph whose field type cannot be read is INTERNAL_ERROR")
    void dependencyGraph_unreadableFieldType() throws Exception {
        FaultyService service = load().failingModel("IField.getTypeSignature");
        assertFails(service, GetDependencyGraphTool::new, typeScope("com.example.Calculator", 1), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_dependency_graph whose method return type cannot be read is INTERNAL_ERROR")
    void dependencyGraph_unreadableReturnType() throws Exception {
        FaultyService service = load().failingModel("IMethod.getReturnType");
        assertFails(service, GetDependencyGraphTool::new, typeScope("com.example.Calculator", 1), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_dependency_graph whose supertype name JDT fails to resolve is INTERNAL_ERROR")
    void dependencyGraph_failedTypeResolution() throws Exception {
        FaultyService service = load().failingModel("IType.resolveType");
        assertFails(service, GetDependencyGraphTool::new, typeScope("com.example.Car", 1), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_dependency_graph whose dependency lookup fails is INTERNAL_ERROR")
    void dependencyGraph_failedDependencyLookup() throws Exception {
        FaultyService service = load().failingFrom("findType", 2);
        assertFails(service, GetDependencyGraphTool::new, typeScope("com.example.Car", 2), INJECTED);
    }

    // ========== get_diagnostics, get_document_symbols ==========

    @Test
    @DisplayName("get_diagnostics whose file cannot be reconciled is INTERNAL_ERROR")
    void diagnostics_failedReconcile() throws Exception {
        FaultyService service = load().failingModel("ICompilationUnit.reconcile");
        assertFails(service, GetDiagnosticsTool::new, file(CALCULATOR), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_document_symbols whose type's range cannot be read is INTERNAL_ERROR")
    void documentSymbols_unreadableType() throws Exception {
        FaultyService service = load().handingOutUnits().failingModel("IType.getSourceRange");
        assertFails(service, GetDocumentSymbolsTool::new, file(CALCULATOR), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_document_symbols whose method's range cannot be read is INTERNAL_ERROR")
    void documentSymbols_unreadableMethod() throws Exception {
        FaultyService service = load().handingOutUnits().failingModel("IMethod.getSourceRange");
        assertFails(service, GetDocumentSymbolsTool::new, file(CALCULATOR), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_document_symbols whose field's range cannot be read is INTERNAL_ERROR")
    void documentSymbols_unreadableField() throws Exception {
        FaultyService service = load().handingOutUnits().failingModel("IField.getSourceRange");
        assertFails(service, GetDocumentSymbolsTool::new, file(CALCULATOR), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_document_symbols whose record component's line cannot be read is INTERNAL_ERROR")
    void documentSymbols_unreadableRecordComponent() throws Exception {
        FaultyService service = load().failingFrom("getLineNumber", 2);
        assertFails(service, GetDocumentSymbolsTool::new, file(GREETING_RECORD), INJECTED);
    }

    // ========== get_javadoc, get_signature_help, get_quick_fixes ==========

    @Test
    @DisplayName("get_javadoc whose Javadoc range cannot be read is INTERNAL_ERROR")
    void javadoc_unreadableRange() throws Exception {
        FaultyService service = load().failingModel("getJavadocRange");
        assertFails(service, GetJavadocTool::new, at(CALCULATOR, 14, 15), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_signature_help whose Javadoc range cannot be read is INTERNAL_ERROR")
    void signatureHelp_unreadableJavadoc() throws Exception {
        FaultyService service = load().failingModel("getJavadocRange");
        assertFails(service, GetSignatureHelpTool::new, at(CALCULATOR, 14, 15), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_quick_fixes whose import candidates cannot be searched is INTERNAL_ERROR")
    void quickFixes_failedImportSearch() throws Exception {
        FaultyService service = load("broken-symbols").failing("getJavaProject");
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("broken-symbols").resolve(BROKEN_SYMBOLS).toString());
        args.put("line", 29);
        assertFails(service, GetQuickFixesTool::new, args, INJECTED);
    }

    // ========== get_type_hierarchy, get_type_members, get_type_usage_summary, search_symbols ==========

    @Test
    @DisplayName("get_type_hierarchy whose type's range cannot be read is INTERNAL_ERROR")
    void typeHierarchy_unreadableLocation() throws Exception {
        FaultyService service = load().failingModel("IType.getSourceRange");
        assertFails(service, GetTypeHierarchyTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose methods cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableMethods() throws Exception {
        FaultyService service = load().failingModel("IType.getMethods");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose fields cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableFields() throws Exception {
        FaultyService service = load().failingModel("IType.getFields");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose nested types cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableNestedTypes() throws Exception {
        FaultyService service = load().failingModel("IType.getTypes");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose method's range cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableMethod() throws Exception {
        FaultyService service = load().failingModel("IMethod.getSourceRange");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose field's range cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableField() throws Exception {
        FaultyService service = load().failingModel("IField.getSourceRange");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members whose nested type's range cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableNestedType() throws Exception {
        FaultyService service = load().failingModel("IType.getSourceRange");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.AnnotationsFixture"), JDT_INJECTED);
    }

    @Test
    @DisplayName("get_type_members of a member type whose enclosing type's members cannot be read is INTERNAL_ERROR")
    void typeMembers_unreadableEnclosingMembers() throws Exception {
        FaultyService service = load().failingModel("IType.getChildren");
        assertFails(service, GetTypeMembersTool::new, typeName("com.example.AnnotationsFixture.Tag"),
            internalError("Internal error: Cannot read the members of AnnotationsFixture: " + FaultyService.FAILURE));
    }

    @Test
    @DisplayName("get_type_usage_summary whose type's modifiers cannot be read is INTERNAL_ERROR")
    void typeUsageSummary_unreadableModifiers() throws Exception {
        FaultyService service = load().failingModel("IType.getFlags");
        assertFails(service, GetTypeUsageSummaryTool::new, typeName("com.example.Calculator"), JDT_INJECTED);
    }

    @Test
    @DisplayName("search_symbols whose method's parameter names cannot be read is INTERNAL_ERROR")
    void searchSymbols_unreadableSignature() throws Exception {
        FaultyService service = load().failingModel("IMethod.getParameterNames");
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "add");
        args.put("kind", "method");
        assertFails(service, SearchSymbolsTool::new, args, JDT_INJECTED);
    }

    private FaultyService load() throws Exception {
        return load("simple-maven");
    }

    private FaultyService load(String fixture) throws Exception {
        FaultyService service = new FaultyService();
        service.loadProject(helper.getFixturePath(fixture));
        return service;
    }

    private Path fixture() {
        return helper.getFixturePath("simple-maven");
    }

    private ObjectNode at(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    private ObjectNode file(String file) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", fixture().resolve(file).toString());
        return args;
    }

    private ObjectNode typeName(String name) {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", name);
        return args;
    }

    private ObjectNode typeScope(String name, int depth) {
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", name);
        args.put("depth", depth);
        return args;
    }

    private static Map<String, Object> internalError(String message) {
        return Map.of("success", false, "error", Map.of(
            "code", "INTERNAL_ERROR",
            "message", message,
            "hint", "This may be a bug. Check server logs for details."));
    }

    /**
     * The payload of the tool {@code create} makes over {@code service}, through execute() and
     * through the JSON-RPC envelope, both equal {@code expected}; each call counts the calls of
     * the service's faults from the first.
     */
    private void assertFails(FaultyService service, Function<Supplier<IJdtService>, Tool> create,
                             ObjectNode args, Map<String, Object> expected) throws Exception {
        Tool tool = create.apply(() -> service);
        JsonNode expectedJson = mapper.valueToTree(expected);
        service.resetCalls();
        JsonNode direct = mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy())));
        service.resetCalls();
        JsonNode wire = new EnvelopeHarness(service).assertEnvelopeFidelity(tool.getName(), args);
        assertAll(
            () -> assertEquals(expectedJson, direct, "execute() payload for " + args),
            () -> assertEquals(expectedJson, wire, "envelope payload for " + args));
    }
}
