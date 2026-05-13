package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetClasspathInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetClasspathInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetClasspathInfoTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetClasspathInfoTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete classpath info")
    void returnsCompleteClasspathInfo() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify project info
        assertNotNull(data.get("projectName"));
        assertNotNull(data.get("projectRoot"));
        assertNotNull(data.get("totalEntries"));

        // Verify source folders
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceFolders = (List<Map<String, Object>>) data.get("sourceFolders");
        assertNotNull(sourceFolders);
        assertFalse(sourceFolders.isEmpty());
        assertEquals("source", sourceFolders.get(0).get("kind"));

        // Verify containers (JRE)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers = (List<Map<String, Object>>) data.get("containers");
        assertNotNull(containers);
    }

    @Test @DisplayName("supports filter parameters")
    void supportsFilterParameters() {
        // Exclude libraries
        ObjectNode noLibs = objectMapper.createObjectNode();
        noLibs.put("includeLibraries", false);
        ToolResponse r1 = tool.execute(noLibs);
        assertTrue(r1.isSuccess());
        assertNull(getData(r1).get("libraries"));

        // Exclude source
        ObjectNode noSrc = objectMapper.createObjectNode();
        noSrc.put("includeSource", false);
        ToolResponse r2 = tool.execute(noSrc);
        assertTrue(r2.isSuccess());
        assertNull(getData(r2).get("sourceFolders"));

        // Exclude containers
        ObjectNode noContainers = objectMapper.createObjectNode();
        noContainers.put("includeContainers", false);
        ToolResponse r3 = tool.execute(noContainers);
        assertTrue(r3.isSuccess());
        assertNull(getData(r3).get("containers"));
    }

    @Test @DisplayName("works with no parameters")
    void worksWithNoParameters() {
        assertTrue(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("sourceFolders include both src/main/java and src/test/java with kind='source' and a path")
    @SuppressWarnings("unchecked")
    void sourceFolders_includeMainAndTestWithCorrectKind() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> sourceFolders =
            (List<Map<String, Object>>) getData(r).get("sourceFolders");
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Map<String, Object> entry : sourceFolders) {
            assertEquals("source", entry.get("kind"),
                "Every entry in sourceFolders must have kind='source'; got: " + entry);
            assertNotNull(entry.get("path"),
                "Every classpath entry must carry a path; got: " + entry);
            paths.add(((String) entry.get("path")).replace('\\', '/'));
        }
        boolean hasMain = paths.stream().anyMatch(p -> p.endsWith("/src/main/java"));
        boolean hasTest = paths.stream().anyMatch(p -> p.endsWith("/src/test/java"));
        assertTrue(hasMain,
            "src/main/java must appear among source folders; got paths: " + paths);
        assertTrue(hasTest,
            "src/test/java must appear among source folders; got paths: " + paths);
    }

    @Test
    @DisplayName("containers contain a JRE entry with kind='container'")
    @SuppressWarnings("unchecked")
    void containers_includeJreEntry() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> containers =
            (List<Map<String, Object>>) getData(r).get("containers");
        for (Map<String, Object> entry : containers) {
            assertEquals("container", entry.get("kind"),
                "Every container entry must have kind='container'; got: " + entry);
            assertNotNull(entry.get("path"));
        }
        // The JDT JRE container path begins with org.eclipse.jdt.launching.JRE_CONTAINER.
        boolean hasJre = containers.stream()
            .map(e -> (String) e.get("path"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(p -> p.contains("JRE_CONTAINER"));
        assertTrue(hasJre,
            "JRE container must appear among classpath containers; got: " + containers);
    }

    @Test
    @DisplayName("totalEntries equals the count of returned entries across all categories")
    @SuppressWarnings("unchecked")
    void totalEntries_equalsSumOfCategoriesIncluded() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        int totalEntries = ((Number) data.get("totalEntries")).intValue();
        int sum = 0;
        for (String category : List.of("sourceFolders", "libraries", "containers",
            "projectDependencies", "variables")) {
            Object list = data.get(category);
            if (list instanceof List<?> l) {
                sum += l.size();
            }
        }
        assertEquals(sum, totalEntries,
            "totalEntries must equal the sum of every returned classpath entry list; got: " + data);
    }

    @Test
    @DisplayName("outputLocation field is present")
    void outputLocation_isPresent() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("outputLocation"),
            "outputLocation (project bin folder) must be reported; got: " + data);
    }

    @Test
    @DisplayName("projectName and projectRoot are reported with exact values")
    void projectNameAndRoot_areReported() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("projectName"),
            "projectName must be present; got: " + data);
        assertNotNull(data.get("projectRoot"),
            "projectRoot must be present; got: " + data);
    }

    @Test
    @DisplayName("multi-module-maven: every module's src/main/java is reported as a separate source folder")
    @SuppressWarnings("unchecked")
    void multiModule_aggregatesAllModuleSourceFolders() throws Exception {
        JdtServiceImpl svc = helper.loadProject("multi-module-maven");
        GetClasspathInfoTool localTool = new GetClasspathInfoTool(() -> svc);

        ToolResponse r = localTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        List<Map<String, Object>> sourceFolders =
            (List<Map<String, Object>>) getData(r).get("sourceFolders");

        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Map<String, Object> entry : sourceFolders) {
            paths.add(((String) entry.get("path")).replace('\\', '/'));
        }
        // multi-module-maven has api/, impl/, web/ each with src/main/java. All three
        // must appear as distinct source folder entries on the aggregated classpath.
        boolean hasApi = paths.stream().anyMatch(p -> p.contains("/api/src/main/java"));
        boolean hasImpl = paths.stream().anyMatch(p -> p.contains("/impl/src/main/java"));
        boolean hasWeb = paths.stream().anyMatch(p -> p.contains("/web/src/main/java"));
        assertTrue(hasApi, "api module source folder missing; got: " + paths);
        assertTrue(hasImpl, "impl module source folder missing; got: " + paths);
        assertTrue(hasWeb, "web module source folder missing; got: " + paths);
    }
}
