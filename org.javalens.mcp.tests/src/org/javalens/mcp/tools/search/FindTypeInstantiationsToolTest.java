package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindTypeInstantiationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindTypeInstantiationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindTypeInstantiationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getInstantiations(Map<String, Object> d) { return (List<?>) d.get("instantiations"); }

    @Test @DisplayName("finds instantiations of project type")
    void findsInstantiationsOfProjectType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getInstantiations(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalInstantiations"));
        assertEquals("com.example.Calculator", getData(r).get("typeName"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("maxResults", 1);
        assertTrue(getInstantiations(getData(tool.execute(args))).size() <= 1);
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
}
