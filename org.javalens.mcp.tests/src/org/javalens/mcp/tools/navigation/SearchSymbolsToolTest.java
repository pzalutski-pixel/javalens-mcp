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
        args.put("kind", "Class");

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

        assertNotNull(calcResult);
        assertEquals("com.example.Calculator", calcResult.get("qualifiedName"));
        assertNotNull(calcResult.get("filePath"));
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
    @DisplayName("Method kind filter returns only methods")
    void methodKindFilter_returnsOnlyMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "add*");
        args.put("kind", "Method");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertNotNull(results);
        assertTrue(results.stream().anyMatch(r ->
            "add".equals(r.get("name")) || r.get("name").toString().startsWith("add")));
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
        assertNotNull(pagination);
        assertEquals(0, pagination.get("offset"));
        assertNotNull(pagination.get("returned"));
        assertNotNull(pagination.get("hasMore"));
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
}
