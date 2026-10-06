package org.javalens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.workspace.WorkspaceManager;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The most recently requested load is the one {@link JavaLensApplication} installs: the
 * JAVA_PROJECT_PATH auto-load, requested when the application starts, never replaces a
 * load requested after it through load_project.
 */
class LoadRequestOrderTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JavaLensApplication app;
    private ToolRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        app = new JavaLensApplication();
        Field registryField = JavaLensApplication.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        registry = new ToolRegistry();
        registryField.set(app, registry);
        Method registerTools = JavaLensApplication.class.getDeclaredMethod("registerTools");
        registerTools.setAccessible(true);
        registerTools.invoke(app);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("an auto-load that is still the latest request installs its service and reports loaded")
    void autoLoad_latestRequest_installsItsService() throws Exception {
        long request = app.requestLoad();
        JdtServiceImpl service = new JdtServiceImpl();

        app.autoLoad(service, helper.getFixturePath("index-fidelity"), request);

        assertAll(
            () -> assertSame(service, jdtService()),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertEquals(List.of(service.getJavaProject().getElementName()), javaLensProjects()));
    }

    @Test
    @DisplayName("an auto-load superseded by a load_project request before its turn neither loads nor replaces the load_project service")
    void autoLoad_supersededBeforeItsTurn_leavesLoadProjectService() throws Exception {
        long autoLoadRequest = app.requestLoad();
        ToolResponse load = loadProject(helper.getFixturePath("plain-java"));
        assertTrue(load.isSuccess(), () -> "precondition: load_project succeeds; got " + load.getError());
        IJdtService installed = jdtService();
        String installedProject = installed.getJavaProject().getElementName();
        JdtServiceImpl autoLoad = new JdtServiceImpl();

        app.autoLoad(autoLoad, helper.getFixturePath("index-fidelity"), autoLoadRequest);

        assertAll(
            () -> assertSame(installed, jdtService(), "the load_project service must stay installed"),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertNull(autoLoad.getJavaProject(), "the superseded auto-load must not load"),
            () -> assertEquals(List.of(installedProject), javaLensProjects()));
    }

    @Test
    @DisplayName("an auto-load that completes after a later load request is not installed, and the later load_project owns the only project")
    void autoLoad_completingAfterLaterRequest_isNotInstalled() throws Exception {
        long autoLoadRequest = app.requestLoad();
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdtServiceImpl autoLoad = new JdtServiceImpl(heldImporter(resolving, release, null));
        Path autoLoadRoot = helper.getFixturePath("index-fidelity");
        Thread autoLoadThread = new Thread(() -> app.autoLoad(autoLoad, autoLoadRoot, autoLoadRequest), "auto-load");
        autoLoadThread.start();
        assertTrue(resolving.await(60, TimeUnit.SECONDS), "the auto-load must reach model resolution");

        app.requestLoad(); // a later request, as load_project registers one before it loads
        release.countDown();
        autoLoadThread.join(TimeUnit.MINUTES.toMillis(2));
        IJdtService afterAutoLoad = jdtService();
        ProjectLoadingState stateAfterAutoLoad = loadingState();
        ToolResponse load = loadProject(helper.getFixturePath("plain-java"));
        IJdtService installed = jdtService();

        assertAll(
            () -> assertNull(afterAutoLoad, "the auto-load's service must not be installed"),
            () -> assertEquals(ProjectLoadingState.LOADING, stateAfterAutoLoad,
                "a later load is still pending when the auto-load completes"),
            () -> assertTrue(load.isSuccess(), () -> "load_project must succeed; got " + load.getError()),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertEquals(List.of(installed.getJavaProject().getElementName()), javaLensProjects()),
            () -> assertFalse(autoLoad.getJavaProject().getProject().exists(),
                "the auto-load's project must be gone"));
    }

    @Test
    @DisplayName("an auto-load that fails while it is the latest request reports FAILED with its message")
    void autoLoad_failingLatestRequest_reportsFailed() throws Exception {
        long request = app.requestLoad();
        JdtServiceImpl failing = new JdtServiceImpl(heldImporter(new CountDownLatch(1), null, "model unavailable"));

        app.autoLoad(failing, helper.getFixturePath("index-fidelity"), request);

        assertAll(
            () -> assertNull(jdtService()),
            () -> assertEquals(ProjectLoadingState.FAILED, loadingState()),
            () -> assertEquals("model unavailable", loadingError()));
    }

    @Test
    @DisplayName("an auto-load that fails after a later load request does not report FAILED")
    void autoLoad_failingAfterLaterRequest_doesNotReportFailed() throws Exception {
        long autoLoadRequest = app.requestLoad();
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdtServiceImpl failing = new JdtServiceImpl(heldImporter(resolving, release, "model unavailable"));
        Path root = helper.getFixturePath("index-fidelity");
        Thread autoLoadThread = new Thread(() -> app.autoLoad(failing, root, autoLoadRequest), "auto-load");
        autoLoadThread.start();
        assertTrue(resolving.await(60, TimeUnit.SECONDS), "the auto-load must reach model resolution");

        app.requestLoad(); // a later request, as load_project registers one before it loads
        release.countDown();
        autoLoadThread.join(TimeUnit.MINUTES.toMillis(2));

        assertAll(
            () -> assertNull(jdtService()),
            () -> assertEquals(ProjectLoadingState.LOADING, loadingState(),
                "a later load is still pending when the auto-load fails"),
            () -> assertNull(loadingError()));
    }

    @Test
    @DisplayName("a load_project that supersedes a running auto-load and then fails reports FAILED with its error and installs no service")
    void loadProject_supersedingAutoLoadThenFailing_reportsFailed() throws Exception {
        long autoLoadRequest = app.requestLoad();
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdtServiceImpl autoLoad = new JdtServiceImpl(heldImporter(resolving, release, null));
        Path autoLoadRoot = helper.getFixturePath("index-fidelity");
        Thread autoLoadThread = new Thread(() -> app.autoLoad(autoLoad, autoLoadRoot, autoLoadRequest), "auto-load");
        autoLoadThread.start();
        assertTrue(resolving.await(60, TimeUnit.SECONDS), "the auto-load must reach model resolution");

        // load_project asks for its service right after registering its request.
        CountDownLatch loadProjectRequested = new CountDownLatch(1);
        app.loadProjectServices = () -> {
            loadProjectRequested.countDown();
            return new JdtServiceImpl(heldImporter(new CountDownLatch(1), null, "model unavailable"));
        };
        AtomicReference<ToolResponse> response = new AtomicReference<>();
        Path loadProjectRoot = helper.getFixturePath("plain-java");
        Thread loadProjectThread = new Thread(() -> response.set(loadProject(loadProjectRoot)), "load-project");
        loadProjectThread.start();
        assertTrue(loadProjectRequested.await(60, TimeUnit.SECONDS), "load_project must register its request");
        release.countDown();
        autoLoadThread.join(TimeUnit.MINUTES.toMillis(2));
        loadProjectThread.join(TimeUnit.MINUTES.toMillis(2));

        assertAll(
            () -> assertFalse(response.get().isSuccess(), "load_project must report its failure"),
            () -> assertEquals(ErrorInfo.INTERNAL_ERROR, response.get().getError().getCode()),
            () -> assertEquals("Internal error: model unavailable", response.get().getError().getMessage()),
            () -> assertEquals(ProjectLoadingState.FAILED, loadingState()),
            () -> assertEquals("model unavailable", loadingError()),
            () -> assertNull(jdtService(), "no service may be installed"),
            () -> assertEquals(List.of(autoLoad.getJavaProject().getElementName()), javaLensProjects(),
                "the completed auto-load's project is the only one"));
    }

    @Test
    @DisplayName("a failed load_project followed by a successful one installs the new service and reports LOADED")
    void loadProject_failedThenSucceeding_installsAndReportsLoaded() throws Exception {
        app.loadProjectServices = () -> new JdtServiceImpl(heldImporter(new CountDownLatch(1), null, "model unavailable"));
        ToolResponse failed = loadProject(helper.getFixturePath("plain-java"));
        ProjectLoadingState stateAfterFailure = loadingState();
        String errorAfterFailure = loadingError();
        app.loadProjectServices = JdtServiceImpl::new;

        ToolResponse loaded = loadProject(helper.getFixturePath("index-fidelity"));
        IJdtService installed = jdtService();

        assertAll(
            () -> assertFalse(failed.isSuccess()),
            () -> assertEquals(ProjectLoadingState.FAILED, stateAfterFailure),
            () -> assertEquals("model unavailable", errorAfterFailure),
            () -> assertTrue(loaded.isSuccess(), () -> "the second load_project must succeed; got " + loaded.getError()),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertNull(loadingError()),
            () -> assertEquals(List.of(installed.getJavaProject().getElementName()), javaLensProjects()));
    }

    @Test
    @DisplayName("a load_project that fails while an earlier load's service is installed keeps that service and LOADED")
    void loadProject_failingWhileServiceInstalled_keepsInstalledService() throws Exception {
        ToolResponse first = loadProject(helper.getFixturePath("index-fidelity"));
        assertTrue(first.isSuccess(), () -> "precondition: the first load_project succeeds; got " + first.getError());
        IJdtService installed = jdtService();
        app.loadProjectServices = () -> new JdtServiceImpl(heldImporter(new CountDownLatch(1), null, "model unavailable"));

        ToolResponse failed = loadProject(helper.getFixturePath("plain-java"));

        assertAll(
            () -> assertFalse(failed.isSuccess()),
            () -> assertEquals("Internal error: model unavailable", failed.getError().getMessage()),
            () -> assertSame(installed, jdtService(), "the earlier service must stay installed"),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertNull(loadingError()),
            () -> assertEquals(List.of(installed.getJavaProject().getElementName()), javaLensProjects()));
    }

    @Test
    @DisplayName("a load_project that completes but cannot remove the earlier project reports EARLIER_PROJECT_KEPT in its response and in health_check")
    void loadProject_earlierProjectKept_reportedByLoadAndHealthCheck() throws Exception {
        ToolResponse first = loadProject(helper.getFixturePath("plain-java"));
        assertTrue(first.isSuccess(), () -> "precondition: the first load_project succeeds; got " + first.getError());
        String earlierName = jdtService().getJavaProject().getElementName();
        app.loadProjectServices = () -> new JdtServiceImpl(new ProjectImporter(), refusingToRemove(earlierName));

        ToolResponse second = loadProject(helper.getFixturePath("index-fidelity"));
        ToolResponse health = registry.getTool("health_check").orElseThrow().execute(objectMapper.createObjectNode());

        IJdtService installed = jdtService();
        Map<String, Object> expectedWarning = Map.of(
            "code", "EARLIER_PROJECT_KEPT",
            "message", "The earlier JavaLens project " + earlierName + " could not be removed: "
                + "a file in its directory is locked; it stays in the workspace until the next load removes it",
            "remediation", "Reload the project; if the warning persists, restart JavaLens.");
        Map<?, ?> healthProject = (Map<?, ?>) data(health).get("project");
        assertAll(
            () -> assertTrue(second.isSuccess(), () -> "the load completed and must succeed; got " + second.getError()),
            () -> assertEquals(List.of(expectedWarning), warnings(data(second))),
            () -> assertEquals(List.of(List.of("code", "message", "remediation")),
                warnings(data(second)).stream().map(entry -> List.copyOf(((Map<?, ?>) entry).keySet())).toList(),
                "warning fields in order"),
            () -> assertEquals(List.of(expectedWarning), warnings(healthProject)),
            () -> assertEquals(ProjectLoadingState.LOADED, loadingState()),
            () -> assertEquals(Stream.of(earlierName, installed.getJavaProject().getElementName()).sorted().toList(),
                javaLensProjects()));
    }

    // ========== Helpers ==========

    /** A workspace manager that fails to remove the project named {@code projectName}. */
    private static WorkspaceManager refusingToRemove(String projectName) {
        return new WorkspaceManager() {
            @Override
            public boolean removeProject(IProject project) throws CoreException {
                if (project.getName().equals(projectName)) {
                    throw new CoreException(new Status(IStatus.ERROR, "org.javalens.mcp.tests",
                        "a file in its directory is locked"));
                }
                return super.removeProject(project);
            }
        };
    }

    private static Map<?, ?> data(ToolResponse response) {
        return (Map<?, ?>) response.getData();
    }

    /** The {@code warnings} list of a response's data, or an empty list when there is none. */
    private static List<?> warnings(Map<?, ?> data) {
        return data == null || data.get("warnings") == null ? List.of() : (List<?>) data.get("warnings");
    }

    /**
     * An importer that signals {@code resolving} when model resolution starts, waits for
     * {@code release} when given, then fails with {@code failure} when given.
     */
    private static ProjectImporter heldImporter(CountDownLatch resolving, CountDownLatch release, String failure) {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                resolving.countDown();
                if (release != null) {
                    try {
                        if (!release.await(2, TimeUnit.MINUTES)) {
                            throw new IllegalStateException("the auto-load was never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("the auto-load was interrupted", e);
                    }
                }
                if (failure != null) {
                    throw new IllegalStateException(failure);
                }
                return super.resolveModel(projectPath);
            }
        };
    }

    private ToolResponse loadProject(Path root) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectPath", root.toString());
        return registry.getTool("load_project").orElseThrow().execute(args);
    }

    private IJdtService jdtService() throws Exception {
        return (IJdtService) field("jdtService").get(app);
    }

    private ProjectLoadingState loadingState() throws Exception {
        return (ProjectLoadingState) field("loadingState").get(app);
    }

    private String loadingError() throws Exception {
        return (String) field("loadingError").get(app);
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = JavaLensApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Names of the workspace projects created by JavaLens loads, sorted. */
    private static List<String> javaLensProjects() {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .map(IProject::getName)
            .filter(name -> name.startsWith("javalens-"))
            .sorted()
            .toList();
    }
}
