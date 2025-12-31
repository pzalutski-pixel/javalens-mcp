package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDiagnosticsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetDiagnosticsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiagnosticsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("gets diagnostics for clean file")
    void getsDiagnosticsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, data.get("totalDiagnostics"));
        assertEquals(0, data.get("errorCount"));
        assertEquals(0, data.get("warningCount"));
        assertEquals(1, data.get("filesChecked"));
    }

    @Test @DisplayName("supports severity filter and maxResults")
    void supportsSeverityFilterAndMaxResults() {
        ObjectNode withSeverity = objectMapper.createObjectNode();
        withSeverity.put("filePath", calculatorPath);
        withSeverity.put("severity", "error");
        assertTrue(tool.execute(withSeverity).isSuccess());

        ObjectNode withMax = objectMapper.createObjectNode();
        withMax.put("maxResults", 1);
        @SuppressWarnings("unchecked")
        List<?> diags = (List<?>) getData(tool.execute(withMax)).get("diagnostics");
        assertTrue(diags.size() <= 1);
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        assertTrue((Integer) getData(r).get("filesChecked") > 0);
    }

    @Test @DisplayName("handles invalid file path")
    void handlesInvalidFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(args).isSuccess());
    }
}
