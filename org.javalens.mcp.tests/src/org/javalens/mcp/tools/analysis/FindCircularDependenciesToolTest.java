package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
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
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindCircularDependenciesTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("project-wide: hasCycles=true, exactly 2 cycles, counts consistent")
    void detectsCyclesComprehensively() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("hasCycles"));
        // cycledemo (a<->b) + staticcycle (x<->y).
        assertEquals(2, ((Number) data.get("cycleCount")).intValue());
        @SuppressWarnings("unchecked")
        List<?> cycles = (List<?>) data.get("cycles");
        assertEquals(2, cycles.size(), "cycleCount must equal cycles list size; got: " + data);
        assertNotNull(data.get("affectedPackages"), "affectedPackages missing");
    }

    @Test @DisplayName("maxCycleLength excludes cycles longer than the limit (tricycle length 3)")
    void maxCycleLength_filtersOutLongerCycles() throws Exception {
        // tricycle-maven has a single 3-package SCC (a->b->c->a). The tool filters SCCs
        // to those with size <= maxCycleLength.
        JdtServiceImpl triService = helper.loadProject("tricycle-maven");
        FindCircularDependenciesTool triTool = new FindCircularDependenciesTool(() -> triService);

        // maxCycleLength=2 -> the length-3 cycle is filtered out.
        ObjectNode tooShort = objectMapper.createObjectNode();
        tooShort.put("maxCycleLength", 2);
        Map<String, Object> shortData = getData(triTool.execute(tooShort));
        assertEquals(false, shortData.get("hasCycles"),
            "a length-3 cycle must be excluded when maxCycleLength=2; got: " + shortData);
        assertEquals(0, ((Number) shortData.get("cycleCount")).intValue());

        // maxCycleLength=3 -> the cycle is included.
        ObjectNode justRight = objectMapper.createObjectNode();
        justRight.put("maxCycleLength", 3);
        Map<String, Object> okData = getData(triTool.execute(justRight));
        assertEquals(true, okData.get("hasCycles"));
        assertEquals(1, ((Number) okData.get("cycleCount")).intValue());
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
    @DisplayName("a 3-package cycle (a->b->c->a) is detected as HIGH severity, length 3")
    void threePackageCycle_isHighSeverity() throws Exception {
        // tricycle-maven fixture: com.tri.a -> b -> c -> a, a single 3-package
        // SCC. The tool's severity rule is `length <= 2 ? medium : high`; with
        // only 2-package cycles elsewhere the high branch was never exercised.
        JdtServiceImpl triService = helper.loadProject("tricycle-maven");
        FindCircularDependenciesTool triTool = new FindCircularDependenciesTool(() -> triService);

        ToolResponse r = triTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());
        Map<String, Object> data = getData(r);

        assertEquals(true, data.get("hasCycles"));
        assertEquals(1, ((Number) data.get("cycleCount")).intValue(),
            "the three packages form exactly one cycle; got: " + data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) data.get("cycles");
        Map<String, Object> cycle = cycles.get(0);
        assertEquals(3, ((Number) cycle.get("length")).intValue(),
            "the cycle spans exactly 3 packages; got: " + cycle);
        assertEquals("high", cycle.get("severity"),
            "a 3-package cycle is high severity (the previously dead branch); got: " + cycle);

        @SuppressWarnings("unchecked")
        java.util.Set<String> packages = new java.util.HashSet<>((List<String>) cycle.get("packages"));
        assertEquals(java.util.Set.of("com.tri.a", "com.tri.b", "com.tri.c"), packages);
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
    @DisplayName("default (project-wide) scan finds the cycledemo and staticcycle cycles")
    void projectWide_findsCycledemoCycle() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // The simple-maven fixture has two distinct package cycles:
        // - com.example.cycledemo.a <-> com.example.cycledemo.b (type imports)
        // - com.example.staticcycle.x <-> com.example.staticcycle.y (static imports)
        // A project-wide scan must surface both.
        assertEquals(true, data.get("hasCycles"),
            "simple-maven contains package cycles; project-wide scan must detect them");
        assertEquals(2, ((Number) data.get("cycleCount")).intValue(),
            "Expected exactly 2 cycles project-wide (cycledemo + staticcycle); got: " + data);

        @SuppressWarnings("unchecked")
        java.util.Set<String> affected = new java.util.HashSet<>(
            (List<String>) data.get("affectedPackages"));
        assertEquals(
            java.util.Set.of(
                "com.example.cycledemo.a", "com.example.cycledemo.b",
                "com.example.staticcycle.x", "com.example.staticcycle.y"),
            affected,
            "affectedPackages must be exactly the four cycle members; got: " + affected);
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Each cycle entry carries packages, path, length, severity")
    void cycleEntry_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example.cycledemo");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) getData(r).get("cycles");
        assertFalse(cycles.isEmpty());
        for (Map<String, Object> c : cycles) {
            for (String key : List.of("packages", "path", "length", "severity")) {
                assertNotNull(c.get(key), key + " missing on cycle: " + c);
            }
            String path = (String) c.get("path");
            assertTrue(path.contains(" -> "),
                "Cycle path must use ` -> ` separator; got: " + path);
        }
    }

    @Test
    @DisplayName("cycleCount equals cycles.size()")
    void cycleCount_equalsListSize() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int count = ((Number) data.get("cycleCount")).intValue();
        @SuppressWarnings("unchecked")
        List<?> cycles = (List<?>) data.get("cycles");
        assertEquals(count, cycles.size());
    }

    @Test
    @DisplayName("suggestions provided when cycles exist")
    void suggestions_presentWithCycles() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) data.get("suggestions");
        if (Boolean.TRUE.equals(data.get("hasCycles"))) {
            assertNotNull(suggestions);
            assertFalse(suggestions.isEmpty(),
                "Suggestions must be non-empty when cycles exist");
        }
    }

    @Test
    @DisplayName("Static-import cycle between staticcycle.x and staticcycle.y is detected")
    void staticImportCycle_isDetected() {
        // The fixture establishes a cycle staticcycle.x <-> staticcycle.y
        // purely through `import static`. The dependency-extraction step
        // must recognise that a static import points at a package, not at
        // a type or member, and walk past the member and declaring class
        // to reach the package name.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example.staticcycle");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(true, data.get("hasCycles"),
            "staticcycle.x and staticcycle.y mutually `import static`; the tool must "
                + "report a cycle. Got: " + data);

        @SuppressWarnings("unchecked")
        java.util.Set<String> affected = new java.util.HashSet<>(
            (List<String>) data.get("affectedPackages"));
        assertEquals(
            java.util.Set.of("com.example.staticcycle.x", "com.example.staticcycle.y"),
            affected,
            "Cycle members must be exactly the two staticcycle subpackages; got: " + affected);
    }

    @Test
    @DisplayName("Severity is 'medium' for 2-package cycles and 'high' for larger cycles")
    void severity_byCycleLength() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example.cycledemo");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) getData(r).get("cycles");
        for (Map<String, Object> c : cycles) {
            int len = ((Number) c.get("length")).intValue();
            String sev = (String) c.get("severity");
            if (len <= 2) {
                assertEquals("medium", sev, "2-package cycle must be medium severity; got: " + c);
            } else {
                assertEquals("high", sev, ">2-package cycle must be high severity; got: " + c);
            }
        }
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: cycledemo reports exactly one 2-package medium cycle")
    void envelope_cycledemo_exactCycle() {
        ObjectNode args = envelope.args();
        args.put("packageFilter", "com.example.cycledemo");
        JsonNode payload = envelope.assertEnvelopeFidelity("find_circular_dependencies", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "find_circular_dependencies failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertTrue(data.get("hasCycles").asBoolean());
        assertEquals(1, data.get("cycleCount").asInt(), "exactly one cycle through the envelope");
        JsonNode cycle = data.get("cycles").get(0);
        assertEquals(2, cycle.get("length").asInt());
        assertEquals("medium", cycle.get("severity").asText());
        java.util.Set<String> pkgs = new java.util.TreeSet<>();
        for (JsonNode p : cycle.get("packages")) pkgs.add(p.asText());
        assertEquals(java.util.Set.of("com.example.cycledemo.a", "com.example.cycledemo.b"), pkgs,
            "the exact cycle members must survive the envelope");
    }
}
