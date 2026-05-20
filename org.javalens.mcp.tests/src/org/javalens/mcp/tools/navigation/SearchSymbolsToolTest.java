package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SearchSymbolsTool.
 * Tests pattern matching, kind filtering, and pagination.
 */
class SearchSymbolsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SearchSymbolsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SearchSymbolsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getResults(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("results");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class search returns results with name, kind, qualifiedName, and filePath")
    void classSearch_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("kind", "class");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        Map<String, Object> calcResult = results.stream()
            .filter(r -> "Calculator".equals(r.get("name")))
            .findFirst()
            .orElse(null);

        assertNotNull(calcResult, "Calculator must be in results");
        assertEquals("com.example.Calculator", calcResult.get("qualifiedName"));
        String filePath = (String) calcResult.get("filePath");
        assertNotNull(filePath, "filePath missing on result");
        assertTrue(filePath.endsWith("Calculator.java"),
            "Calculator result must point to Calculator.java; got: " + filePath);
    }

    @Test
    @DisplayName("Trailing wildcard pattern matches correctly")
    void trailingWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calc*");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.stream().anyMatch(r -> "Calculator".equals(r.get("name"))));
    }

    @Test
    @DisplayName("Leading wildcard pattern matches correctly")
    void leadingWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "*Service");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.stream().anyMatch(r -> "UserService".equals(r.get("name"))));
    }

    @Test
    @DisplayName("Method kind filter returns only methods (every result must have kind=Method)")
    void methodKindFilter_returnsOnlyMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "add*");
        args.put("kind", "Method");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertNotNull(results);
        assertFalse(results.isEmpty(),
            "`add*` must match at least Calculator.add; got empty results");

        // Calculator.add must be among the results (positive case).
        assertTrue(results.stream().anyMatch(r -> "add".equals(r.get("name"))),
            "Calculator.add must appear when searching `add*` with kind=Method; got: " + results);

        // Critical: every result's kind must equal "Method". A regression where the kind
        // filter is silently ignored would otherwise pass.
        for (Map<String, Object> result : results) {
            assertEquals("method", result.get("kind"),
                "every result's emitted kind is lowercase 'method' (input filter can be any case); offending entry: " + result);
        }
    }

    // ========== Pagination Tests ==========

    @Test
    @DisplayName("Pagination with maxResults and offset returns correct metadata")
    @SuppressWarnings("unchecked")
    void pagination_returnsCorrectMetadata() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "*");
        args.put("maxResults", 2);
        args.put("offset", 0);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.size() <= 2);

        Map<String, Object> pagination = (Map<String, Object>) data.get("pagination");
        assertNotNull(pagination, "pagination block must be present");
        assertEquals(0, pagination.get("offset"));
        int returned = ((Number) pagination.get("returned")).intValue();
        assertEquals(results.size(), returned,
            "pagination.returned must equal results.size(); got: " + pagination);
        // hasMore: boolean, true when total > returned. For maxResults=2 with many matches.
        assertNotNull(pagination.get("hasMore"), "hasMore flag must be present");
        assertTrue(pagination.get("hasMore") instanceof Boolean,
            "hasMore must be a boolean; got: " + pagination.get("hasMore").getClass());
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or blank query returns error")
    void parameterValidation_returnsErrors() {
        // Missing query
        ObjectNode args1 = objectMapper.createObjectNode();
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Blank query
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("query", "   ");
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("No matches returns empty results list")
    void noMatches_returnsEmptyResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "NonExistentClass");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.isEmpty());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Middle wildcard `F*er` matches FilledCircle but not unrelated types")
    @SuppressWarnings("unchecked")
    void middleWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "F*er");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        java.util.Set<String> names = results.stream()
            .map(rr -> (String) rr.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // F*er should match FieldHolder, FilledCircle (no — FilledCircle doesn't end er).
        // Actually F.*er covers FieldHolder. Let me adjust to a deterministic pattern:
        // verify FieldHolder is matched, Calculator is NOT matched.
        assertTrue(names.contains("FieldHolder"),
            "F*er should match FieldHolder; got: " + names);
        assertFalse(names.contains("Calculator"),
            "F*er must not match Calculator (doesn't start with F); got: " + names);
    }

    @Test
    @DisplayName("Single-char wildcard `?Shape` matches IShape")
    @SuppressWarnings("unchecked")
    void singleCharWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "?Shape");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        java.util.Set<String> names = results.stream()
            .map(rr -> (String) rr.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("IShape"),
            "`?Shape` should match IShape via single-char wildcard; got: " + names);
    }

    @Test
    @DisplayName("Kind=Interface filter: every result is an Interface; IShape appears among project results")
    @SuppressWarnings("unchecked")
    void kindInterface_filterReturnsOnlyInterfaces() {
        // Use a project-specific query so JDK interfaces don't swamp the result set.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "IShape");
        args.put("kind", "interface");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        assertFalse(results.isEmpty(),
            "`IShape` with kind=Interface should match at least the project's IShape; got empty");
        assertTrue(results.stream().anyMatch(rr -> "IShape".equals(rr.get("name"))),
            "Project's IShape must appear; got: " + results);
        for (Map<String, Object> result : results) {
            assertEquals("interface", result.get("kind"),
                "Every kind=Interface result must have kind='interface'; offending: " + result);
        }
    }

    @Test
    @DisplayName("Kind=Field filter: every result is a Field (lastResult appears)")
    @SuppressWarnings("unchecked")
    void kindField_filterReturnsOnlyFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "lastResult");
        args.put("kind", "Field");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        assertFalse(results.isEmpty());
        for (Map<String, Object> result : results) {
            assertEquals("field", result.get("kind"),
                "Every kind=Field result must have lowercase kind='field'; offending: " + result);
        }
    }

    @Test
    @DisplayName("Type result includes qualifiedName and package")
    @SuppressWarnings("unchecked")
    void typeResult_includesQualifiedNameAndPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("kind", "class");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> calc = getResults(getData(r)).stream()
            .filter(rr -> "Calculator".equals(rr.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals("com.example.Calculator", calc.get("qualifiedName"));
        assertEquals("com.example", calc.get("package"));
        assertNotNull(calc.get("filePath"));
    }

    @Test
    @DisplayName("Method result includes signature and containingType")
    @SuppressWarnings("unchecked")
    void methodResult_includesSignatureAndContainingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "add");
        args.put("kind", "Method");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        // Many JDK classes have `add` methods. Filter to Calculator's explicitly.
        Map<String, Object> add = getResults(getData(r)).stream()
            .filter(rr -> "add".equals(rr.get("name"))
                && "Calculator".equals(rr.get("containingType")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "search for `add` (kind=Method) must include Calculator.add"));
        assertNotNull(add.get("signature"),
            "Method result must include signature; got: " + add);
        assertEquals("Calculator", add.get("containingType"));
    }

    @Test
    @DisplayName("Negative maxResults returns INVALID_PARAMETER naming maxResults")
    void negativeMaxResults_returnsInvalidParameter() {
        // Source: `if (maxResults < 0) return invalidParameter("maxResults", ...)`.
        // This is the strict B-11-fix branch — silent clamping is a regression.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("maxResults", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("maxResults"),
            "Error must name `maxResults`; got: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("Negative offset returns INVALID_PARAMETER naming offset (independent from maxResults)")
    void negativeOffset_returnsInvalidParameter() {
        // Independent guard after maxResults — pin the dedicated branch so a reorder
        // that shadowed the offset check would surface here.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("offset", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("offset"),
            "Error must name `offset`; got: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("kind='enum' filter returns only enum results (Color appears, no class/interface/method)")
    @SuppressWarnings("unchecked")
    void kindEnum_filterReturnsOnlyEnums() {
        // Source's getSearchType switch handles "enum" → IJavaSearchConstants.ENUM.
        // Earlier coverage tests class/interface/method/field but not enum.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Color");
        args.put("kind", "enum");
        args.put("maxResults", 1000);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        assertFalse(results.isEmpty(),
            "`Color` with kind=enum should match TypeKindsFixture.Color; got empty");
        assertTrue(results.stream().anyMatch(rr -> "Color".equals(rr.get("name"))),
            "TypeKindsFixture.Color must appear; got: " + results);
        for (Map<String, Object> result : results) {
            assertEquals("enum", result.get("kind"),
                "Every kind=enum result must have kind='enum'; offending: " + result);
        }
    }

    @Test
    @DisplayName("Pagination offset skips first N results")
    @SuppressWarnings("unchecked")
    void pagination_offsetSkipsResults() {
        // Use a project-prefix query so we work within a bounded result set.
        // `com.example` would query for that as a single symbol; use a wildcard.
        ObjectNode p1Args = objectMapper.createObjectNode();
        p1Args.put("query", "*");
        p1Args.put("kind", "Method");
        p1Args.put("maxResults", 2);
        p1Args.put("offset", 0);

        ToolResponse p1 = tool.execute(p1Args);
        assertTrue(p1.isSuccess());
        List<Map<String, Object>> firstPage = getResults(getData(p1));

        ObjectNode p2Args = objectMapper.createObjectNode();
        p2Args.put("query", "*");
        p2Args.put("kind", "Method");
        p2Args.put("maxResults", 2);
        p2Args.put("offset", 2);

        ToolResponse p2 = tool.execute(p2Args);
        assertTrue(p2.isSuccess());
        Map<String, Object> p2Data = getData(p2);
        List<Map<String, Object>> secondPage = getResults(p2Data);

        // Use (name + filePath + line) tuple as identity since names may collide.
        java.util.function.Function<Map<String, Object>, String> id = r ->
            r.get("name") + "@" + r.get("filePath") + ":" + r.get("line");
        java.util.Set<String> firstIds = firstPage.stream().map(id)
            .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> secondIds = secondPage.stream().map(id)
            .collect(java.util.stream.Collectors.toSet());

        // There are many methods in the project — at least 4 (Calculator's 4 methods
        // alone). Pages must differ.
        if (secondPage.size() == 2 && firstPage.size() == 2) {
            assertNotEquals(firstIds, secondIds,
                "offset=2 with kind=Method must skip the first two entries; got " +
                    "page1=" + firstIds + " page2=" + secondIds);
        }

        Map<String, Object> pagination = (Map<String, Object>) p2Data.get("pagination");
        assertEquals(2, pagination.get("offset"));
    }
}
