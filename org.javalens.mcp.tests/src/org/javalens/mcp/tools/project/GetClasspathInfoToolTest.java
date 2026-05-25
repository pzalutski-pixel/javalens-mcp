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

        // Verify project info — non-blank identity fields, non-negative count
        String projectName = (String) data.get("projectName");
        assertNotNull(projectName, "projectName missing");
        assertFalse(projectName.isBlank(), "projectName non-blank; got: " + data);
        String projectRoot = (String) data.get("projectRoot");
        assertNotNull(projectRoot, "projectRoot missing");
        assertFalse(projectRoot.isBlank(), "projectRoot non-blank; got: " + data);
        assertTrue(((Number) data.get("totalEntries")).intValue() >= 0,
            "totalEntries >= 0; got: " + data);

        // Verify source folders
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceFolders = (List<Map<String, Object>>) data.get("sourceFolders");
        assertNotNull(sourceFolders, "sourceFolders list must be present");
        assertFalse(sourceFolders.isEmpty(), "fixture has source folders");
        assertEquals("source", sourceFolders.get(0).get("kind"));

        // Verify containers (JRE) — should always include at least JRE
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers = (List<Map<String, Object>>) data.get("containers");
        assertNotNull(containers, "containers list must be present");
        assertFalse(containers.isEmpty(),
            "Every Java project has at least a JRE container; got: " + containers);
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
        // TestProjectHelper imports source folders as linked folders named
        // "src-{index}-{path-with-dashes}"; src/main/java → src-N-src-main-java.
        boolean hasMain = paths.stream().anyMatch(p -> p.endsWith("src-main-java"));
        boolean hasTest = paths.stream().anyMatch(p -> p.endsWith("src-test-java"));
        assertTrue(hasMain,
            "src/main/java source folder must appear; got paths: " + paths);
        assertTrue(hasTest,
            "src/test/java source folder must appear; got paths: " + paths);
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

    // ========== Resolved-classpath section ==========
    //
    // The raw classpath returned above shows containers as opaque paths
    // (org.eclipse.jdt.launching.JRE_CONTAINER). The `resolved` section exposes
    // what JDT actually sees post-container-expansion: every system module from
    // the JRE container becomes its own library entry, every library entry is
    // validated for on-disk existence, and per-entry extra-attributes (e.g. the
    // `module` attribute that controls modulepath vs classpath placement) are
    // surfaced.

    @Test
    @DisplayName("resolved section is present and contains more entries than raw (container expanded)")
    @SuppressWarnings("unchecked")
    void resolved_sectionExpandsContainersIntoIndividualEntries() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        List<Map<String, Object>> resolved = (List<Map<String, Object>>) data.get("resolved");
        assertNotNull(resolved, "resolved section must be present; got: " + data.keySet());
        assertFalse(resolved.isEmpty(), "resolved section must contain entries");

        int containerCount = ((List<?>) data.get("containers")).size();
        int resolvedCount = resolved.size();
        assertTrue(resolvedCount > containerCount,
            "Resolved entries should outnumber raw containers (each container expands to "
                + "multiple library entries); raw containers=" + containerCount
                + " resolved=" + resolvedCount);
    }

    @Test
    @DisplayName("resolved entries include the JDK system modules from JRE container expansion")
    @SuppressWarnings("unchecked")
    void resolved_includesJdkSystemModules() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> resolved =
            (List<Map<String, Object>>) getData(r).get("resolved");
        boolean hasJdkBootstrap = resolved.stream()
            .map(e -> (String) e.get("path"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(p -> p.contains("jrt-fs.jar")
                || p.contains("java.base")
                || p.endsWith("rt.jar")
                || p.startsWith("jrt:"));
        assertTrue(hasJdkBootstrap,
            "Resolved classpath must include a JDK bootstrap entry "
                + "(jrt-fs.jar / java.base / rt.jar / jrt:); got paths: "
                + resolved.stream().map(e -> e.get("path")).toList());
    }

    @Test
    @DisplayName("each resolved entry carries kind, path, and exists fields")
    @SuppressWarnings("unchecked")
    void resolved_perEntryShape() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        List<Map<String, Object>> resolved =
            (List<Map<String, Object>>) getData(r).get("resolved");
        for (Map<String, Object> entry : resolved) {
            assertNotNull(entry.get("kind"),
                "Every resolved entry must report its kind; got: " + entry);
            assertNotNull(entry.get("path"),
                "Every resolved entry must report its path; got: " + entry);
            assertTrue(entry.containsKey("exists"),
                "Every resolved entry must report file-existence status; got: " + entry);
            assertTrue(entry.get("exists") instanceof Boolean,
                "exists must be a boolean; got: " + entry);
        }
    }

    // ========== JRE section ==========
    //
    // Today the only way to know which JDK JDT picked is to inspect the
    // running process; the tool never reports it. For projects loaded under a
    // different JDK than the user expects (npm-launched runtimes, IDE bundled
    // JVMs, container images), this is a real source of confusion.

    @Test
    @DisplayName("jre section reports the JRE that JDT actually selected for the project")
    @SuppressWarnings("unchecked")
    void jre_sectionReportsDetectedRuntime() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        Map<String, Object> jre = (Map<String, Object>) data.get("jre");
        assertNotNull(jre, "jre section must be present; got data keys: " + data.keySet());

        String installLocation = (String) jre.get("installLocation");
        assertNotNull(installLocation, "jre.installLocation must be reported; got: " + jre);
        assertFalse(installLocation.isBlank(), "jre.installLocation must be non-blank");

        String name = (String) jre.get("name");
        assertNotNull(name, "jre.name must be reported; got: " + jre);
    }

    @Test
    @DisplayName("jre section reports javaVersion when the IVMInstall is an IVMInstall2")
    @SuppressWarnings("unchecked")
    void jre_reportsJavaVersionWhenAvailable() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> jre = (Map<String, Object>) getData(r).get("jre");
        assertNotNull(jre);
        String javaVersion = (String) jre.get("javaVersion");
        assertNotNull(javaVersion,
            "jre.javaVersion must be reported (the running JVM is always IVMInstall2-capable); got: "
                + jre);
        assertFalse(javaVersion.isBlank(),
            "jre.javaVersion must be non-blank; got: " + javaVersion);
    }

    @Test
    @DisplayName("jre section lists the system modules the project can reach (includes java.base)")
    @SuppressWarnings("unchecked")
    void jre_systemModulesIncludeJavaBase() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        Map<String, Object> jre = (Map<String, Object>) data.get("jre");
        assertNotNull(jre);
        List<String> systemModules = (List<String>) jre.get("systemModules");
        assertNotNull(systemModules,
            "jre.systemModules must be reported (list of module names visible to the project); got: "
                + jre);
        assertTrue(systemModules.contains("java.base"),
            "jre.systemModules must include java.base (every project reads java.base); got: "
                + systemModules);
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
        // Linked folder naming: api/src/main/java → src-N-api-src-main-java, etc.
        boolean hasApi = paths.stream().anyMatch(p -> p.contains("api-src-main-java"));
        boolean hasImpl = paths.stream().anyMatch(p -> p.contains("impl-src-main-java"));
        boolean hasWeb = paths.stream().anyMatch(p -> p.contains("web-src-main-java"));
        assertTrue(hasApi, "api module source folder missing; got: " + paths);
        assertTrue(hasImpl, "impl module source folder missing; got: " + paths);
        assertTrue(hasWeb, "web module source folder missing; got: " + paths);
    }
}
