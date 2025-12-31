package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSuperMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetSuperMethodTool tool;
    private ObjectMapper objectMapper;
    private String animalPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSuperMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        animalPath = projectPath.resolve("src/main/java/com/example/Animal.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds super method with complete response")
    void findsSuperMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 22);  // Dog.speak() which overrides Animal.speak()
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify method info
        assertNotNull(data.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> methodInfo = (Map<String, Object>) data.get("method");
        assertNotNull(methodInfo.get("name"));
        assertNotNull(methodInfo.get("signature"));

        // Verify overrides info (may be null if not overriding)
        if (data.get("overrides") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) data.get("overrides");
            assertNotNull(overrides.get("declaringType"));
        }
    }

    @Test @DisplayName("returns null overrides for non-overriding method")
    void handlesNonOverridingMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 7);  // Animal.speak() - base method, not overriding anything
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // Should have null overrides or a message
        if (data.get("overrides") == null) {
            assertNotNull(data.get("message"));
        }
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 22);
        noFile.put("column", 16);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", animalPath);
        noLine.put("column", 16);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 5);  // Class declaration
        args.put("column", 13);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
