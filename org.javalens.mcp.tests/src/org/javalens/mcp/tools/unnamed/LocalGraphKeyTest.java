package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.SHADOW;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.SHADOW_TEST;

/**
 * A local or anonymous class, a class nested in one, and their members are no nodes of the
 * project graph and have no graph key, so the graph tools refuse them, naming the element, and
 * never answer for a member class whose name they share. Shadow.java in unnamed-semantics
 * ({@link UnnamedSemantics}) declares the member class {@code Local} and, in {@code probe()}, a
 * local class {@code Local}, each with a {@code depth()}.
 *
 * <p>The graph attributes a call in an anonymous class body to the method declaring the class:
 * the member class's {@code depth()} is called by {@code measure()}, which {@code runner()} (for
 * its anonymous Runnable) and the test {@code measures()} call. The local class's
 * {@code depth()} is called by {@code probe()} only, which the test {@code probes()} calls.
 */
class LocalGraphKeyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String SHADOW_TYPE = PACKAGE + "Shadow";
    private static final String TESTS_REFUSAL =
        "Invalid parameter 'position': affected-test analysis supports project methods, fields, and types; "
            + "no graph node for '";
    private static final String TRANSITIVE_REFUSAL =
        "Invalid parameter 'transitive': transitive analysis supports project methods, fields, and types; "
            + "no graph node for '";
    private static final Map<String, Object> TESTS_DATA_MEASURES = map(
        "className", PACKAGE + "ShadowTest",
        "methodName", "measures",
        "key", PACKAGE + "ShadowTest#measures()",
        "filePath", SHADOW_TEST,
        "line", 7);
    private static final Map<String, Object> TESTS_META = map(
        "totalCount", 1,
        "returnedCount", 1,
        "truncated", false,
        "suggestedNextTools", List.of(
            "analyze_change_impact with transitive=true for the full blast radius",
            "find_tests for the project's whole test inventory"));

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedSemantics.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("find_affected_tests on depth of the local class Local is INVALID_PARAMETER: no graph node for 'depth'")
    void affectedTests_localClassMethod() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(12, 16),
            invalidParameter(TESTS_REFUSAL + "depth'"));
    }

    @Test
    @DisplayName("find_affected_tests on the local class Local is INVALID_PARAMETER: no graph node for 'Local'")
    void affectedTests_localClass() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(11, 14),
            invalidParameter(TESTS_REFUSAL + "Local'"));
    }

    @Test
    @DisplayName("find_affected_tests on depth of the member class Inner of the local class Holder is INVALID_PARAMETER: no graph node for 'depth'")
    void affectedTests_memberOfLocalClass() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(34, 20),
            invalidParameter(TESTS_REFUSAL + "depth'"));
    }

    @Test
    @DisplayName("find_affected_tests on run of anonymous class 1 is INVALID_PARAMETER: no graph node for 'run'")
    void affectedTests_anonymousClassMethod() {
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(25, 24),
            invalidParameter(TESTS_REFUSAL + "run'"));
    }

    @Test
    @DisplayName("find_affected_tests on depth of the member class Shadow.Local finds ShadowTest#measures() only")
    void affectedTests_memberClassMethod() {
        Map<String, Object> data = map(
            "symbol", "depth",
            "symbolType", "SourceMethod",
            "testMethodCount", 1,
            "testMethods", List.of(TESTS_DATA_MEASURES));
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(5, 12),
            success(data, TESTS_META));
    }

    @Test
    @DisplayName("find_affected_tests on the member class Shadow.Local finds ShadowTest#measures() only")
    void affectedTests_memberClass() {
        Map<String, Object> data = map(
            "symbol", "Local",
            "symbolType", "SourceType",
            "testMethodCount", 1,
            "testMethods", List.of(TESTS_DATA_MEASURES));
        assertPayload(mapper, new FindAffectedTestsTool(() -> service), envelope, position(4, 17),
            success(data, TESTS_META));
    }

    @Test
    @DisplayName("analyze_change_impact transitive on depth of the local class Local is INVALID_PARAMETER: no graph node for 'depth'")
    void transitiveImpact_localClassMethod() {
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, transitive(12, 16),
            invalidParameter(TRANSITIVE_REFUSAL + "depth'"));
    }

    @Test
    @DisplayName("analyze_change_impact transitive on the local class Local is INVALID_PARAMETER: no graph node for 'Local'")
    void transitiveImpact_localClass() {
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, transitive(11, 14),
            invalidParameter(TRANSITIVE_REFUSAL + "Local'"));
    }

    @Test
    @DisplayName("analyze_change_impact transitive on depth of the member class Shadow.Local names measure, runner and ShadowTest#measures()")
    void transitiveImpact_memberClassMethod() {
        // Keys sort with # before T: Shadow#measure(), Shadow#runner(), then ShadowTest#measures().
        Map<String, Object> data = map(
            "symbol", "depth",
            "symbolType", "SourceMethod",
            "transitive", true,
            "totalAffectedMethods", 3,
            "affectedMethods", List.of(
                SHADOW_TYPE + "#measure()",
                SHADOW_TYPE + "#runner()",
                PACKAGE + "ShadowTest#measures()"),
            "affectedFiles", List.of(
                map("filePath", SHADOW, "methodCount", 2),
                map("filePath", SHADOW_TEST, "methodCount", 1)));
        Map<String, Object> meta = map(
            "totalCount", 3,
            "returnedCount", 3,
            "truncated", false,
            "suggestedNextTools", List.of(
                "find_affected_tests for the tests covering this symbol",
                "find_references for the direct call sites with locations"));
        assertPayload(mapper, new AnalyzeChangeImpactTool(() -> service), envelope, transitive(5, 12),
            success(data, meta));
    }

    private ObjectNode transitive(int line, int column) {
        ObjectNode args = position(line, column);
        args.put("transitive", true);
        return args;
    }

    private ObjectNode position(int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(UnnamedSemantics.FIXTURE).resolve(SHADOW).toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
