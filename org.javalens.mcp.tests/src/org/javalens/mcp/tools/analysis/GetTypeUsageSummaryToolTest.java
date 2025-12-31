package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetTypeUsageSummaryToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetTypeUsageSummaryTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeUsageSummaryTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("summarizes type usage comprehensively")
    void summarizesTypeUsageComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("com.example.Calculator", data.get("typeName"));
        assertEquals("Class", data.get("kind"));
        assertNotNull(data.get("totalUsages"));

        @SuppressWarnings("unchecked")
        Map<String, Object> usages = (Map<String, Object>) data.get("usages");
        assertNotNull(usages.get("instantiations"));
        assertNotNull(usages.get("casts"));
        assertNotNull(usages.get("instanceofChecks"));
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        assertEquals("com.example.Calculator", getData(tool.execute(args)).get("typeName"));
    }

    @Test @DisplayName("respects maxPerCategory")
    void respectsMaxPerCategory() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxPerCategory", 1);

        assertTrue(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires typeName")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("typeName", "com.nonexistent.Type");
        assertFalse(tool.execute(unknown).isSuccess());
    }
}
