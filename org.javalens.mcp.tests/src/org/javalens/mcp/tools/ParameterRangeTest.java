package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A numeric parameter outside its documented range is INVALID_PARAMETER naming the range, never
 * moved into the range without a word: maxResults of find_references, find_implementations,
 * find_field_writes and search_symbols (0 to 1000), of get_call_hierarchy_incoming (0 to 500) and
 * of get_document_symbols (0 to 2000); maxDepth of get_project_structure (1 to 20); maxResults of
 * get_type_hierarchy (1 to 50); depth of analyze_change_impact (1 to 3); maxUsages of
 * analyze_type (0 or more); maxCycleLength of find_circular_dependencies (2 or more: a cycle joins
 * at least two packages); depth of get_dependency_graph (1 or more). The fixture is
 * simple-maven: Calculator.java declares the field {@code lastResult} (line 6, column 16) and
 * {@code add} (line 14, column 15); IShape.java declares the interface {@code IShape} (line 2,
 * column 17). Each payload is pinned through execute() and through the JSON-RPC envelope.
 */
class ParameterRangeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String CALCULATOR = "src/main/java/com/example/Calculator.java";
    private static final String ISHAPE = "src/main/java/com/example/IShape.java";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("find_references: maxResults 1001 is INVALID_PARAMETER, the range 0 to 1000")
    void findReferences_maxResultsAboveRange() throws Exception {
        assertRefused(new FindReferencesTool(() -> service), at(CALCULATOR, 14, 15), "maxResults", 1001,
            "Must be between 0 and 1000; got: 1001");
    }

    @Test
    @DisplayName("find_implementations: maxResults 1001 is INVALID_PARAMETER, the range 0 to 1000")
    void findImplementations_maxResultsAboveRange() throws Exception {
        assertRefused(new FindImplementationsTool(() -> service), at(ISHAPE, 2, 17), "maxResults", 1001,
            "Must be between 0 and 1000; got: 1001");
    }

    @Test
    @DisplayName("find_field_writes: maxResults 1001 is INVALID_PARAMETER, the range 0 to 1000")
    void findFieldWrites_maxResultsAboveRange() throws Exception {
        assertRefused(new FindFieldWritesTool(() -> service), at(CALCULATOR, 6, 16), "maxResults", 1001,
            "Must be between 0 and 1000; got: 1001");
    }

    @Test
    @DisplayName("search_symbols: maxResults 1001 is INVALID_PARAMETER, the range 0 to 1000")
    void searchSymbols_maxResultsAboveRange() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "Calculator");
        assertRefused(new SearchSymbolsTool(() -> service), args, "maxResults", 1001,
            "Must be between 0 and 1000; got: 1001");
    }

    @Test
    @DisplayName("get_call_hierarchy_incoming: maxResults 501 is INVALID_PARAMETER, the range 0 to 500")
    void callHierarchyIncoming_maxResultsAboveRange() throws Exception {
        assertRefused(new GetCallHierarchyIncomingTool(() -> service), at(CALCULATOR, 14, 15), "maxResults", 501,
            "Must be between 0 and 500; got: 501");
    }

    @Test
    @DisplayName("get_document_symbols: maxResults 2001 is INVALID_PARAMETER, the range 0 to 2000")
    void documentSymbols_maxResultsAboveRange() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file(CALCULATOR));
        assertRefused(new GetDocumentSymbolsTool(() -> service), args, "maxResults", 2001,
            "Must be between 0 and 2000; got: 2001");
    }

    @Test
    @DisplayName("get_project_structure: maxDepth 0 and 21 are INVALID_PARAMETER, the range 1 to 20")
    void projectStructure_maxDepthOutsideRange() throws Exception {
        GetProjectStructureTool tool = new GetProjectStructureTool(() -> service);
        assertRefused(tool, mapper.createObjectNode(), "maxDepth", 0, "Must be between 1 and 20; got: 0");
        assertRefused(tool, mapper.createObjectNode(), "maxDepth", 21, "Must be between 1 and 20; got: 21");
    }

    @Test
    @DisplayName("get_type_hierarchy: maxResults 0 and 51 are INVALID_PARAMETER, the range 1 to 50")
    void typeHierarchy_maxResultsOutsideRange() throws Exception {
        GetTypeHierarchyTool tool = new GetTypeHierarchyTool(() -> service);
        assertRefused(tool, at(ISHAPE, 2, 17), "maxResults", 0, "Must be between 1 and 50; got: 0");
        assertRefused(tool, at(ISHAPE, 2, 17), "maxResults", 51, "Must be between 1 and 50; got: 51");
    }

    @Test
    @DisplayName("analyze_change_impact: depth 0 and 4 are INVALID_PARAMETER, the range 1 to 3")
    void changeImpact_depthOutsideRange() throws Exception {
        AnalyzeChangeImpactTool tool = new AnalyzeChangeImpactTool(() -> service);
        assertRefused(tool, at(CALCULATOR, 14, 15), "depth", 0, "Must be between 1 and 3; got: 0");
        assertRefused(tool, at(CALCULATOR, 14, 15), "depth", 4, "Must be between 1 and 3; got: 4");
    }

    @Test
    @DisplayName("analyze_type: maxUsages -1 is INVALID_PARAMETER, the range 0 or more")
    void analyzeType_negativeMaxUsages() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        assertRefused(new AnalyzeTypeTool(() -> service), args, "maxUsages", -1, "Must be >= 0; got: -1");
    }

    @Test
    @DisplayName("find_circular_dependencies: maxCycleLength 1 and -1 are INVALID_PARAMETER, the range 2 or more")
    void circularDependencies_maxCycleLengthBelowRange() throws Exception {
        FindCircularDependenciesTool tool = new FindCircularDependenciesTool(() -> service);
        assertRefused(tool, mapper.createObjectNode(), "maxCycleLength", 1, "Must be >= 2; got: 1");
        assertRefused(tool, mapper.createObjectNode(), "maxCycleLength", -1, "Must be >= 2; got: -1");
    }

    @Test
    @DisplayName("get_dependency_graph: depth 0 and -1 are INVALID_PARAMETER, the range 1 or more")
    void dependencyGraph_depthBelowRange() throws Exception {
        GetDependencyGraphTool tool = new GetDependencyGraphTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.Calculator");
        assertRefused(tool, args, "depth", 0, "Must be >= 1; got: 0");
        assertRefused(tool, args, "depth", -1, "Must be >= 1; got: -1");
        ObjectNode packageArgs = mapper.createObjectNode();
        packageArgs.put("scope", "package");
        packageArgs.put("name", "com.example");
        assertRefused(tool, packageArgs, "depth", 0, "Must be >= 1; got: 0");
    }

    @Test
    @DisplayName("analyze_type: maxUsages 0 is accepted, Calculator's usage counts unclipped")
    void analyzeType_zeroMaxUsagesAccepted() throws Exception {
        // Calculator's uses (AnalyzeTypeToolTest.calculator_exactMembersHierarchyAndUsages): 5
        // instantiations, 1 cast, 2 instanceof checks, 2 type arguments; maxUsages clips only
        // the sample of places the search could not confirm, of which Calculator has none.
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxUsages", 0);
        AnalyzeTypeTool tool = new AnalyzeTypeTool(() -> service);
        JsonNode expected = mapper.valueToTree(Map.of(
            "instantiations", 5, "casts", 1, "instanceofChecks", 2, "typeArguments", 2, "total", 10));
        JsonNode direct = mapper.readTree(mapper.writeValueAsString(tool.execute(args.deepCopy())));
        JsonNode wire = envelope.assertEnvelopeFidelity(tool.getName(), args);
        assertAll(
            () -> assertEquals(true, direct.path("success").asBoolean(), "execute() success for " + args),
            () -> assertEquals(expected, direct.path("data").path("usages"), "execute() usages for " + args),
            () -> assertEquals(expected, wire.path("data").path("usages"), "envelope usages for " + args));
    }

    private String file(String relative) {
        return helper.getFixturePath("simple-maven").resolve(relative).toString();
    }

    private ObjectNode at(String relative, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file(relative));
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    /**
     * {@code tool} called with {@code args} and {@code parameter} set to {@code value} answers,
     * through execute() and through the JSON-RPC envelope, exactly INVALID_PARAMETER with
     * {@code reason}.
     */
    private void assertRefused(Tool tool, ObjectNode args, String parameter, int value, String reason)
            throws Exception {
        ObjectNode call = args.deepCopy();
        call.put(parameter, value);
        JsonNode expected = mapper.valueToTree(Map.of("success", false, "error", Map.of(
            "code", "INVALID_PARAMETER",
            "message", "Invalid parameter '" + parameter + "': " + reason)));
        JsonNode direct = mapper.readTree(mapper.writeValueAsString(tool.execute(call.deepCopy())));
        JsonNode wire = envelope.assertEnvelopeFidelity(tool.getName(), call);
        assertAll(
            () -> assertEquals(expected, direct, "execute() payload for " + call),
            () -> assertEquals(expected, wire, "envelope payload for " + call));
    }
}
