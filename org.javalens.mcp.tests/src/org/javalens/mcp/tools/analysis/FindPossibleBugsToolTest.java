package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindPossibleBugsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindPossibleBugsTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindPossibleBugsTool(() -> service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds bugs comprehensively")
    void findsBugsComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("issues"));
        assertNotNull(data.get("totalIssues"));
        assertNotNull(data.get("highCount"));
        assertNotNull(data.get("mediumCount"));
        assertNotNull(data.get("lowCount"));
    }

    @Test @DisplayName("supports severity filter")
    void supportsSeverityFilter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "high");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) getData(r).get("issues");
        for (Map<String, Object> issue : issues) {
            assertEquals("high", issue.get("severity"));
        }
    }

    @Test @DisplayName("returns no issues for clean file")
    void returnsNoIssuesForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals(0, getData(r).get("totalIssues"));
    }

    @Test @DisplayName("analyzes whole project")
    void analyzesWholeProject() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        assertNotNull(getData(r).get("totalIssues"));
    }

    // ========== Semantic-grade tests (exact bug patterns in BugPatterns.java) ==========

    @Test
    @DisplayName("BugPatterns.java: detects empty catch, ==-on-string, sync-on-string, unclosed resource")
    void bugPatterns_detectsAllDocumentedIssueCategories() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) getData(r).get("issues");

        // Collect rule/category labels (the tool reports each issue with some kind of
        // category identifier). We don't pin the exact key name; collect across the most
        // common label fields.
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (Map<String, Object> issue : issues) {
            for (String field : List.of("rule", "category", "type", "name", "message", "description")) {
                Object v = issue.get(field);
                if (v != null) labels.add(v.toString().toLowerCase());
            }
        }

        // The fixture has: emptyExceptionHandler (empty catch), stringCompareWithOperator
        // (== on String), syncOnStringVariable (sync on String field), unclosedResource
        // (FileInputStream not closed), multipleBugs (combination). At minimum the tool
        // must surface each category.
        assertTrue(labels.stream().anyMatch(l -> l.contains("empty") && l.contains("catch")),
            "Expected an issue mentioning empty catch; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("string") && (l.contains("==") || l.contains("equals") || l.contains("compar"))),
            "Expected an issue mentioning String == comparison; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("sync") && l.contains("string")),
            "Expected an issue mentioning synchronization on String; got labels: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.contains("resource") || l.contains("close")),
            "Expected an issue mentioning resource leak / unclosed resource; got labels: " + labels);
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> issuesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("issues");
    }

    @Test
    @DisplayName("Each issue carries severity in {high, medium, low}")
    void issueSeverity_valid() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> validSeverity = java.util.Set.of("high", "medium", "low");
        for (Map<String, Object> i : issuesOf(r)) {
            Object sev = i.get("severity");
            assertNotNull(sev, "severity missing on issue: " + i);
            assertTrue(validSeverity.contains(sev.toString()),
                "severity must be one of {high, medium, low}; got: " + sev);
        }
    }

    @Test
    @DisplayName("highCount + mediumCount + lowCount == totalIssues")
    void counts_consistent() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        long high = ((Number) data.get("highCount")).longValue();
        long med = ((Number) data.get("mediumCount")).longValue();
        long low = ((Number) data.get("lowCount")).longValue();
        long total = ((Number) data.get("totalIssues")).longValue();
        assertEquals(total, high + med + low,
            "high+medium+low must equal totalIssues; got high=" + high + " med=" + med
                + " low=" + low + " total=" + total);
    }

    @Test
    @DisplayName("severity='medium' filter returns only medium-severity issues")
    void severityMedium_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "medium");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> i : issuesOf(r)) {
            assertEquals("medium", i.get("severity"),
                "All issues must be medium when severity=medium; got: " + i);
        }
    }

    @Test
    @DisplayName("severity='low' filter returns only low-severity issues")
    void severityLow_filter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/BugPatterns.java");
        args.put("severity", "low");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> i : issuesOf(r)) {
            assertEquals("low", i.get("severity"),
                "All issues must be low when severity=low; got: " + i);
        }
    }

    @Test
    @DisplayName("Calculator.java (clean) returns zero issues + zero highCount")
    void cleanFile_zeroCounts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(0, ((Number) data.get("totalIssues")).intValue());
        assertEquals(0, ((Number) data.get("highCount")).intValue());
        assertEquals(0, ((Number) data.get("mediumCount")).intValue());
        assertEquals(0, ((Number) data.get("lowCount")).intValue());
        assertTrue(issuesOf(r).isEmpty());
    }
}
