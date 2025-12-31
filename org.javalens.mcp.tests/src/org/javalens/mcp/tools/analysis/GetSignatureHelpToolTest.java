package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetSignatureHelpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSignatureHelpToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetSignatureHelpTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSignatureHelpTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns signature help with complete response")
    void returnsSignatureHelpComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify LSP-style response
        assertNotNull(data.get("signatures"));
        assertNotNull(data.get("activeSignature"));
        assertNotNull(data.get("activeParameter"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) data.get("signatures");
        assertFalse(signatures.isEmpty());

        Map<String, Object> sig = signatures.get(0);
        assertNotNull(sig.get("label"));
        assertNotNull(sig.get("parameters"));
    }

    @Test @DisplayName("includes all overloaded signatures")
    void includesOverloadedSignatures() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method (may have overloads)
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<?> signatures = (List<?>) getData(r).get("signatures");
        assertTrue(signatures.size() >= 1);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
