package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindLargeClassesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindLargeClassesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindLargeClassesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindLargeClassesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Threshold Detection Tests ==========

    @Test
    @DisplayName("should find classes exceeding low method threshold")
    void findsClassesExceedingMethodThreshold() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 5);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertFalse(largeClasses.isEmpty(), "Should find classes with more than 5 methods");

        // Verify violation details are present
        @SuppressWarnings("unchecked")
        Map<String, Object> firstClass = (Map<String, Object>) largeClasses.get(0);
        assertNotNull(firstClass.get("file"), "Should include file path");
        assertNotNull(firstClass.get("typeName"), "Should include type name");
        assertNotNull(firstClass.get("methodCount"), "Should include method count");
        assertNotNull(firstClass.get("fieldCount"), "Should include field count");
        assertNotNull(firstClass.get("lineCount"), "Should include line count");
        assertNotNull(firstClass.get("violations"), "Should include violations list");
    }

    @Test
    @DisplayName("should find classes exceeding field threshold")
    void findsClassesExceedingFieldThreshold() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxFields", 2);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertFalse(largeClasses.isEmpty(), "Should find classes with more than 2 fields");
    }

    @Test
    @DisplayName("should return empty results with very high thresholds")
    void returnsEmptyWithHighThresholds() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 1000);
        args.put("maxFields", 1000);
        args.put("maxLines", 100000);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertTrue(largeClasses.isEmpty(), "No classes should exceed very high thresholds");
        assertTrue((int) data.get("totalClassesScanned") > 0, "Should have scanned classes");
    }

    // ========== Default Threshold Tests ==========

    @Test
    @DisplayName("should use default thresholds when not specified")
    void usesDefaultThresholds() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) data.get("thresholds");
        assertEquals(20, thresholds.get("maxMethods"));
        assertEquals(10, thresholds.get("maxFields"));
        assertEquals(300, thresholds.get("maxLines"));
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should report total classes scanned")
    void reportsTotalClassesScanned() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalScanned = (int) data.get("totalClassesScanned");
        assertTrue(totalScanned > 0, "Should scan at least one class");
    }

    // ========== Semantic-grade tests (LargeClass boundary fixture) ==========

    @Test
    @DisplayName("default thresholds: LargeClass appears (21 methods > 20, 11 fields > 10); Calculator does not")
    void defaultThresholds_largeClassFlagged_calculatorNot() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("largeClasses");
        java.util.Set<String> names = classes.stream()
            .map(c -> (String) c.get("typeName"))
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(names.contains("LargeClass"),
            "LargeClass has 21 methods and 11 fields; exceeds default thresholds (20/10). Got: " + names);
        assertFalse(names.contains("Calculator"),
            "Calculator has few methods/fields; must not be flagged. Got: " + names);
    }

    @Test
    @DisplayName("maxMethods=22 (above LargeClass count): LargeClass still flagged by fields (11 > default 10)")
    void boundary_methodsAboveLargeClass_stillFlaggedByFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 22);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("largeClasses");
        boolean hasLargeClass = classes.stream().anyMatch(c -> "LargeClass".equals(c.get("typeName")));
        assertTrue(hasLargeClass,
            "Even with maxMethods=22, LargeClass's 11 fields exceed default maxFields=10");
    }

    @Test
    @DisplayName("maxMethods=22 and maxFields=12 (above LargeClass counts): LargeClass not flagged by counts")
    void boundary_aboveBothMethodAndFieldCounts_largeClassNotFlaggedByCounts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 22);
        args.put("maxFields", 12);
        args.put("maxLines", 10000);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) getData(r).get("largeClasses");
        boolean hasLargeClass = classes.stream().anyMatch(c -> "LargeClass".equals(c.get("typeName")));
        assertFalse(hasLargeClass,
            "With all thresholds above LargeClass's metrics, it must not appear; got: " +
                classes.stream().map(c -> c.get("typeName")).toList());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> classesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("largeClasses");
    }

    @Test
    @DisplayName("violations list mentions specific thresholds breached (methods or fields)")
    void violations_describeThresholds() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : classesOf(r)) {
            @SuppressWarnings("unchecked")
            List<String> v = (List<String>) c.get("violations");
            assertFalse(v.isEmpty(), "violations list must be non-empty when class is flagged: " + c);
            for (String entry : v) {
                assertTrue(
                    entry.startsWith("methods:") || entry.startsWith("fields:") || entry.startsWith("lines:"),
                    "Each violation must specify methods/fields/lines threshold; got: " + entry);
            }
        }
    }

    @Test
    @DisplayName("totalViolations == largeClasses.size()")
    void totalViolations_equalsListSize() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalViolations")).intValue();
        assertEquals(total, classesOf(r).size());
    }

    @Test
    @DisplayName("Each large class entry has file, typeName, methodCount, fieldCount, lineCount, violations")
    void entryShape_includesAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 5);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : classesOf(r)) {
            for (String key : List.of("file", "typeName", "methodCount", "fieldCount", "lineCount", "violations")) {
                assertNotNull(c.get(key), key + " missing on largeClass entry: " + c);
            }
        }
    }

    @Test
    @DisplayName("Custom thresholds reported in thresholds map verbatim")
    void thresholds_echoed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 7);
        args.put("maxFields", 4);
        args.put("maxLines", 150);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) getData(r).get("thresholds");
        assertEquals(7, thresholds.get("maxMethods"));
        assertEquals(4, thresholds.get("maxFields"));
        assertEquals(150, thresholds.get("maxLines"));
    }
}
