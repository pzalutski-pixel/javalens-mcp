package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins analyze_data_flow's opt-in interprocedural mode (issue #23) against
 * the flow-maven fixture: null facts tracked through argument-to-parameter
 * hops to dereference sinks, taint facts (selected method's parameters)
 * propagated through aliases and concatenation to escape sinks, depth
 * bounding, recursion termination, and off-by-default isolation.
 *
 * <p>The analysis is a may-analysis: assignments that could kill a fact
 * (e.g. a conditional reassignment before the call) do not suppress flows.
 * Returned values are not tracked back into callers.
 */
class AnalyzeDataFlowInterproceduralTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeDataFlowTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("flow-maven");
        tool = new AnalyzeDataFlowTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("flow-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flows(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("interproceduralFlows");
    }

    private ObjectNode argsAt(String file, int line, int column, boolean followCalls) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectPath.resolve("src/main/java/com/flow/" + file).toString());
        args.put("line", line);
        args.put("column", column);
        if (followCalls) {
            args.put("followCalls", true);
        }
        return args;
    }

    // ========== Null facts ==========

    @Test
    @DisplayName("null value tracked through a call to a dereference sink in the callee")
    @SuppressWarnings("unchecked")
    void nullFlow_throughCallToDereference() {
        ToolResponse r = tool.execute(argsAt("NullFlow.java", 4, 19, true));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        assertEquals("entry", data.get("method"), "intra-method analysis still present");
        assertEquals(Boolean.TRUE, data.get("followCalls"));

        List<Map<String, Object>> flows = flows(r);
        assertEquals(1, flows.size(), () -> "flows: " + flows);

        Map<String, Object> flow = flows.get(0);
        assertEquals("null", flow.get("fact"));
        assertEquals("value", flow.get("sourceVariable"));
        assertEquals("com.flow.NullFlow#entry(boolean)", flow.get("sourceMethod"));
        assertEquals(5, flow.get("sourceLine"));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) flow.get("steps");
        assertEquals(1, steps.size(), () -> "steps: " + steps);
        assertEquals("argument", steps.get(0).get("kind"));
        assertEquals("com.flow.NullFlow#describe(String)", steps.get(0).get("callee"));
        assertEquals("text", steps.get(0).get("parameter"));
        assertEquals(0, steps.get(0).get("argIndex"));
        assertEquals(9, steps.get(0).get("line"));

        Map<String, Object> sink = (Map<String, Object>) flow.get("sink");
        assertEquals("dereference", sink.get("kind"));
        assertEquals("text.length()", sink.get("expression"));
        assertEquals("com.flow.NullFlow#describe(String)", sink.get("method"));
        assertEquals(13, sink.get("line"));
    }

    @Test
    @DisplayName("a method with no null or escaping facts yields no flows")
    void safeMethod_noFlows() {
        ToolResponse r = tool.execute(argsAt("NullFlow.java", 16, 16, true));
        assertTrue(r.isSuccess());
        assertEquals(List.of(), flows(r));
    }

    // ========== Taint facts ==========

    @Test
    @DisplayName("parameter taint propagates through concat alias and escapes into a non-project callee")
    @SuppressWarnings("unchecked")
    void taintFlow_aliasThenEscape() {
        ToolResponse r = tool.execute(argsAt("TaintFlow.java", 9, 17, true));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        List<Map<String, Object>> flows = flows(r);
        assertEquals(1, flows.size(), () -> "flows: " + flows);

        Map<String, Object> flow = flows.get(0);
        assertEquals("taint", flow.get("fact"));
        assertEquals("request", flow.get("sourceVariable"));
        assertEquals("com.flow.TaintFlow#handle(String)", flow.get("sourceMethod"));
        assertEquals(9, flow.get("sourceLine"));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) flow.get("steps");
        assertEquals(2, steps.size(), () -> "steps: " + steps);
        assertEquals("alias", steps.get(0).get("kind"));
        assertEquals("query", steps.get(0).get("variable"));
        assertEquals(10, steps.get(0).get("line"));
        assertEquals("argument", steps.get(1).get("kind"));
        assertEquals("com.flow.TaintFlow#run(String)", steps.get(1).get("callee"));
        assertEquals("sql", steps.get(1).get("parameter"));
        assertEquals(11, steps.get(1).get("line"));

        Map<String, Object> sink = (Map<String, Object>) flow.get("sink");
        assertEquals("escape", sink.get("kind"));
        assertEquals("java.util.List#add(E)", sink.get("callee"));
        assertEquals("log.add(sql)", sink.get("expression"));
        assertEquals("com.flow.TaintFlow#run(String)", sink.get("method"));
        assertEquals(15, sink.get("line"));
    }

    // ========== Bounding ==========

    @Test
    @DisplayName("maxCallDepth bounds the walk: depth 1 finds nothing, depth 2 reaches the sink")
    @SuppressWarnings("unchecked")
    void depthBound_honored() {
        ObjectNode shallow = argsAt("DeepNullFlow.java", 4, 19, true);
        shallow.put("maxCallDepth", 1);
        ToolResponse r1 = tool.execute(shallow);
        assertTrue(r1.isSuccess());
        assertEquals(List.of(), flows(r1), "sink sits two hops deep; depth 1 must not reach it");

        ObjectNode deep = argsAt("DeepNullFlow.java", 4, 19, true);
        deep.put("maxCallDepth", 2);
        ToolResponse r2 = tool.execute(deep);
        assertTrue(r2.isSuccess());

        List<Map<String, Object>> flows = flows(r2);
        assertEquals(1, flows.size(), () -> "flows: " + flows);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) flows.get(0).get("steps");
        assertEquals(2, steps.size());
        assertEquals("com.flow.DeepNullFlow#middle(String)", steps.get(0).get("callee"));
        assertEquals("com.flow.DeepNullFlow#bottom(String)", steps.get(1).get("callee"));
        Map<String, Object> sink = (Map<String, Object>) flows.get(0).get("sink");
        assertEquals("end.trim()", sink.get("expression"));
        assertEquals(14, sink.get("line"));
    }

    @Test
    @DisplayName("recursive call chains terminate with no flows")
    void recursion_terminates() {
        ToolResponse r = tool.execute(argsAt("RecursiveFlow.java", 4, 17, true));
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());
        assertEquals(List.of(), flows(r));
    }

    @Test
    @DisplayName("maxCallDepth below 1 is rejected as INVALID_PARAMETER")
    void invalidDepth_rejected() {
        ObjectNode args = argsAt("NullFlow.java", 4, 19, true);
        args.put("maxCallDepth", 0);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    // ========== Off by default ==========

    @Test
    @DisplayName("without followCalls the response keeps the intra-method shape exactly")
    void offByDefault_shapeUnchanged() {
        ToolResponse r = tool.execute(argsAt("NullFlow.java", 4, 19, false));
        assertTrue(r.isSuccess());

        Map<String, Object> data = getData(r);
        assertEquals("entry", data.get("method"));
        assertNull(data.get("followCalls"), "no followCalls marker in intra-method mode");
        assertNull(data.get("interproceduralFlows"), "no flows block in intra-method mode");
    }
}
