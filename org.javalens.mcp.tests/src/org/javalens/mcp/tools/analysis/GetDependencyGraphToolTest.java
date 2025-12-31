package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDependencyGraphToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDependencyGraphTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDependencyGraphTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("analyzes type dependencies")
    void analyzesTypeDependencies() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.service.UserService");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("type", data.get("scope"));
        assertEquals("com.example.service.UserService", data.get("root"));
        assertNotNull(data.get("nodes"));
        assertNotNull(data.get("edges"));
        assertNotNull(data.get("summary"));
    }

    @Test @DisplayName("analyzes package dependencies")
    void analyzesPackageDependencies() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "package");
        args.put("name", "com.example");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals("package", getData(r).get("scope"));
        assertEquals("com.example", getData(r).get("root"));
    }

    @Test @DisplayName("respects depth and includeExternal")
    void respectsOptions() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.Calculator");
        args.put("depth", 2);
        args.put("includeExternal", true);

        assertTrue(tool.execute(args).isSuccess());
    }

    @Test @DisplayName("requires scope and name")
    void requiresParameters() {
        ObjectNode noScope = objectMapper.createObjectNode();
        noScope.put("name", "com.example.Calculator");
        assertFalse(tool.execute(noScope).isSuccess());

        ObjectNode noName = objectMapper.createObjectNode();
        noName.put("scope", "type");
        assertFalse(tool.execute(noName).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badScope = objectMapper.createObjectNode();
        badScope.put("scope", "invalid");
        badScope.put("name", "com.example.Calculator");
        assertFalse(tool.execute(badScope).isSuccess());

        ObjectNode unknownType = objectMapper.createObjectNode();
        unknownType.put("scope", "type");
        unknownType.put("name", "com.nonexistent.Type");
        assertFalse(tool.execute(unknownType).isSuccess());
    }
}
