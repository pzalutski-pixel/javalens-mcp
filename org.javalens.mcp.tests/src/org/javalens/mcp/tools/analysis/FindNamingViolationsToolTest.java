package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindNamingViolationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindNamingViolationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Violation Detection Tests ==========

    @Test
    @DisplayName("should find naming violations in file with bad names")
    void findsNamingViolations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalViolations = (int) data.get("totalViolations");
        assertTrue(totalViolations > 0, "Should find naming violations in DiAndReflectionPatterns.java");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) data.get("violations");
        assertFalse(violations.isEmpty());

        // Verify violation structure
        Map<String, Object> firstViolation = violations.get(0);
        assertNotNull(firstViolation.get("file"), "Should include file path");
        assertNotNull(firstViolation.get("line"), "Should include line number");
        assertNotNull(firstViolation.get("elementType"), "Should include element type");
        assertNotNull(firstViolation.get("name"), "Should include the name");
        assertNotNull(firstViolation.get("convention"), "Should include expected convention");
    }

    @Test
    @DisplayName("should detect Bad_Field_Name as field naming violation")
    void detectsBadFieldName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundFieldViolation = violations.stream()
            .anyMatch(v -> "Bad_Field_Name".equals(v.get("name")) && "field".equals(v.get("elementType")));
        assertTrue(foundFieldViolation, "Should detect Bad_Field_Name as a field naming violation");
    }

    @Test
    @DisplayName("should detect Bad_Method_Name as method naming violation")
    void detectsBadMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundMethodViolation = violations.stream()
            .anyMatch(v -> "Bad_Method_Name".equals(v.get("name")) && "method".equals(v.get("elementType")));
        assertTrue(foundMethodViolation, "Should detect Bad_Method_Name as a method naming violation");
    }

    @Test
    @DisplayName("should detect badConstant as constant naming violation")
    void detectsBadConstantName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundConstantViolation = violations.stream()
            .anyMatch(v -> "badConstant".equals(v.get("name")) && "constant".equals(v.get("elementType")));
        assertTrue(foundConstantViolation, "Should detect badConstant as a constant naming violation");
    }

    // ========== Clean File Tests ==========

    @Test
    @DisplayName("should return no violations for well-named file")
    void returnsNoViolationsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/Calculator.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(0, data.get("totalViolations"));
    }

    // ========== Project-Wide Scan Tests ==========

    @Test
    @DisplayName("should scan all files when no filePath specified")
    void scansAllFilesWhenNoPathSpecified() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int filesScanned = (int) data.get("filesScanned");
        assertTrue(filesScanned > 1, "Should scan multiple files");
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> violationsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("violations");
    }

    @Test
    @DisplayName("Violation entries have file, line, elementType, name, convention")
    void violationEntry_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> v : violationsOf(r)) {
            for (String key : List.of("file", "line", "elementType", "name", "convention")) {
                assertNotNull(v.get(key), key + " missing on violation: " + v);
            }
        }
    }

    @Test
    @DisplayName("Convention values are one of {PascalCase, camelCase, UPPER_SNAKE_CASE}")
    void conventionValues_valid() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> allowed = java.util.Set.of("PascalCase", "camelCase", "UPPER_SNAKE_CASE");
        for (Map<String, Object> v : violationsOf(r)) {
            assertTrue(allowed.contains(v.get("convention")),
                "convention must be one of " + allowed + "; got: " + v);
        }
    }

    @Test
    @DisplayName("elementType values are one of {class, enum, method, field, constant, parameter}")
    void elementTypeValues_valid() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        java.util.Set<String> allowed = java.util.Set.of("class", "enum", "method", "field", "constant", "parameter");
        for (Map<String, Object> v : violationsOf(r)) {
            assertTrue(allowed.contains(v.get("elementType")),
                "elementType must be one of " + allowed + "; got: " + v);
        }
    }

    @Test
    @DisplayName("Bad_Method_Name reports convention=camelCase")
    void badMethodName_reportsCorrectConvention() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> v = violationsOf(r).stream()
            .filter(x -> "Bad_Method_Name".equals(x.get("name")))
            .findFirst().orElseThrow();
        assertEquals("camelCase", v.get("convention"));
    }

    @Test
    @DisplayName("badConstant reports convention=UPPER_SNAKE_CASE")
    void badConstant_reportsCorrectConvention() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> v = violationsOf(r).stream()
            .filter(x -> "badConstant".equals(x.get("name")))
            .findFirst().orElseThrow();
        assertEquals("UPPER_SNAKE_CASE", v.get("convention"));
    }

    @Test
    @DisplayName("Clean file: GOOD_CONSTANT and well-named members are NOT violations")
    void cleanMembers_notReported() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Map<String, Object> v : violationsOf(r)) names.add((String) v.get("name"));
        assertFalse(names.contains("GOOD_CONSTANT"),
            "GOOD_CONSTANT is properly named — must not be flagged; got: " + names);
        assertFalse(names.contains("getName"),
            "getName is camelCase — must not be flagged; got: " + names);
        assertFalse(names.contains("DiAndReflectionPatterns"),
            "Class DiAndReflectionPatterns is PascalCase — must not be flagged; got: " + names);
    }

    @Test
    @DisplayName("totalViolations == violations.size()")
    void totalViolations_equalsListSize() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        int total = ((Number) getData(r).get("totalViolations")).intValue();
        assertEquals(total, violationsOf(r).size());
    }
}
