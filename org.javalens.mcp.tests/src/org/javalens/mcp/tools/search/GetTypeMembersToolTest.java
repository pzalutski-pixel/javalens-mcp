package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetTypeMembersToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetTypeMembersTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetTypeMembersTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("gets type members with complete response")
    void getsTypeMembersComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.example.Calculator");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify type info
        @SuppressWarnings("unchecked")
        Map<String, Object> typeInfo = (Map<String, Object>) data.get("type");
        assertEquals("Calculator", typeInfo.get("name"));
        assertEquals("Class", typeInfo.get("kind"));

        // Verify members
        assertNotNull(data.get("methods"));
        assertNotNull(data.get("fields"));
        assertNotNull(data.get("totalMembers"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) data.get("methods");
        assertFalse(methods.isEmpty());
        assertNotNull(methods.get(0).get("name"));
        assertNotNull(methods.get(0).get("signature"));
    }

    @Test @DisplayName("supports optional parameters")
    void supportsOptionalParameters() {
        // Test includeInherited
        ObjectNode withInherited = objectMapper.createObjectNode();
        withInherited.put("typeName", "com.example.Calculator");
        withInherited.put("includeInherited", true);
        assertTrue(tool.execute(withInherited).isSuccess());

        // Test memberKind filter
        ObjectNode methodsOnly = objectMapper.createObjectNode();
        methodsOnly.put("typeName", "com.example.Calculator");
        methodsOnly.put("memberKind", "method");
        ToolResponse r = tool.execute(methodsOnly);
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("methods"));
        assertNull(getData(r).get("fields"));
    }

    @Test @DisplayName("finds type by simple name")
    void findsTypeBySimpleName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
    }

    @Test @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles unknown type gracefully")
    void handlesUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "com.nonexistent.Type");

        assertFalse(tool.execute(args).isSuccess());
    }
}
