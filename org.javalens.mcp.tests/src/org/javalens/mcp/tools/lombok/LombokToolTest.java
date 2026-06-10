package org.javalens.mcp.tools.lombok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Lombok support through the MCP tools the way the shipped server runs
 * (the build attaches the Lombok agent to the test JVM). Validates the plan's
 * acceptance criteria at the tool layer: generated members are reported, code
 * using them is diagnostic-clean, a generated accessor resolves references, and
 * a non-Lombok type is not augmented.
 */
class LombokToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetTypeMembersTool typeMembers;
    private GetDiagnosticsTool diagnostics;
    private FindReferencesTool references;
    private String consumerPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("lombok-maven");
        typeMembers = new GetTypeMembersTool(() -> service);
        diagnostics = new GetDiagnosticsTool(() -> service);
        references = new FindReferencesTool(() -> service);
        consumerPath = helper.getFixturePath("lombok-maven")
            .resolve("src/main/java/com/example/LombokConsumer.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<String> methodNames(ToolResponse r) {
        List<Map<String, Object>> methods = (List<Map<String, Object>>) getData(r).get("methods");
        return methods.stream().map(m -> (String) m.get("name")).toList();
    }

    @Test
    @DisplayName("get_type_members reports Lombok-generated accessors on a @Data type")
    void getTypeMembers_includesGeneratedAccessors() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.LombokBean");

        ToolResponse r = typeMembers.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        List<String> names = methodNames(r);
        assertTrue(names.containsAll(List.of("getName", "getAge", "setName", "setAge")),
            "generated accessors must be reported; got: " + names);
    }

    @Test
    @DisplayName("get_diagnostics on code calling generated accessors is error-free")
    void getDiagnostics_consumerHasNoFalseErrors() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", consumerPath);
        args.put("severity", "error");

        ToolResponse r = diagnostics.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());
        assertEquals(0, ((Number) getData(r).get("errorCount")).intValue(),
            "calls to getName()/getAge() must resolve; got: " + getData(r).get("diagnostics"));
    }

    @Test
    @DisplayName("find_references resolves a Lombok-generated getter usage")
    @SuppressWarnings("unchecked")
    void findReferences_resolvesGeneratedGetter() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", consumerPath);
        args.put("line", 5);     // 0-based; file line 6 = the return with bean.getName()
        args.put("column", 31);  // the getName identifier in bean.getName()

        ToolResponse r = references.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());
        assertEquals("getName", getData(r).get("symbol"),
            "position must resolve to the generated getName; got: " + getData(r));
        List<Map<String, Object>> locations = (List<Map<String, Object>>) getData(r).get("locations");
        assertFalse(locations.isEmpty(),
            "the getName() call site must be found as a reference; got: " + getData(r));
    }

    @Test
    @DisplayName("isolation: a non-@Data type is not augmented with accessors")
    void nonLombokType_notAugmented() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.LombokConsumer");

        ToolResponse r = typeMembers.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        List<String> names = methodNames(r);
        assertTrue(names.contains("greet"), "the real method must be present; got: " + names);
        assertFalse(names.stream().anyMatch(n -> n.startsWith("get") || n.startsWith("set")),
            "LombokConsumer is not @Data; no accessors should be fabricated; got: " + names);
    }
}
