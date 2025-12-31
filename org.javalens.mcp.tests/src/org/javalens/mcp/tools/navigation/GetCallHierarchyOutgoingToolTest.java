package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetCallHierarchyOutgoingToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetCallHierarchyOutgoingTool tool;
    private ObjectMapper objectMapper;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetCallHierarchyOutgoingTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds callees with complete response")
    void findCalleesComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 20);  // processInput method that calls other methods
        args.put("column", 18);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("method"));
        assertNotNull(data.get("declaringClass"));
        assertNotNull(data.get("signature"));
        assertNotNull(data.get("totalCallees"));
        assertNotNull(data.get("callees"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callees = (List<Map<String, Object>>) data.get("callees");
        if (!callees.isEmpty()) {
            Map<String, Object> callee = callees.get(0);
            assertNotNull(callee.get("method"));
            assertNotNull(callee.get("declaringClass"));
            assertNotNull(callee.get("callType"));
        }
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 20);
        noFile.put("column", 18);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", refactoringTargetPath);
        noLine.put("column", 18);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);  // Field declaration
        args.put("column", 19);

        assertFalse(tool.execute(args).isSuccess());
    }
}
