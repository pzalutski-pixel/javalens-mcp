package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeTypeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeTypeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeTypeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("analyzes type comprehensively")
    void analyzesTypeComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Type info
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("type");
        assertEquals("Calculator", type.get("name"));
        assertEquals("com.example.Calculator", type.get("qualifiedName"));
        assertEquals("Class", type.get("kind"));
        assertNotNull(type.get("file"));

        // Members
        @SuppressWarnings("unchecked")
        Map<String, Object> members = (Map<String, Object>) data.get("members");
        assertNotNull(members.get("methods"));
        assertNotNull(members.get("fields"));
        assertTrue((Integer) members.get("methodCount") > 0);

        // Hierarchy and usages by default
        assertNotNull(data.get("hierarchy"));
        assertNotNull(data.get("usages"));
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) getData(r).get("type");
        assertEquals("Calculator", type.get("name"));
    }

    @Test @DisplayName("controls usages output")
    void controlsUsagesOutput() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");
        args.put("includeUsages", false);

        assertNull(getData(tool.execute(args)).get("usages"));
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

        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("typeName", "");
        assertFalse(tool.execute(empty).isSuccess());
    }
}
