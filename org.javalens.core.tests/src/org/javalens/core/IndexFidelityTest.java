package org.javalens.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.launching.JavaRuntime;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search index of a loaded project holds every type its compilation units declare,
 * however the project is loaded or reloaded.
 */
class IndexFidelityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final Set<String> INDEX_FIDELITY_TYPES = Set.of(
        "org.example.indexing.Builder",
        "org.example.indexing.Collector",
        "org.example.indexing.Graph",
        "org.example.indexing.Graph.Kind",
        "org.example.indexing.Graph.Node");

    /** Every reference to Graph.Node in the index-fidelity fixture, as file:line (0-based). */
    private static final List<String> NODE_REFERENCES = List.of(
        "Builder.java:17",
        "Collector.java:3",
        "Collector.java:17",
        "Collector.java:20",
        "Collector.java:27",
        "Collector.java:36",
        "Graph.java:15",
        "Graph.java:17",
        "Graph.java:21");

    /** More files than JDT's in-memory index holds before a query merges it to disk. */
    private static final int ABOVE_MERGE_THRESHOLD = 120;

    /** Fewer files than JDT's in-memory index holds before a query merges it to disk. */
    private static final int BELOW_MERGE_THRESHOLD = 60;

    private static final String LINKED_SOURCE_FOLDER = "src-0-src-main-java";

    // ========== Load ==========

    @Test
    @DisplayName("a load indexes every declared type and every reference to the nested record")
    void load_indexHoldsEveryDeclaredType() throws Exception {
        JdtServiceImpl service = helper.loadProject("index-fidelity");
        IJavaProject project = service.getJavaProject();

        assertEquals(INDEX_FIDELITY_TYPES, declaredTypes(project));
        assertEquals(INDEX_FIDELITY_TYPES, indexedTypes(project));
        assertEquals(NODE_REFERENCES, references(project, "org.example.indexing.Graph.Node"));
    }

    // ========== Indexing interleaved with configuration ==========

    @Test
    @DisplayName("sources indexed before the configured classpath is set stay fully indexed with more than a hundred files and a lambda-bearing file")
    void indexedBeforeClasspath_aboveMergeThreshold_fullyIndexed() throws Exception {
        Path root = writeOrderedFixture("above-threshold", ABOVE_MERGE_THRESHOLD);
        IJavaProject project = linkIndexThenConfigure(root);

        Set<String> expected = orderedFixtureTypes(ABOVE_MERGE_THRESHOLD);
        assertEquals(expected, declaredTypes(project));
        assertEquals(expected, indexedTypes(project));
    }

    @Test
    @DisplayName("sources indexed before the configured classpath is set stay fully indexed with fewer than a hundred files and a lambda-bearing file")
    void indexedBeforeClasspath_belowMergeThreshold_fullyIndexed() throws Exception {
        Path root = writeOrderedFixture("below-threshold", BELOW_MERGE_THRESHOLD);
        IJavaProject project = linkIndexThenConfigure(root);

        Set<String> expected = orderedFixtureTypes(BELOW_MERGE_THRESHOLD);
        assertEquals(expected, declaredTypes(project));
        assertEquals(expected, indexedTypes(project));
    }

    // ========== Reload ==========

    @Test
    @DisplayName("a reload of the same root leaves one JavaLens project whose index holds each type exactly once")
    void reloadSameRoot_oneProjectAndCompleteIndex() throws Exception {
        Path root = helper.getFixturePath("index-fidelity");
        JdtServiceImpl first = new JdtServiceImpl();
        first.loadProject(root);
        String firstName = first.getJavaProject().getElementName();
        JdtServiceImpl second = new JdtServiceImpl();
        second.loadProject(root);
        IJavaProject project = second.getJavaProject();

        assertAll(
            () -> assertEquals(List.of(project.getElementName()), javaLensProjects()),
            () -> assertFalse(workspaceProject(firstName).exists(),
                "the earlier load's project " + firstName + " must be gone"),
            () -> assertEquals(INDEX_FIDELITY_TYPES, indexedTypes(project)),
            () -> assertEquals(INDEX_FIDELITY_TYPES.stream().sorted().toList(),
                workspaceTypeOccurrences("org.example.indexing")),
            () -> assertEquals(NODE_REFERENCES, references(project, "org.example.indexing.Graph.Node")));
    }

    @Test
    @DisplayName("a reload of a different root removes the earlier load's project")
    void reloadDifferentRoot_removesEarlierProject() throws Exception {
        JdtServiceImpl first = new JdtServiceImpl();
        first.loadProject(helper.getFixturePath("index-fidelity"));
        String firstName = first.getJavaProject().getElementName();
        JdtServiceImpl second = new JdtServiceImpl();
        second.loadProject(helper.getFixturePath("plain-java"));

        assertAll(
            () -> assertEquals(List.of(second.getJavaProject().getElementName()), javaLensProjects()),
            () -> assertFalse(workspaceProject(firstName).exists(),
                "the earlier load's project " + firstName + " must be gone"),
            () -> assertEquals(List.of(), workspaceTypeOccurrences("org.example.indexing")));
    }

    @Test
    @DisplayName("a reload that fails in model resolution leaves the earlier project and its index as they were, and no new project")
    void reloadFailingInModelResolution_keepsEarlierLoad() throws Exception {
        ProjectImporter failing = new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                throw new IllegalStateException("model unavailable");
            }
        };

        assertFailedReloadKeepsEarlierLoad(failing, IllegalStateException.class, "model unavailable");
    }

    @Test
    @DisplayName("a reload that fails in configuration, after its project is created and linked, leaves the earlier project and its index as they were, and no new project")
    void reloadFailingInConfiguration_keepsEarlierLoad() throws Exception {
        ProjectImporter failing = new ProjectImporter() {
            @Override
            public IJavaProject configureJavaProject(IProject project, Path projectPath, BuildModel model,
                                                     WorkspaceManager workspaceManager) throws CoreException {
                super.configureJavaProject(project, projectPath, model, workspaceManager);
                throw new CoreException(new Status(IStatus.ERROR, "org.javalens.core.tests", "configuration unavailable"));
            }
        };

        assertFailedReloadKeepsEarlierLoad(failing, CoreException.class, "configuration unavailable");
    }

    @Test
    @DisplayName("a reload that completes but cannot remove the earlier project is in use, keeps both projects and warns EARLIER_PROJECT_KEPT after the model's warnings; the next load removes it")
    void reloadUnableToRemoveEarlierProject_warnsAndNextLoadRemovesIt() throws Exception {
        Path root = helper.getFixturePath("index-fidelity");
        JdtServiceImpl earlier = new JdtServiceImpl();
        earlier.loadProject(root);
        String earlierName = earlier.getJavaProject().getElementName();
        JdtServiceImpl reload = new JdtServiceImpl(addingModelWarning(), refusingToRemove(earlierName));

        reload.loadProject(root);

        String reloadName = reload.getJavaProject().getElementName();
        List<LoadWarning> reloadWarnings = reload.getWarnings();
        List<String> projectsAfterReload = javaLensProjects();
        Set<String> reloadIndexedTypes = indexedTypes(reload.getJavaProject());
        JdtServiceImpl next = new JdtServiceImpl();
        next.loadProject(root);

        assertAll(
            () -> assertEquals(List.of(MODEL_WARNING, new LoadWarning(LoadWarning.EARLIER_PROJECT_KEPT,
                    "The earlier JavaLens project " + earlierName + " could not be removed: " + REMOVAL_FAILURE
                        + "; it stays in the workspace until the next load removes it",
                    "Reload the project; if the warning persists, restart JavaLens.")),
                reloadWarnings),
            () -> assertEquals(Stream.of(earlierName, reloadName).sorted().toList(), projectsAfterReload),
            () -> assertEquals(INDEX_FIDELITY_TYPES, reloadIndexedTypes),
            () -> assertEquals(List.of(next.getJavaProject().getElementName()), javaLensProjects()),
            () -> assertEquals(List.of(), next.getWarnings()));
    }

    @Test
    @DisplayName("a load started while another is in progress runs after it, and the later load's project is the only one left")
    void overlappingLoads_runOneAfterTheOther() throws Exception {
        Path root = helper.getFixturePath("index-fidelity");
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstResolving = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondResolving = new CountDownLatch(1);
        AtomicReference<Instant> firstLoadedAtSecondStart = new AtomicReference<>();
        JdtServiceImpl first = new JdtServiceImpl(heldImporter(firstResolving, releaseFirst, () -> { }));
        JdtServiceImpl second = new JdtServiceImpl(heldImporter(secondResolving, null,
            () -> firstLoadedAtSecondStart.set(first.getLoadedAt())));
        Thread firstLoad = loadThread("first", first, root, outcomes);
        Thread secondLoad = loadThread("second", second, root, outcomes);

        firstLoad.start();
        assertTrue(firstResolving.await(60, TimeUnit.SECONDS), "the first load must reach model resolution");
        secondLoad.start();
        // While the first load is held inside its turn, the second must not begin its own.
        boolean secondStartedDuringFirst = secondResolving.await(2, TimeUnit.SECONDS);
        releaseFirst.countDown();
        firstLoad.join(TimeUnit.MINUTES.toMillis(2));
        secondLoad.join(TimeUnit.MINUTES.toMillis(2));

        assertAll(
            () -> assertFalse(secondStartedDuringFirst, "the second load must wait for the first to finish"),
            () -> assertEquals(first.getLoadedAt(), firstLoadedAtSecondStart.get(),
                "the first load must have completed when the second begins"),
            () -> assertEquals(List.of("first loaded", "second loaded"), outcomes.stream().sorted().toList()),
            () -> assertEquals(List.of(second.getJavaProject().getElementName()), javaLensProjects()),
            () -> assertFalse(first.getJavaProject().getProject().exists(), "the first load's project must be gone"),
            () -> assertEquals(INDEX_FIDELITY_TYPES, indexedTypes(second.getJavaProject())));
    }

    @Test
    @DisplayName("a load that is no longer requested when its turn comes leaves the workspace and the earlier load untouched")
    void loadNoLongerRequested_leavesWorkspaceUntouched() throws Exception {
        JdtServiceImpl loaded = new JdtServiceImpl();
        loaded.loadProject(helper.getFixturePath("index-fidelity"));
        JdtServiceImpl superseded = new JdtServiceImpl();

        boolean ran = superseded.loadProject(helper.getFixturePath("plain-java"), () -> false);

        assertAll(
            () -> assertFalse(ran, "a load that is no longer requested must report that it did not run"),
            () -> assertNull(superseded.getJavaProject()),
            () -> assertEquals(List.of(loaded.getJavaProject().getElementName()), javaLensProjects()),
            () -> assertEquals(INDEX_FIDELITY_TYPES, indexedTypes(loaded.getJavaProject())));
    }

    // ========== Helpers ==========

    /** The warning {@link #addingModelWarning} adds to the resolved build model. */
    private static final LoadWarning MODEL_WARNING = new LoadWarning(
        LoadWarning.MODULE_LAYOUT_ASSUMED, "model warning added by the test importer", "none");

    /** The reason {@link #refusingToRemove} gives for not removing a project. */
    private static final String REMOVAL_FAILURE = "a file in its directory is locked";

    /** An importer whose resolved build model carries {@link #MODEL_WARNING} after its own warnings. */
    private static ProjectImporter addingModelWarning() {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                BuildModel model = super.resolveModel(projectPath);
                List<LoadWarning> warnings = new ArrayList<>(model.warnings());
                warnings.add(MODEL_WARNING);
                return new BuildModel(model.buildSystem(), model.provenance(), model.modules(), model.classpath(),
                    model.processorJars(), model.analysisLevel(), model.inputs(), warnings);
            }
        };
    }

    /** A workspace manager that fails to remove the project named {@code projectName}. */
    private static WorkspaceManager refusingToRemove(String projectName) {
        return new WorkspaceManager() {
            @Override
            public boolean removeProject(IProject project) throws CoreException {
                if (project.getName().equals(projectName)) {
                    throw new CoreException(new Status(IStatus.ERROR, "org.javalens.core.tests", REMOVAL_FAILURE));
                }
                return super.removeProject(project);
            }
        };
    }

    /**
     * Load the index-fidelity fixture, then reload it through {@code failingImporter}: the
     * reload throws {@code failureType} with {@code failure}, the earlier project is still the
     * only JavaLens project, its index is complete, and the earlier service still resolves
     * every fixture type to its source file.
     */
    private void assertFailedReloadKeepsEarlierLoad(ProjectImporter failingImporter,
                                                    Class<? extends Exception> failureType,
                                                    String failure) throws Exception {
        Path root = helper.getFixturePath("index-fidelity");
        JdtServiceImpl earlier = new JdtServiceImpl();
        earlier.loadProject(root);
        String earlierName = earlier.getJavaProject().getElementName();
        JdtServiceImpl failingReload = new JdtServiceImpl(failingImporter);

        Exception thrown = assertThrows(failureType, () -> failingReload.loadProject(root));

        Path sources = root.resolve("src/main/java/org/example/indexing").toAbsolutePath().normalize();
        assertAll(
            () -> assertEquals(failure, thrown.getMessage()),
            () -> assertEquals(List.of(earlierName), javaLensProjects()),
            () -> assertEquals(INDEX_FIDELITY_TYPES, indexedTypes(earlier.getJavaProject())),
            () -> assertEquals(Map.of(
                    "org.example.indexing.Builder", sources.resolve("Builder.java"),
                    "org.example.indexing.Collector", sources.resolve("Collector.java"),
                    "org.example.indexing.Graph", sources.resolve("Graph.java"),
                    "org.example.indexing.Graph.Kind", sources.resolve("Graph.java"),
                    "org.example.indexing.Graph.Node", sources.resolve("Graph.java")),
                typeFiles(earlier, INDEX_FIDELITY_TYPES)));
    }

    /** Each type name to the source file {@code service} resolves it to, or null when unresolved. */
    private static Map<String, Path> typeFiles(JdtServiceImpl service, Set<String> typeNames) {
        Map<String, Path> files = new TreeMap<>();
        for (String typeName : typeNames) {
            IType type = service.findType(typeName);
            files.put(typeName, type == null || type.getResource() == null ? null
                : Path.of(type.getResource().getLocation().toOSString()).toAbsolutePath().normalize());
        }
        return files;
    }

    /**
     * An importer that, when model resolution starts, runs {@code onResolving}, signals
     * {@code resolving} and, given {@code release}, holds the load there until it is released.
     */
    private static ProjectImporter heldImporter(CountDownLatch resolving, CountDownLatch release,
                                                Runnable onResolving) {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                onResolving.run();
                resolving.countDown();
                if (release != null) {
                    try {
                        if (!release.await(2, TimeUnit.MINUTES)) {
                            throw new IllegalStateException("the load was never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("the load was interrupted", e);
                    }
                }
                return super.resolveModel(projectPath);
            }
        };
    }

    /** A thread that loads {@code root} and records "{@code label} loaded", or the failure. */
    private static Thread loadThread(String label, JdtServiceImpl service, Path root, List<String> outcomes) {
        return new Thread(() -> {
            try {
                service.loadProject(root);
                outcomes.add(label + " loaded");
            } catch (Exception | Error e) {
                outcomes.add(label + " failed: " + e);
            }
        }, label + "-load");
    }

    private static IProject workspaceProject(String name) {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(name);
    }

    /** Names of the workspace projects created by JavaLens loads, sorted. */
    private static List<String> javaLensProjects() {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .map(IProject::getName)
            .filter(name -> name.startsWith("javalens-"))
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Every top-level and member type the index finds in {@code packageName} across the
     * workspace, sorted, one entry per match.
     */
    private static List<String> workspaceTypeOccurrences(String packageName) throws CoreException {
        List<String> names = typeNames(packageName, SearchEngine.createWorkspaceScope());
        names.sort(null);
        return names;
    }

    /**
     * Creates the project and links its source folder in one workspace operation, lets the
     * indexer finish with the classpath the project is created with, then sets the configured
     * classpath and lets the indexer finish again: the interleaving in which a load's
     * configuration steps outlast the indexer's first pass.
     */
    private static IJavaProject linkIndexThenConfigure(Path root) throws Exception {
        WorkspaceManager workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
        IProject[] created = new IProject[1];
        ResourcesPlugin.getWorkspace().run(monitor -> {
            IProject project = workspaceManager.createLinkedProject("javalens-" + root.getFileName(), root);
            workspaceManager.createLinkedFolder(project, LINKED_SOURCE_FOLDER, root.resolve("src/main/java"));
            created[0] = project;
        }, new NullProgressMonitor());
        IProject project = created[0];
        waitForIndexer();
        IJavaProject javaProject = JavaCore.create(project);
        javaProject.setRawClasspath(new IClasspathEntry[] {
            JavaCore.newContainerEntry(JavaRuntime.getDefaultJREContainerEntry().getPath()),
            JavaCore.newSourceEntry(project.getFolder(LINKED_SOURCE_FOLDER).getFullPath())
        }, project.getFullPath().append("bin"), new NullProgressMonitor());
        waitForIndexer();
        return javaProject;
    }

    private static void waitForIndexer() throws CoreException {
        typeNames("org.javalens.none", SearchEngine.createWorkspaceScope());
    }

    /** Top-level and member types declared by the compilation units of the project's source roots. */
    private static Set<String> declaredTypes(IJavaProject project) throws CoreException {
        Set<String> declared = new TreeSet<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    for (IType type : cu.getAllTypes()) {
                        declared.add(type.getFullyQualifiedName('.'));
                    }
                }
            }
        }
        return declared;
    }

    /** Types the search index finds in the project's source roots. */
    private static Set<String> indexedTypes(IJavaProject project) throws CoreException {
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
            new IJavaElement[] { project }, IJavaSearchScope.SOURCES);
        return new TreeSet<>(typeNames(null, scope));
    }

    /**
     * Types the index finds in {@code scope}, in {@code packageName} when it is not null. The
     * index qualifies a member type by its enclosing types, so the search matches the package
     * as a prefix and the package is compared exactly here.
     */
    private static List<String> typeNames(String packageName, IJavaSearchScope scope) throws CoreException {
        List<String> names = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(
            packageName == null ? null : (packageName + "*").toCharArray(),
            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE,
            null, SearchPattern.R_PREFIX_MATCH,
            IJavaSearchConstants.TYPE, scope,
            new TypeNameRequestor() {
                @Override
                public void acceptType(int modifiers, char[] pkg, char[] simpleTypeName,
                                       char[][] enclosingTypeNames, String path) {
                    if (packageName != null && !packageName.equals(new String(pkg))) {
                        return;
                    }
                    StringBuilder name = new StringBuilder(new String(pkg));
                    for (char[] enclosing : enclosingTypeNames) {
                        name.append('.').append(enclosing);
                    }
                    names.add(name.append('.').append(simpleTypeName).toString());
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
        return names;
    }

    /** References to {@code typeName} in the project's source roots, as file:line (0-based), sorted. */
    private static List<String> references(IJavaProject project, String typeName) throws CoreException {
        IType type = project.findType(typeName);
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
            new IJavaElement[] { project }, IJavaSearchScope.SOURCES);
        List<String> found = new ArrayList<>();
        new SearchEngine().search(
            SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES),
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) throws CoreException {
                    IFile file = (IFile) match.getResource();
                    String source = JavaCore.createCompilationUnitFrom(file).getSource();
                    long line = source.substring(0, match.getOffset()).chars().filter(c -> c == '\n').count();
                    found.add(file.getName() + ":" + line);
                }
            },
            new NullProgressMonitor());
        found.sort((a, b) -> {
            String[] left = a.split(":");
            String[] right = b.split(":");
            int byFile = left[0].compareTo(right[0]);
            return byFile != 0 ? byFile : Integer.compare(Integer.parseInt(left[1]), Integer.parseInt(right[1]));
        });
        return found;
    }

    /**
     * A build-file-less project with {@code plainFileCount} lambda-free classes in package
     * {@code a.bulk} and, indexed after them, one class with a lambda and a method reference
     * in package {@code b.late}.
     */
    private Path writeOrderedFixture(String name, int plainFileCount) throws IOException {
        Path root = helper.getTempDirectory().resolve(name);
        Path sources = root.resolve("src/main/java");
        Path plainDir = Files.createDirectories(sources.resolve("a/bulk"));
        for (int i = 0; i < plainFileCount; i++) {
            String simpleName = plainName(i);
            Files.writeString(plainDir.resolve(simpleName + ".java"), """
                package a.bulk;

                public final class %s {
                    public int value() {
                        return %d;
                    }
                }
                """.formatted(simpleName, i));
        }
        Path lambdaDir = Files.createDirectories(sources.resolve("b/late"));
        Files.writeString(lambdaDir.resolve("Mapper.java"), """
            package b.late;

            import java.util.List;
            import java.util.stream.Collectors;

            public final class Mapper {
                public String join(List<Object> values) {
                    return values.stream().map(Object::toString).collect(Collectors.joining(","));
                }

                public Runnable task() {
                    return () -> { };
                }
            }
            """);
        return root;
    }

    private static Set<String> orderedFixtureTypes(int plainFileCount) {
        Set<String> types = IntStream.range(0, plainFileCount)
            .mapToObj(i -> "a.bulk." + plainName(i))
            .collect(Collectors.toCollection(TreeSet::new));
        types.add("b.late.Mapper");
        return types;
    }

    private static String plainName(int i) {
        return "Plain%03d".formatted(i);
    }
}
