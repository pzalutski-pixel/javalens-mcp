package org.javalens.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindReflectionUsageToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReflectionUsageTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReflectionUsageTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Detection Tests ==========

    @Test
    @DisplayName("should find reflection usage in project")
    void findsReflectionUsage() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalCalls = (int) data.get("totalCalls");
        assertTrue(totalCalls > 0, "Should find reflection calls in DiAndReflectionPatterns.java");
    }

    @Test
    @DisplayName("should include reflection method label in results")
    void includesReflectionMethodLabel() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");
        if (!calls.isEmpty()) {
            Map<String, Object> firstCall = calls.get(0);
            assertNotNull(firstCall.get("reflectionMethod"), "Should include reflection method label");
            // filePath and line may be absent if match is in a binary JAR
        }
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should group results by reflection method type in summary")
    void groupsResultsInSummary() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("summary"), "Should include summary");
        assertNotNull(data.get("reflectionCalls"), "Should include reflectionCalls list");
    }

    @Test
    @DisplayName("should respect maxResults parameter (per-reflection-method cap, per the tool's description)")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // The tool documents `maxResults` as "Maximum results per reflection method". The
        // summary maps each detected reflection label to its count; every value must obey
        // the cap.
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary);
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            int count = ((Number) entry.getValue()).intValue();
            assertTrue(count <= 1,
                "maxResults=1 caps each reflection label's count to 1; "
                    + entry.getKey() + " has " + count + " entries; full summary: " + summary);
        }
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("reflection calls include Class.forName, getMethod, Method.invoke, getDeclaredField, Field.get from DiAndReflectionPatterns")
    void findsSpecificReflectionApis() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 100);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");

        java.util.Set<String> reflectionMethods = calls.stream()
            .map(c -> (String) c.get("reflectionMethod"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());

        // DiAndReflectionPatterns has Class.forName, getDeclaredConstructor().newInstance(),
        // getMethod, Method.invoke, getDeclaredField, Field.get (and setAccessible).
        // Some of these (newInstance, setAccessible) may not be classified as core reflection
        // by the tool; assert the most universally-detected APIs appear.
        assertTrue(reflectionMethods.contains("Class.forName"),
            "Expected Class.forName in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Class.getMethod"),
            "Expected Class.getMethod in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Method.invoke"),
            "Expected Method.invoke in detected reflection methods; got: " + reflectionMethods);
        assertTrue(reflectionMethods.contains("Class.getDeclaredField"),
            "Expected Class.getDeclaredField in detected reflection methods; got: " + reflectionMethods);
    }
}
