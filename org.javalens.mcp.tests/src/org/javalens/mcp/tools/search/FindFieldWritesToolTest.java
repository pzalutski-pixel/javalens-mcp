package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindFieldWritesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindFieldWritesTool tool;
    private ObjectMapper objectMapper;
    private String refactoringTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindFieldWritesTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        refactoringTargetPath = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds field writes with complete response")
    void findsFieldWritesComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);  // userName field
        args.put("column", 19);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("userName", data.get("field"));
        assertNotNull(data.get("declaringType"));
        assertNotNull(data.get("totalWriteLocations"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> writes = (List<Map<String, Object>>) data.get("writeLocations");
        assertNotNull(writes);
        if (!writes.isEmpty()) {
            Map<String, Object> write = writes.get(0);
            assertNotNull(write.get("line"));
            assertEquals("WRITE", write.get("accessType"));
        }
    }

    @Test @DisplayName("supports maxResults parameter")
    void supportsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 13);
        args.put("column", 19);
        args.put("maxResults", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> writes = (List<?>) getData(r).get("writeLocations");
        assertTrue(writes.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 13);
        noFile.put("column", 19);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", refactoringTargetPath);
        noLine.put("column", 19);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-field position gracefully")
    void handlesNonFieldPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetPath);
        args.put("line", 20);  // Method, not field
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
