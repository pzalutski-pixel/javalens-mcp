package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Violation Detection Tests ==========

    @Test
    @DisplayName("DiAndReflectionPatterns.java: exactly 3 naming violations with exact type/convention/line")
    void findsNamingViolations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(3, ((Number) data.get("totalViolations")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) data.get("violations");
        assertEquals(3, violations.size());

        Map<String, List<Object>> byName = new java.util.HashMap<>();
        for (Map<String, Object> v : violations) {
            byName.put((String) v.get("name"), List.of(
                v.get("elementType"), v.get("convention"), ((Number) v.get("line")).intValue()));
        }
        // name -> [elementType, convention, 0-based decl line] — exact set is the isolation oracle.
        assertEquals(Map.of(
            "Bad_Field_Name", List.of("field", "camelCase", 12),
            "badConstant", List.of("constant", "UPPER_SNAKE_CASE", 15),
            "Bad_Method_Name", List.of("method", "camelCase", 92)),
            byName);
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
    @DisplayName("elementType values are one of {class, enum, record, annotation, method, field, constant, parameter}")
    void elementTypeValues_valid() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        java.util.Set<String> allowed = java.util.Set.of(
            "class", "enum", "record", "annotation", "method", "field", "constant", "parameter");
        for (Map<String, Object> v : violationsOf(r)) {
            assertTrue(allowed.contains(v.get("elementType")),
                "elementType must be one of " + allowed + "; got: " + v);
        }
    }

    @Test
    @DisplayName("Top-level record with non-PascalCase name is flagged with elementType=record")
    void recordDeclaration_isChecked() {
        // bad_record is a top-level record declaration whose name violates
        // PascalCase. Records are AbstractTypeDeclaration but not
        // TypeDeclaration; a visitor that only handles TypeDeclaration
        // misses them.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NamingViolationFixtures.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        boolean found = violationsOf(r).stream()
            .anyMatch(v -> "bad_record".equals(v.get("name"))
                && "record".equals(v.get("elementType"))
                && "PascalCase".equals(v.get("convention")));
        assertTrue(found,
            "bad_record must be flagged as a record naming violation; got: " + violationsOf(r));
    }

    @Test
    @DisplayName("Top-level annotation type with non-PascalCase name is flagged with elementType=annotation")
    void annotationTypeDeclaration_isChecked() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NamingViolationFixtures.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        boolean found = violationsOf(r).stream()
            .anyMatch(v -> "bad_annotation".equals(v.get("name"))
                && "annotation".equals(v.get("elementType"))
                && "PascalCase".equals(v.get("convention")));
        assertTrue(found,
            "bad_annotation must be flagged as an annotation-type naming violation; got: " + violationsOf(r));
    }

    @Test
    @DisplayName("Badly-named method parameter is flagged with elementType=parameter, convention=camelCase")
    void parameterDeclaration_isChecked() {
        // bad_record.compute(int Bad_Param) — the parameter Bad_Param violates camelCase.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/NamingViolationFixtures.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> v = violationsOf(r).stream()
            .filter(x -> "Bad_Param".equals(x.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Bad_Param parameter must be flagged; got: " + violationsOf(r)));
        assertEquals("parameter", v.get("elementType"));
        assertEquals("camelCase", v.get("convention"));
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

    // ========== Exact 0-based declaration lines ==========

    private int lineOf(List<Map<String, Object>> violations, String name) {
        Map<String, Object> v = violations.stream()
            .filter(x -> name.equals(x.get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("no violation named " + name + ": " + violations));
        return ((Number) v.get("line")).intValue();
    }

    @Test
    @DisplayName("Named violations report exact 0-based declaration lines")
    void namedViolations_exactZeroBasedLines() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> v = violationsOf(r);

        // DiAndReflectionPatterns.java declarations (0-based): Bad_Field_Name at 12,
        // badConstant at 15, Bad_Method_Name at 92. Lines are 0-based per the tool
        // contract; a dropped zero-based conversion shifts every line by one.
        assertEquals(12, lineOf(v, "Bad_Field_Name"));
        assertEquals(15, lineOf(v, "badConstant"));
        assertEquals(92, lineOf(v, "Bad_Method_Name"));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Bad_Field_Name reported at exact 0-based line 12")
    void envelope_badFieldName_exactLine() {
        ObjectNode args = envelope.args();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        JsonNode payload = envelope.assertEnvelopeFidelity("find_naming_violations", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_naming_violations failed through the envelope: " + payload);
        JsonNode found = null;
        for (JsonNode x : payload.get("data").get("violations")) {
            if ("Bad_Field_Name".equals(x.get("name").asText())) found = x;
        }
        assertNotNull(found, "Bad_Field_Name not reported through the envelope");
        assertEquals(12, found.get("line").asInt(),
            "the 0-based declaration line must survive the JSON-RPC envelope");
    }
}
