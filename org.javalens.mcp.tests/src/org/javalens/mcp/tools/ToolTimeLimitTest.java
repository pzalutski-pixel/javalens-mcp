package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.javalens.mcp.tools.TimeLimitCalls.FIXTURE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the time limit JAVALENS_TIMEOUT_SECONDS sets on each tool call, through the production
 * registerTools() wiring at both seams, the JSON-RPC envelope and execute(): a call whose deadline
 * expires answers exactly TIMEOUT, whatever its work had produced; JDT stops that work through the
 * monitor it is handed; a call that reads (search, AST, type hierarchy, working copies, the project
 * graph) cancelled at any of its cancellation checks leaves no working copy and leaves the next
 * call the answer a fresh load gives; and with the variable unset no call is limited. Deadlines
 * expire at a chosen cancellation check, never on the clock, so every outcome is deterministic.
 * The fixture is described in {@link TimeLimitCalls}.
 */
class ToolTimeLimitTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final TimeLimitCalls calls = new TimeLimitCalls(helper);

    // ========== The TIMEOUT answer ==========

    @Test
    @DisplayName("every tool but load_project and health_check answers exactly TIMEOUT, at both seams, once its deadline has expired")
    void everyTool_expiredDeadline_answersExactlyTimeout() throws Exception {
        JdtServiceImpl service = calls.load("simple-maven", "30");
        calls.expireAt(service, 0);
        EnvelopeHarness envelope = new EnvelopeHarness(service);
        Map<String, ObjectNode> inputs =
            ToolInvocationInputs.buildValidInputs(calls.mapper, helper.getFixturePath("simple-maven"));

        Map<String, String> notTimeout = new TreeMap<>();
        TreeSet<String> called = new TreeSet<>();
        for (String name : envelope.registry().getToolNames()) {
            if (name.equals("load_project") || name.equals("health_check")) {
                continue;
            }
            called.add(name);
            JsonNode answer = envelope.assertEnvelopeFidelity(name, inputs.get(name));
            if (!calls.timeout(name, 30).equals(answer)) {
                notTimeout.put(name, answer.toString());
            }
        }
        assertAll(
            () -> assertEquals(73, called.size(), "every tool that runs within a deadline"),
            () -> assertEquals(Map.of(), notTimeout));
    }

    @Test
    @DisplayName("a search-backed call (find_references) whose deadline expires at its first cancellation check answers exactly TIMEOUT at both seams")
    void searchBackedCall_cancelled_answersExactlyTimeout() throws Exception {
        JdtServiceImpl service = calls.load(FIXTURE, "30");
        calls.expireAt(service, 1);
        JsonNode answer = new EnvelopeHarness(service).assertEnvelopeFidelity("find_references", areaReferences());
        assertAll(
            () -> assertEquals(calls.timeout("find_references", 30), answer),
            () -> assertEquals(List.of(true, true), calls.expiredOfStarted(),
                "each seam's deadline expired at a check JDT made through the monitor"));
    }

    @Test
    @DisplayName("an AST-backed call (get_complexity_metrics) whose deadline expires at its first cancellation check answers exactly TIMEOUT at both seams")
    void astBackedCall_cancelled_answersExactlyTimeout() throws Exception {
        JdtServiceImpl service = calls.load(FIXTURE, "5");
        calls.expireAt(service, 1);
        JsonNode answer = new EnvelopeHarness(service).assertEnvelopeFidelity("get_complexity_metrics",
            calls.args("filePath", calls.file("Canvas.java")));
        assertAll(
            () -> assertEquals(calls.timeout("get_complexity_metrics", 5), answer),
            () -> assertEquals(List.of(true, true), calls.expiredOfStarted(),
                "each seam's deadline expired at a check JDT made through the monitor"));
    }

    // ========== Unset means no limit ==========

    @Test
    @DisplayName("with JAVALENS_TIMEOUT_SECONDS unset no call is limited: the whole answer at both seams, and no deadline started")
    void unset_noCallIsLimited() throws Exception {
        JdtServiceImpl service = calls.load(FIXTURE, null);
        calls.expireAt(service, 0);
        JsonNode answer = new EnvelopeHarness(service).assertEnvelopeFidelity("find_references", areaReferences());
        assertAll(
            () -> assertNull(service.getTimeoutSeconds()),
            () -> assertEquals(areaReferencesAnswer(), answer),
            () -> assertEquals(List.of(), calls.started));
    }

    // ========== The next call after a cancelled one ==========

    @Test
    @DisplayName("find_references (search): cancelled at any of its checks, it answers TIMEOUT and the next call answers exactly as a fresh load")
    void findReferences_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("find_references", areaReferences());
        assertEquals(areaReferencesAnswer(), fresh, "the fresh answer");
    }

    @Test
    @DisplayName("get_complexity_metrics (AST): cancelled at any of its checks, it answers TIMEOUT and the next call answers exactly as a fresh load")
    void complexityMetrics_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("get_complexity_metrics",
            calls.args("filePath", calls.file("Canvas.java")));
        // add 1, totalArea 3 (a loop and a branch), largest 2 (a loop), main 1
        assertAll(
            () -> assertEquals(4, fresh.at("/data/summary/methodCount").asInt()),
            () -> assertEquals(7, fresh.at("/data/summary/totalCyclomaticComplexity").asInt()),
            () -> assertEquals(3, fresh.at("/data/summary/maxMethodCC").asInt()));
    }

    @Test
    @DisplayName("get_type_hierarchy (type hierarchy): cancelled at any of its checks, it answers TIMEOUT and the next call answers exactly as a fresh load")
    void typeHierarchy_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("get_type_hierarchy",
            calls.args("typeName", "com.deadline.Shape"));
        assertEquals(2, fresh.at("/data/totalSubtypes").asInt(), "Circle and Square");
    }

    @Test
    @DisplayName("get_diagnostics (working copies, every file): cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void diagnostics_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("get_diagnostics",
            calls.mapper.createObjectNode());
        assertAll(
            () -> assertEquals(8, fresh.at("/data/filesChecked").asInt()),
            () -> assertEquals(1, fresh.at("/data/errorCount").asInt(), "Draft.java line 6"));
    }

    @Test
    @DisplayName("analyze_file (working copy): cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void analyzeFile_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertEachCancellationLeavesTheFreshAnswer("analyze_file", calls.args("filePath", calls.file("Draft.java")));
    }

    @Test
    @DisplayName("analyze_type (working copy, search): cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void analyzeType_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertEachCancellationLeavesTheFreshAnswer("analyze_type", calls.args("typeName", "com.deadline.Draft"));
    }

    @Test
    @DisplayName("get_quick_fixes (working copy): cancelled at any of its checks, it answers TIMEOUT, leaves no working copy, and the next call answers exactly as a fresh load")
    void quickFixes_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        ObjectNode args = calls.args("filePath", calls.file("Draft.java"));
        args.put("line", 6);
        JsonNode fresh = calls.assertEachCancellationLeavesTheFreshAnswer("get_quick_fixes", args);
        assertEquals(1, fresh.at("/data/problemCount").asInt(), "the type mismatch on line 6");
    }

    @Test
    @DisplayName("find_unreachable_code (project graph): cancelled at any of its checks, it answers TIMEOUT and the next call answers exactly as a fresh load")
    void unreachableCode_cancelledAtAnyCheck_nextCallIsFresh() throws Exception {
        calls.assertEachCancellationLeavesTheFreshAnswer("find_unreachable_code", calls.mapper.createObjectNode());
    }

    // ========== Helpers ==========

    /** find_references on Shape.area(), declared on line 5, column 11 of Shape.java. */
    private ObjectNode areaReferences() {
        return calls.position("Shape.java", 5, 11);
    }

    /** The three calls of area() in Canvas.java, each located at the method name. */
    private JsonNode areaReferencesAnswer() throws Exception {
        return calls.mapper.readTree("""
            {"success":true,"data":{"symbol":"area","symbolKind":"method","containingType":"com.deadline.Shape",
             "totalCount":3,"locations":[
              {"filePath":"src/main/java/com/deadline/Canvas.java","line":17,"column":22,
               "context":"if (shape.area() > 0) {","referenceKind":"METHOD_INVOCATION"},
              {"filePath":"src/main/java/com/deadline/Canvas.java","line":18,"column":31,
               "context":"total += shape.area();","referenceKind":"METHOD_INVOCATION"},
              {"filePath":"src/main/java/com/deadline/Canvas.java","line":27,"column":46,
               "context":"largest = Math.max(largest, shape.area());","referenceKind":"METHOD_INVOCATION"}]},
             "meta":{"totalCount":3,"returnedCount":3,"truncated":false,
              "suggestedNextTools":["go_to_definition to see the symbol definition","get_type_hierarchy for type symbols"]}}
            """);
    }
}
