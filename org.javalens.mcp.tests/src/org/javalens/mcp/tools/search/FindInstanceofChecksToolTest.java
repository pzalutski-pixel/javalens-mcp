package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindInstanceofChecksToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindInstanceofChecksTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindInstanceofChecksTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getChecks(Map<String, Object> d) { return (List<?>) d.get("instanceofChecks"); }

    @Test @DisplayName("finds instanceof checks")
    void findsInstanceofChecks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getChecks(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalChecks"));
        assertEquals("com.example.Calculator", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        assertTrue(getChecks(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("instanceof checks for Calculator: 2 in fixtures (performCasts + checkTypes)")
    void calculator_findsExactInstanceofCount() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // SearchPatterns has TWO `instanceof Calculator` checks: one in performCasts (line 79)
        // and one in checkTypes (line 100). No other instanceof Calculator anywhere.
        assertEquals(2, ((Number) getData(r).get("totalChecks")).intValue(),
            "Expected exactly 2 instanceof Calculator checks; got: "
                + getData(r).get("totalChecks") + " (" + getChecks(getData(r)) + ")");
    }
}
