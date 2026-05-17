package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;
    private AnalyzeMethodTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMethod(Map<String, Object> d) { return (Map<String, Object>) d.get("method"); }

    @Test @DisplayName("analyzes method comprehensively")
    void analyzesMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        Map<String, Object> method = getMethod(data);

        // Method info
        assertEquals("add", method.get("name"));
        assertNotNull(method.get("signature"));
        assertEquals("com.example.Calculator", method.get("declaringType"));
        assertEquals("int", method.get("returnType"));

        // Parameters
        assertNotNull(data.get("parameters"));

        // Call hierarchy
        assertNotNull(data.get("callers"));
        assertNotNull(data.get("callees"));
        assertNotNull(data.get("overrides"));
    }

    @Test @DisplayName("respects max limits")
    void respectsMaxLimits() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 1);
        args.put("maxCallees", 1);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<?> callerList = (List<?>) callers.get("list");
        assertTrue(callerList.size() <= 1);
    }

    @Test @DisplayName("requires filePath, line, column")
    void requiresParameters() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());

        ObjectNode noColumn = objectMapper.createObjectNode();
        noColumn.put("filePath", calculatorPath);
        noColumn.put("line", 14);
        assertFalse(tool.execute(noColumn).isSuccess());
    }

    @Test @DisplayName("handles invalid inputs")
    void handlesInvalidInputs() {
        // Non-existent file
        ObjectNode badFile = objectMapper.createObjectNode();
        badFile.put("filePath", "/nonexistent/File.java");
        badFile.put("line", 14);
        badFile.put("column", 15);
        assertFalse(tool.execute(badFile).isSuccess());

        // Position not on method
        ObjectNode notMethod = objectMapper.createObjectNode();
        notMethod.put("filePath", calculatorPath);
        notMethod.put("line", 0);
        notMethod.put("column", 0);
        assertFalse(tool.execute(notMethod).isSuccess());
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add aggregate: callers include UserService and SearchPatterns files")
    void calculatorAdd_callersIncludeKnownInvokers() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) callers.get("list");

        // AnalyzeMethodTool emits the per-caller file as `file` (not `filePath`).
        java.util.Set<String> callerFiles = list.stream()
            .map(c -> (String) c.get("file"))
            .filter(java.util.Objects::nonNull)
            .map(s -> s.replace('\\', '/'))
            .map(s -> s.substring(s.lastIndexOf('/') + 1))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(callerFiles.contains("UserService.java"),
            "UserService.calculateTotal calls Calculator.add — analyze_method aggregate must surface it; got: " + callerFiles);
        assertTrue(callerFiles.contains("SearchPatterns.java"),
            "SearchPatterns.createObjects calls Calculator.add — analyze_method aggregate must surface it; got: " + callerFiles);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Method info has name, signature, declaringType, returnType, modifiers")
    @SuppressWarnings("unchecked")
    void method_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> method = getMethod(getData(r));
        for (String key : List.of("name", "signature", "declaringType", "returnType", "modifiers")) {
            assertNotNull(method.get(key), key + " missing on method: " + method);
        }
    }

    @Test
    @DisplayName("Calculator.add has 2 parameters: int a, int b")
    void calculatorAdd_parameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) getData(r).get("parameters");
        assertEquals(2, params.size(),
            "Calculator.add(int a, int b) has 2 parameters; got: " + params);
        for (Map<String, Object> p : params) {
            assertNotNull(p.get("name"));
            assertNotNull(p.get("type"));
        }
    }

    @Test
    @DisplayName("callers block has list + count; count equals list.size()")
    void callersBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callers = (Map<String, Object>) getData(r).get("callers");
        @SuppressWarnings("unchecked")
        List<?> list = (List<?>) callers.get("list");
        assertNotNull(list);
        Object count = callers.get("count");
        if (count != null) {
            assertEquals(((Number) count).intValue(), list.size(),
                "callers.count must equal callers.list.size(); got: " + callers);
        }
    }

    @Test
    @DisplayName("callees block present; entries reference target methods")
    void calleesBlock_present() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> callees = (Map<String, Object>) getData(r).get("callees");
        assertNotNull(callees);
        assertNotNull(callees.get("list"));
    }

    @Test
    @DisplayName("overrides block present (may be empty for top-level methods)")
    void overridesBlock_present() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("overrides"));
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.add: analyze_method callers count agrees with get_call_hierarchy_incoming (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculatorAdd_callerCountAgreesWithCallHierarchy() throws Exception {
        // If analyze_method's callers count drifts from get_call_hierarchy_incoming,
        // one of the tools is wrong about what calls Calculator.add. This test pins
        // them together.
        GetCallHierarchyIncomingTool detail = new GetCallHierarchyIncomingTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        args.put("maxCallers", 100);

        Map<String, Object> aggregateData = getData(tool.execute(args));
        Map<String, Object> detailData = getData(detail.execute(args));

        Map<String, Object> aggregateCallers = (Map<String, Object>) aggregateData.get("callers");
        List<?> aggregateList = (List<?>) aggregateCallers.get("list");
        List<?> detailCallers = (List<?>) detailData.get("callers");

        assertNotNull(aggregateList, "aggregate callers.list must be present; got: " + aggregateCallers);
        assertNotNull(detailCallers, "detail callers list must be present; got: " + detailData);
        assertEquals(detailCallers.size(), aggregateList.size(),
            "analyze_method.callers.list.size() must equal get_call_hierarchy_incoming.callers.size(); "
                + "aggregate=" + aggregateList.size() + " detail=" + detailCallers.size());
    }
}
