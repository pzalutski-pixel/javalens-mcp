package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindCircularDependenciesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindCircularDependenciesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCircularDependenciesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("detects cycles comprehensively")
    void detectsCyclesComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("hasCycles"));
        assertNotNull(data.get("cycleCount"));
        assertNotNull(data.get("cycles"));
        assertNotNull(data.get("affectedPackages"));
    }

    @Test @DisplayName("supports filtering options")
    void supportsFilteringOptions() {
        ObjectNode withFilter = objectMapper.createObjectNode();
        withFilter.put("packageFilter", "com.example");
        assertTrue(tool.execute(withFilter).isSuccess());

        ObjectNode withMaxLength = objectMapper.createObjectNode();
        withMaxLength.put("maxCycleLength", 5);
        assertTrue(tool.execute(withMaxLength).isSuccess());
    }

    @Test @DisplayName("handles non-existent package")
    void handlesNonExistentPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.nonexistent");

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        assertEquals(false, getData(r).get("hasCycles"));
        assertEquals(0, getData(r).get("cycleCount"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("cycledemo packages form a cycle: a <-> b, detected exactly once with both packages affected")
    void cycledemo_detectsAToBCycle() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example.cycledemo");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // The fixture deliberately establishes com.example.cycledemo.a -> b and
        // com.example.cycledemo.b -> a. The tool must report this as exactly one cycle.
        assertEquals(true, data.get("hasCycles"),
            "cycledemo.a and cycledemo.b mutually import each other; tool must detect a cycle. Got: "
                + data);
        assertEquals(1, ((Number) data.get("cycleCount")).intValue(),
            "Expected exactly 1 cycle in the cycledemo subtree; got: " + data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) data.get("cycles");
        assertEquals(1, cycles.size());

        Map<String, Object> cycle = cycles.get(0);
        assertEquals(2, ((Number) cycle.get("length")).intValue(),
            "Cycle has exactly 2 packages; got: " + cycle);
        assertEquals("medium", cycle.get("severity"),
            "2-package cycle is medium severity; got: " + cycle);

        @SuppressWarnings("unchecked")
        java.util.Set<String> packages = new java.util.HashSet<>(
            (List<String>) cycle.get("packages"));
        assertEquals(
            java.util.Set.of("com.example.cycledemo.a", "com.example.cycledemo.b"),
            packages,
            "Cycle packages must be exactly cycledemo.a and cycledemo.b; got: " + packages);

        @SuppressWarnings("unchecked")
        java.util.Set<String> affected = new java.util.HashSet<>(
            (List<String>) data.get("affectedPackages"));
        assertEquals(
            java.util.Set.of("com.example.cycledemo.a", "com.example.cycledemo.b"),
            affected,
            "affectedPackages must list exactly the two cycle members; got: " + affected);
    }

    @Test
    @DisplayName("packageFilter='com.example.service' (single non-cyclic package): hasCycles=false")
    void serviceOnly_hasNoCycles() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example.service");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // com.example.service contains only UserService and has no self-cycle. With this
        // packageFilter the cycledemo subtree is excluded — the result must be empty.
        assertEquals(false, data.get("hasCycles"),
            "com.example.service has no internal cycle; got: " + data);
        assertEquals(0, ((Number) data.get("cycleCount")).intValue());
    }

    @Test
    @DisplayName("default (project-wide) scan finds the cycledemo cycle as the only cycle in simple-maven")
    void projectWide_findsCycledemoCycle() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // With the cycledemo fixture added, the project-wide scan must surface exactly
        // that cycle and no others (no other fixture introduces a package cycle).
        assertEquals(true, data.get("hasCycles"),
            "simple-maven now contains the cycledemo cycle; project-wide scan must detect it");
        assertEquals(1, ((Number) data.get("cycleCount")).intValue(),
            "Expected exactly 1 cycle project-wide (only cycledemo); got: " + data);
    }
}
