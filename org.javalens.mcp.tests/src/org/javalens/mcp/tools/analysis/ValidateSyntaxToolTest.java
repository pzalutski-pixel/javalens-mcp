package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ValidateSyntaxTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidateSyntaxToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private ValidateSyntaxTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ValidateSyntaxTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("validates file successfully")
    void validatesFileSuccessfully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("valid"));
        @SuppressWarnings("unchecked")
        List<?> errors = (List<?>) data.get("errors");
        assertTrue(errors == null || errors.isEmpty());
    }

    @Test @DisplayName("validates inline content")
    void validatesInlineContent() {
        ObjectNode valid = objectMapper.createObjectNode();
        valid.put("content", "public class Test { public void test() {} }");
        assertEquals(true, getData(tool.execute(valid)).get("valid"));

        ObjectNode withFileName = objectMapper.createObjectNode();
        withFileName.put("content", "public class MyClass {}");
        withFileName.put("fileName", "MyClass.java");
        assertEquals(true, getData(tool.execute(withFileName)).get("valid"));
    }

    @Test @DisplayName("detects syntax errors")
    void detectsSyntaxErrors() {
        ObjectNode missingBrace = objectMapper.createObjectNode();
        missingBrace.put("content", "public class Test { public void test() {");
        assertEquals(false, getData(tool.execute(missingBrace)).get("valid"));

        ObjectNode missingValue = objectMapper.createObjectNode();
        missingValue.put("content", "public class Test { int x = ; }");
        Map<String, Object> data = getData(tool.execute(missingValue));
        assertEquals(false, data.get("valid"));
        @SuppressWarnings("unchecked")
        List<?> errors = (List<?>) data.get("errors");
        assertFalse(errors.isEmpty());
    }

    @Test @DisplayName("requires filePath or content")
    void requiresFilePathOrContent() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid file path")
    void handlesInvalidFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(args).isSuccess());
    }
}
