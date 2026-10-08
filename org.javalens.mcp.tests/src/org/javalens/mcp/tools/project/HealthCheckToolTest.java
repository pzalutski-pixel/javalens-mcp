package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.IPathUtils;
import org.javalens.core.PathUtilsImpl;
import org.javalens.core.graph.ProjectGraphService;
import org.javalens.core.project.BuildSystem;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.javalens.core.search.SearchService;
import org.javalens.core.sync.DiskSyncMode;
import org.javalens.mcp.ProjectLoadingState;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.HealthCheckTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckToolTest {

    private HealthCheckTool toolWithProject;
    private HealthCheckTool toolWithoutProject;
    private HealthCheckTool toolLoading;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        toolWithProject = new HealthCheckTool(() -> true, () -> 56,
            () -> ProjectLoadingState.LOADED, () -> null, () -> null);
        toolWithoutProject = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.NOT_LOADED, () -> null, () -> null);
        toolLoading = new HealthCheckTool(() -> false, () -> 56,
            () -> ProjectLoadingState.LOADING, () -> null, () -> null);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("returns complete health status when project loaded")
    void returnsCompleteHealthStatusWithProject() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Status
        assertEquals("Ready", data.get("status"));
        String message = (String) data.get("message");
        assertNotNull(message, "message missing");
        assertFalse(message.isBlank(), "message non-blank; got: " + data);
        // version is the server version reported by the protocol handler.
        assertEquals(org.javalens.mcp.protocol.McpProtocolHandler.serverVersion(), data.get("version"));
        assertNotNull(data.get("uptime"), "uptime missing");

        // Project info: no service is wired, so there is no build model or warnings to report.
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertEquals(Map.of("status", "loaded", "loaded", true, "message", "Project loaded successfully"),
            project);

        // Java/OS info — both maps must be present
        @SuppressWarnings("unchecked")
        Map<String, Object> java = (Map<String, Object>) data.get("java");
        assertNotNull(java, "java block missing");
        assertFalse(java.isEmpty(), "java block non-empty; got: " + data);
        @SuppressWarnings("unchecked")
        Map<String, Object> os = (Map<String, Object>) data.get("os");
        assertNotNull(os, "os block missing");
        assertFalse(os.isEmpty(), "os block non-empty; got: " + data);

        // Capabilities
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) data.get("capabilities");
        assertTrue((Boolean) capabilities.get("findReferences"));
        assertTrue((Boolean) capabilities.get("refactoring"));

        // Tool count
        assertEquals(56, data.get("toolCount"));
    }

    @Test @DisplayName("returns waiting status when no project loaded")
    void returnsWaitingStatusWithoutProject() {
        ToolResponse r = toolWithoutProject.execute(objectMapper.createObjectNode());

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Waiting for project", data.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertFalse((Boolean) project.get("loaded"));
    }

    @Test @DisplayName("always succeeds with no parameters")
    void alwaysSucceedsWithNoParameters() {
        assertTrue(toolWithProject.execute(null).isSuccess());
        assertTrue(toolWithoutProject.execute(objectMapper.createObjectNode()).isSuccess());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("LOADING state reports status='Loading project...' and project.loaded=false")
    @SuppressWarnings("unchecked")
    void loadingState_reportsLoadingStatus() {
        ToolResponse r = toolLoading.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertEquals("Loading project...", data.get("status"));
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertEquals(false, project.get("loaded"),
            "During LOADING, project.loaded must be false; got: " + project);
        assertEquals("loading", project.get("status"),
            "Inner project.status must be lowercase enum name; got: " + project);
        assertNotNull(project.get("message"),
            "Loading state must carry an explanatory message");
    }

    @Test
    @DisplayName("FAILED state reports status='Project load failed' and embeds error message")
    @SuppressWarnings("unchecked")
    void failedState_embedsLoadErrorInProjectMessage() {
        // Construct a tool fixed to FAILED with a known error message; verify the error
        // text is surfaced verbatim in project.message.
        HealthCheckTool failedTool = new HealthCheckTool(
            () -> false, () -> 56,
            () -> ProjectLoadingState.FAILED,
            () -> "Maven subprocess exited with code 1", () -> null);

        ToolResponse r = failedTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertEquals("Project load failed", data.get("status"));
        Map<String, Object> project = (Map<String, Object>) data.get("project");
        assertEquals(false, project.get("loaded"));
        assertEquals("failed", project.get("status"));
        assertTrue(project.get("message").toString().contains("Maven subprocess exited with code 1"),
            "FAILED message must include the error text from the supplier; got: " + project);
    }

    @Test
    @DisplayName("project.status field reports the lowercase enum name for each loading state")
    @SuppressWarnings("unchecked")
    void projectStatusField_isLowercaseEnumName() {
        // NOT_LOADED → "not_loaded"
        Map<String, Object> notLoadedProject = (Map<String, Object>) getData(
            toolWithoutProject.execute(objectMapper.createObjectNode())).get("project");
        assertEquals("not_loaded", notLoadedProject.get("status"));

        // LOADED → "loaded"
        Map<String, Object> loadedProject = (Map<String, Object>) getData(
            toolWithProject.execute(objectMapper.createObjectNode())).get("project");
        assertEquals("loaded", loadedProject.get("status"));
    }

    @Test
    @DisplayName("capabilities map carries all five documented feature flags set to true")
    @SuppressWarnings("unchecked")
    void capabilities_carriesAllFiveDocumentedFlags() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> capabilities =
            (Map<String, Object>) getData(r).get("capabilities");
        assertNotNull(capabilities);

        // All five must be present AND true. A regression that flipped any to false
        // or dropped a key would mislead the AI agent about available features.
        assertEquals(Boolean.TRUE, capabilities.get("findReferences"));
        assertEquals(Boolean.TRUE, capabilities.get("findImplementations"));
        assertEquals(Boolean.TRUE, capabilities.get("typeHierarchy"));
        assertEquals(Boolean.TRUE, capabilities.get("refactoring"));
        assertEquals(Boolean.TRUE, capabilities.get("diagnostics"));
    }

    @Test
    @DisplayName("with no service, configuration reports the limit the environment sets: unset in the build, so timeoutSeconds null, and absolutePaths false")
    @SuppressWarnings("unchecked")
    void configuration_noService_reportsEnvironmentLimit() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("timeoutSeconds", null);
        expected.put("absolutePaths", false);
        Map<String, Object> configuration = (Map<String, Object>) getData(r).get("configuration");
        assertAll(
            () -> assertEquals(expected, configuration),
            () -> assertEquals(List.of("timeoutSeconds", "absolutePaths"), List.copyOf(configuration.keySet())));
    }

    @Test
    @DisplayName("with a service, configuration reports the service's limit: 30 when it sets 30, null when it sets none")
    @SuppressWarnings("unchecked")
    void configuration_withService_reportsServiceLimit() {
        Path root = Path.of("limit-root").toAbsolutePath().normalize();
        BuildModel model = new BuildModel(BuildSystem.MAVEN, Provenance.STATIC_READ, List.of(module(".", root)),
            List.of(), List.of(), "21", new BuildInputs(List.of(), List.of()), List.of());
        Map<String, Object> limited = new java.util.LinkedHashMap<>();
        limited.put("timeoutSeconds", 30);
        limited.put("absolutePaths", false);
        Map<String, Object> unlimited = new java.util.LinkedHashMap<>();
        unlimited.put("timeoutSeconds", null);
        unlimited.put("absolutePaths", false);

        ToolResponse thirty = new HealthCheckTool(
            () -> new LoadedModelService(model, "21", new PathUtilsImpl(root), List.of(), 30))
            .execute(objectMapper.createObjectNode());
        ToolResponse none = new HealthCheckTool(
            () -> new LoadedModelService(model, "21", new PathUtilsImpl(root), List.of(), null))
            .execute(objectMapper.createObjectNode());
        assertAll(
            () -> assertEquals(limited, getData(thirty).get("configuration")),
            () -> assertEquals(unlimited, getData(none).get("configuration")));
    }

    @Test
    @DisplayName("diskSync reports the env default 'strict' when no service is wired")
    void diskSync_reportsStrictDefault() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        // service supplier returns null here, so diskSync falls back to the
        // JAVALENS_DISK_SYNC env default, which is "strict" (#26 ships strict-default).
        assertEquals("strict", getData(r).get("diskSync"));
    }

    @Test
    @DisplayName("response includes startedAt timestamp and java/os info maps")
    @SuppressWarnings("unchecked")
    void response_includesStartedAtAndJavaOsInfo() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        assertNotNull(data.get("startedAt"),
            "startedAt timestamp must be present");

        Map<String, Object> java = (Map<String, Object>) data.get("java");
        assertNotNull(java.get("version"),
            "java.version must be reported; got: " + java);
        assertNotNull(java.get("vendor"),
            "java.vendor must be reported; got: " + java);

        Map<String, Object> os = (Map<String, Object>) data.get("os");
        assertNotNull(os.get("name"),
            "os.name must be reported; got: " + os);
        assertNotNull(os.get("arch"),
            "os.arch must be reported; got: " + os);
    }

    // ========== Load report of a loaded project ==========

    @Test
    @DisplayName("LOADED with a service: project carries its buildModel (applied level, first 20 of 21 modules, root as '.') and its warnings, after the status fields")
    @SuppressWarnings("unchecked")
    void loadedWithService_reportsBuildModelAndWarnings() {
        // The model declares 17; the service applied 21. Of 21 modules only the first 20
        // are listed, while moduleCount gives all 21.
        Path root = Path.of("loaded-model-root").toAbsolutePath().normalize();
        List<BuildModule> modules = new ArrayList<>();
        modules.add(module(".", root));
        List<Map<String, Object>> expectedModules = new ArrayList<>();
        expectedModules.add(Map.of("id", ".", "dir", "."));
        for (int i = 1; i <= 20; i++) {
            String name = String.format("pkg%02d", i);
            modules.add(module(name, root.resolve(name)));
            if (i <= 19) {
                expectedModules.add(Map.of("id", name, "dir", name));
            }
        }
        BuildModel model = new BuildModel(BuildSystem.BAZEL, Provenance.BUILD_TOOL, modules,
            List.of(), List.of(), "17", new BuildInputs(List.of(), List.of()), List.of());
        List<LoadWarning> warnings = List.of(
            new LoadWarning(LoadWarning.BAZEL_NOT_BUILT, "bazel-bin holds no jars",
                "Run 'bazel build //...'", "pkg01"),
            new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_UNKNOWN, "no level declared", null, null));
        LoadedModelService service = new LoadedModelService(model, "21", new PathUtilsImpl(root), warnings);

        ToolResponse r = new HealthCheckTool(() -> service).execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> project = (Map<String, Object>) getData(r).get("project");

        Map<String, Object> expectedBuildModel = Map.of(
            "buildSystem", "bazel",
            "source", "build-tool",
            "analysisLevel", "21",
            "moduleCount", 21,
            "modules", expectedModules);
        // A warning's remediation and module appear only when set.
        List<Map<String, Object>> expectedWarnings = List.of(
            Map.of("code", LoadWarning.BAZEL_NOT_BUILT, "message", "bazel-bin holds no jars",
                "remediation", "Run 'bazel build //...'", "module", "pkg01"),
            Map.of("code", LoadWarning.COMPLIANCE_LEVEL_UNKNOWN, "message", "no level declared"));
        assertAll(
            () -> assertEquals(Map.of(
                    "status", "loaded",
                    "loaded", true,
                    "message", "Project loaded successfully",
                    "buildModel", expectedBuildModel,
                    "warnings", expectedWarnings),
                project, "project"),
            () -> assertEquals(List.of("status", "loaded", "message", "buildModel", "warnings"),
                List.copyOf(project.keySet()), "project fields in order"),
            () -> assertEquals(List.of("buildSystem", "source", "analysisLevel", "moduleCount", "modules"),
                project.get("buildModel") instanceof Map<?, ?> buildModel ? List.copyOf(buildModel.keySet()) : null,
                "buildModel fields in order"),
            () -> assertEquals(List.of("code", "message", "remediation", "module"),
                project.get("warnings") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first
                    ? List.copyOf(first.keySet()) : null,
                "first warning's fields in order"));
    }

    @Test
    @DisplayName("A state other than LOADED reports no buildModel or warnings even when a service is present")
    void notLoadedState_withService_reportsNoLoadReport() {
        Path root = Path.of("loading-model-root").toAbsolutePath().normalize();
        BuildModel model = new BuildModel(BuildSystem.MAVEN, Provenance.STATIC_READ, List.of(module(".", root)),
            List.of(), List.of(), "21", new BuildInputs(List.of(), List.of()), List.of());
        LoadedModelService service = new LoadedModelService(model, "21", new PathUtilsImpl(root),
            List.of(new LoadWarning(LoadWarning.BAZEL_NOT_BUILT, "bazel-bin holds no jars", null)));
        HealthCheckTool loading = new HealthCheckTool(() -> true, () -> 56,
            () -> ProjectLoadingState.LOADING, () -> null, () -> service);

        ToolResponse r = loading.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        assertEquals(Map.of("status", "loading", "loaded", false, "message", "Project is loading, please wait..."),
            getData(r).get("project"));
    }

    @Test
    @DisplayName("uptime is a non-empty string in seconds/minutes/hours format")
    void uptime_isFormattedString() {
        ToolResponse r = toolWithProject.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        String uptime = (String) getData(r).get("uptime");
        assertNotNull(uptime);
        assertTrue(uptime.endsWith("seconds") || uptime.endsWith("minutes") || uptime.endsWith("hours"),
            "uptime must end with the bucket unit (seconds/minutes/hours); got: " + uptime);
    }

    // ========== Helpers ==========

    private static BuildModule module(String id, Path dir) {
        return new BuildModule(id, dir, List.of(), List.of(), List.of(), null, false);
    }

    /**
     * A service that has loaded a project and answers only what health_check reports about
     * the load; any other call fails the test.
     */
    private static final class LoadedModelService implements IJdtService {
        private final BuildModel model;
        private final String analysisLevel;
        private final IPathUtils pathUtils;
        private final List<LoadWarning> warnings;
        private final Integer timeoutSeconds;

        LoadedModelService(BuildModel model, String analysisLevel, IPathUtils pathUtils, List<LoadWarning> warnings) {
            this(model, analysisLevel, pathUtils, warnings, null);
        }

        LoadedModelService(BuildModel model, String analysisLevel, IPathUtils pathUtils, List<LoadWarning> warnings,
                           Integer timeoutSeconds) {
            this.model = model;
            this.analysisLevel = analysisLevel;
            this.pathUtils = pathUtils;
            this.warnings = warnings;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override public BuildModel getBuildModel() { return model; }
        @Override public String getAnalysisLevel() { return analysisLevel; }
        @Override public IPathUtils getPathUtils() { return pathUtils; }
        @Override public List<LoadWarning> getWarnings() { return warnings; }
        @Override public DiskSyncMode getDiskSyncMode() { return DiskSyncMode.STRICT; }

        @Override public Path getProjectRoot() { throw unexpected(); }
        @Override public Integer getTimeoutSeconds() { return timeoutSeconds; }
        @Override public Deadline newDeadline() { throw unexpected(); }
        @Override public IJavaProject getJavaProject() { throw unexpected(); }
        @Override public SearchService getSearchService() { throw unexpected(); }
        @Override public ProjectGraphService getProjectGraphService() { throw unexpected(); }
        @Override public List<Path> ensureFresh() { throw unexpected(); }
        @Override public ICompilationUnit getCompilationUnit(Path p) { throw unexpected(); }
        @Override public IJavaElement getElementAtPosition(Path p, int l, int c) { throw unexpected(); }
        @Override public IType getTypeAtPosition(Path p, int l, int c) { throw unexpected(); }
        @Override public IType findType(String n) { throw unexpected(); }
        @Override public String getContextLine(ICompilationUnit cu, int o) { throw unexpected(); }
        @Override public int getOffset(ICompilationUnit cu, int l, int c) { throw unexpected(); }
        @Override public int getLineNumber(ICompilationUnit cu, int o) { throw unexpected(); }
        @Override public int getColumnNumber(ICompilationUnit cu, int o) { throw unexpected(); }
        @Override public List<Path> getAllJavaFiles() { throw unexpected(); }

        private static UnsupportedOperationException unexpected() {
            return new UnsupportedOperationException("health_check does not read this from the service");
        }
    }
}
