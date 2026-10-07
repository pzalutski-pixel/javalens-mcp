package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.NestedTypes.FLOWS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * analyze_data_flow names a field's type as a signature writes it, a captured wildcard by the
 * wildcard it captures.
 *
 * <p>{@code peek(Box<? extends CharSequence> box)} (Flows.java, nested-types, line 16) returns
 * {@code box.value.length()} on line 17: it reads its parameter box once and, through box, the
 * field {@code value} of the generic member class {@code Box<T>}, whose type as box reads it is
 * a capture of {@code ? extends CharSequence}. The parameter's type is reported as written.
 */
class CaptureDisplayTypeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;
    private String flows;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(NestedTypes.FIXTURE);
        envelope = new EnvelopeHarness(service);
        flows = helper.getFixturePath(NestedTypes.FIXTURE).resolve(FLOWS).toString();
    }

    @Test
    @DisplayName("analyze_data_flow of peek reports the field value, read through a Box<? extends CharSequence>, as a ? extends CharSequence")
    void capturedFieldType() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", flows);
        args.put("line", 17);
        args.put("column", 8);
        Map<String, Object> data = map(
            "method", "peek",
            "parameterCount", 1,
            "variables", List.of(
                map("name", "box", "type", "Box<? extends CharSequence>", "kind", "parameter", "declared", true,
                    "read", true, "written", false, "readCount", 1, "writeCount", 0),
                map("name", "value", "type", "? extends CharSequence", "kind", "field", "declared", false,
                    "read", true, "written", false, "readCount", 1, "writeCount", 0)),
            "returnStatements", 1);
        Map<String, Object> meta = map("suggestedNextTools", List.of(
            "analyze_control_flow for branching and loop analysis",
            "extract_method to extract code with understood data dependencies"));
        assertPayload(mapper, new AnalyzeDataFlowTool(() -> service), envelope, args, success(data, meta));
    }
}
