package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SuggestImportsTool.
 * Tests import suggestion and relevance ranking.
 */
class SuggestImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SuggestImportsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SuggestImportsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCandidates(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("candidates");
    }

    @Test
    @DisplayName("finds JDK types with complete candidate structure and relevance ranking")
    void findsJdkTypesWithCompleteStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify summary fields
        assertEquals("List", data.get("typeName"));
        assertNotNull(data.get("totalCandidates"));

        // Verify candidates structure
        List<Map<String, Object>> candidates = getCandidates(data);
        assertFalse(candidates.isEmpty());

        // Verify java.util.List is found
        assertTrue(candidates.stream()
            .anyMatch(c -> "java.util.List".equals(c.get("fullyQualifiedName"))));

        // Verify first candidate has all required fields
        Map<String, Object> first = candidates.get(0);
        assertNotNull(first.get("fullyQualifiedName"));
        assertNotNull(first.get("packageName"));
        assertNotNull(first.get("relevance"));
        assertNotNull(first.get("isInterface"));
        assertNotNull(first.get("isClass"));
        assertNotNull(first.get("isEnum"));

        // Verify fixId format
        String fixId = (String) first.get("fixId");
        assertNotNull(fixId);
        assertTrue(fixId.startsWith("add_import:"));

        // Verify relevance ranking: java.util.List should come before java.awt types
        int utilIndex = -1;
        int awtIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String fqn = (String) candidates.get(i).get("fullyQualifiedName");
            if ("java.util.List".equals(fqn)) {
                utilIndex = i;
            } else if (fqn != null && fqn.startsWith("java.awt.")) {
                awtIndex = i;
            }
        }
        if (utilIndex >= 0 && awtIndex >= 0) {
            assertTrue(utilIndex < awtIndex, "java.util.List should rank higher than java.awt types");
        }
    }

    @Test
    @DisplayName("respects maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 3);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertTrue(candidates.size() <= 3);
    }

    @Test
    @DisplayName("finds project types")
    void findsProjectTypes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertTrue(candidates.stream()
            .anyMatch(c -> "com.example.Calculator".equals(c.get("fullyQualifiedName"))));
    }

    @Test
    @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("rejects blank typeName")
    void rejectsBlankTypeName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "   ");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("returns empty list for unknown type")
    void returnsEmptyForUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "NonExistentType12345");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertEquals(0, candidates.size());
    }
}
