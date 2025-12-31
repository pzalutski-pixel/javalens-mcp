package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCircularDependenciesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCircularDependenciesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCircularDependenciesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("detects cycles comprehensively")
    void detectsCyclesComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("hasCycles"));
        assertNotNull(data.get("cycleCount"));
        assertNotNull(data.get("cycles"));
        assertNotNull(data.get("affectedPackages"));
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        ObjectNode withFilter = objectMapper.createObjectNode();
        withFilter.put("packageFilter", "com.example");
        assertTrue(tool.execute(withFilter).isSuccess());

        ObjectNode withMaxLength = objectMapper.createObjectNode();
        withMaxLength.put("maxCycleLength", 5);
        assertTrue(tool.execute(withMaxLength).isSuccess());
    }

    @Test @DisplayName("handles non-existent package")
    void handlesNonExistentPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.nonexistent");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals(false, getData(r).get("hasCycles"));
        assertEquals(0, getData(r).get("cycleCount"));
    }
}
