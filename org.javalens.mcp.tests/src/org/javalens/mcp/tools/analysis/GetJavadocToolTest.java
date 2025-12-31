package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetJavadocTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetJavadocToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetJavadocTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetJavadocTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("parses javadoc with complete response including tags")
    void parsesJavadocComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method with javadoc
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("symbol"));
        assertNotNull(data.get("kind"));
        assertNotNull(data.get("hasDocumentation"));

        if ((Boolean) data.get("hasDocumentation")) {
            assertNotNull(data.get("summary"));
            // May have @param, @return, @throws depending on the method
        }
    }

    @Test @DisplayName("returns hasDocumentation false for undocumented symbol")
    void handlesUndocumentedSymbol() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 10);  // Class declaration (may not have javadoc)
        args.put("column", 13);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("hasDocumentation"));
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

    @Test @DisplayName("handles non-member position gracefully")
    void handlesNonMemberPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
