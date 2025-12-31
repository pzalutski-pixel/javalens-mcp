package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCatchBlocksToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCatchBlocksTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCatchBlocksTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private List<?> getCatchBlocks(Map<String, Object> d) { return (List<?>) d.get("catchBlocks"); }

    @Test @DisplayName("finds catch blocks")
    void findsCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("exceptionType", "java.io.IOException");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertFalse(getCatchBlocks(getData(r)).isEmpty());
        assertNotNull(getData(r).get("totalCatchBlocks"));
    }

    @Test @DisplayName("respects maxResults")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("exceptionType", "java.io.IOException");
        args.put("maxResults", 1);
        assertTrue(getCatchBlocks(getData(tool.execute(args))).size() <= 1);
    }

    @Test @DisplayName("requires exceptionType")
    void requiresExceptionType() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown exception type")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("exceptionType", "com.nonexistent.X");
        assertFalse(tool.execute(args).isSuccess());
    }
}
