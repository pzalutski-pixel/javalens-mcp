package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDiRegistrationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDiRegistrationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiRegistrationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Non-Spring Project Tests ==========

    @Test
    @DisplayName("should return empty results for non-Spring project")
    void returnsEmptyForNonSpringProject() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Non-Spring project should have empty categories but not error
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary, "Should include summary");
        assertEquals(0, summary.get("components"));
        assertEquals(0, summary.get("configurations"));
        assertEquals(0, summary.get("beans"));
        assertEquals(0, summary.get("injectionPoints"));
    }

    // ========== Structure Tests ==========

    @Test
    @DisplayName("should return all expected categories in output")
    void returnsAllCategories() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertNotNull(data.get("summary"), "Should include summary");
        assertNotNull(data.get("components"), "Should include components list");
        assertNotNull(data.get("configurations"), "Should include configurations list");
        assertNotNull(data.get("beans"), "Should include beans list");
        assertNotNull(data.get("injectionPoints"), "Should include injectionPoints list");
    }

    @Test
    @DisplayName("should respect maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        // Should not throw and should return valid structure
        assertNotNull(getData(response).get("summary"));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("summary keys are exactly {components, configurations, beans, injectionPoints}")
    void summary_hasExactKeys() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) getData(r).get("summary");
        assertEquals(
            java.util.Set.of("components", "configurations", "beans", "injectionPoints"),
            summary.keySet(),
            "summary keys must be exactly the four documented categories; got: " + summary.keySet());
    }

    @Test
    @DisplayName("Top-level data has summary + four category lists; lists are all empty for non-Spring project")
    void categories_emptyListsForNonSpring() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : List.of("components", "configurations", "beans", "injectionPoints")) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) data.get(key);
            assertNotNull(list, key + " missing on data");
            assertTrue(list.isEmpty(),
                key + " must be empty for non-Spring project; got: " + list);
        }
    }

    @Test
    @DisplayName("Each summary count equals the corresponding list's size")
    void summaryCounts_matchListSizes() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, Number> summary = (Map<String, Number>) data.get("summary");
        for (String key : List.of("components", "configurations", "beans", "injectionPoints")) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) data.get(key);
            assertEquals(summary.get(key).intValue(), list.size(),
                "summary." + key + " must equal " + key + ".size(); got summary="
                    + summary.get(key) + " listSize=" + list.size());
        }
    }
}
