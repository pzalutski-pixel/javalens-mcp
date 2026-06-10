package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that get_call_hierarchy_incoming, which resolves callers through the same
 * SearchService, surfaces a not-first super(...) delegation (JEP 513 flexible
 * constructor body). Incoming callers of Base(int) must include both the
 * first-statement delegation (Traditional) and the not-first one (Derived) —
 * the latter is exactly the call JDT's indexed search misses.
 */
class GetCallHierarchyIncomingFlexibleCtorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetCallHierarchyIncomingTool tool;
    private String basePath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("java25-maven");
        tool = new GetCallHierarchyIncomingTool(() -> service);
        basePath = helper.getFixturePath("java25-maven")
            .resolve("src/main/java/com/example/ctor/Base.java").toString();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("incoming callers of Base(int) include the not-first super(v) delegation")
    @SuppressWarnings("unchecked")
    void incomingCallers_includeNotFirstDelegation() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", basePath);
        args.put("line", 4);    // 0-based; file line 5 = "public Base(int x) {"
        args.put("column", 11); // the constructor name "Base"

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "expected success; got: " + r.getData());

        List<Map<String, Object>> callers = (List<Map<String, Object>>) getData(r).get("callers");
        String dump = String.valueOf(callers);
        assertTrue(dump.contains("Traditional"),
            "the first-statement super(v) caller (Traditional) must appear; got: " + dump);
        assertTrue(dump.contains("Derived"),
            "the not-first super(v) caller (Derived, flexible body) must appear; got: " + dump);
    }
}
