package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetComplexityMetricsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetComplexityMetricsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetComplexityMetricsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("calculates metrics comprehensively")
    void calculatesMetricsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File metrics
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        assertNotNull(file.get("path"));
        assertTrue((Integer) file.get("physicalLOC") > 0);

        // Summary
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary.get("totalCyclomaticComplexity"));
        assertNotNull(summary.get("totalCognitiveComplexity"));
        assertNotNull(summary.get("methodCount"));

        // Risk assessment
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("riskAssessment");
        assertNotNull(risk.get("highRiskMethods"));
        assertNotNull(risk.get("lowRiskMethods"));

        // Method details included by default
        assertNotNull(data.get("methods"));
    }

    @Test @DisplayName("respects includeDetails option")
    void respectsIncludeDetailsOption() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeDetails", false);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertNull(getData(r).get("methods"));
    }

    @Test @DisplayName("requires filePath")
    void requiresFilePath() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        assertFalse(tool.execute(badPath).isSuccess());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        assertFalse(tool.execute(emptyPath).isSuccess());
    }
}
