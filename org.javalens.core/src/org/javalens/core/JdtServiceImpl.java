package org.javalens.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.javalens.core.exceptions.ReloadRequiredException;
import org.javalens.core.graph.ProjectGraphService;
import org.javalens.core.project.BuildSystem;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.ProjectPaths;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.search.SearchService;
import org.javalens.core.sync.DiskStampService;
import org.javalens.core.sync.DiskSyncMode;
import org.javalens.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Main JDT service implementation.
 * Ties together workspace management, project import, and search capabilities.
 */
public class JdtServiceImpl implements IJdtService {

    private static final Logger log = LoggerFactory.getLogger(JdtServiceImpl.class);

    private final WorkspaceManager workspaceManager;
    private final ProjectImporter projectImporter;
    /** The time limit on each tool call, from JAVALENS_TIMEOUT_SECONDS. */
    private TimeoutSetting timeoutSetting;
    /** Starts the deadline of a tool call from the limit in seconds. */
    private IntFunction<Deadline> deadlines = Deadline::of;

    private Path projectRoot;
    private IPathUtils pathUtils;
    private IJavaProject javaProject;
    private SearchService searchService;
    private ProjectGraphService projectGraphService;
    private DiskStampService diskStampService;
    /** Each source root's external location, normalized, to its linked folder, in classpath order. */
    private final Map<Path, IFolder> sourceRootFolders = new java.util.LinkedHashMap<>();
    private Instant loadedAt;

    // Project info for health_check
    private int sourceFileCount;
    private int packageCount;
    private List<String> packages;
    private BuildModel buildModel;
    private String analysisLevel;
    /** The importer's warnings for the last completed load: the model's, the JRE check's and configuration's. */
    private List<LoadWarning> modelWarnings = List.of();
    /** EARLIER_PROJECT_KEPT for each earlier project the last load could not remove. */
    private List<LoadWarning> earlierProjectWarnings = List.of();

    /** Creates the verifier each load checks the search index with. */
    private Function<IJavaProject, IndexVerifier> indexVerifiers = IndexVerifier::new;
    /** Verifies the search index against the loaded compilation units. */
    private IndexVerifier indexVerifier;
    /**
     * The source files whose declarations the search index is missing, as last verified, as
     * normalized absolute paths: every file at load, and each file a disk-sync repair re-reads.
     */
    private volatile Set<Path> indexGaps = Set.of();
    /** Whether a gap the index verification finds is re-indexed before it is reported. */
    private boolean indexRepairEnabled = true;
    /**
     * The libraries whose search index JDT could not build at the last load, even after
     * rebuilding it, as load warnings show them, sorted.
     */
    private List<String> libraryIndexGaps = List.of();

    private DiskSyncMode diskSyncMode;

    public JdtServiceImpl() {
        this(new ProjectImporter());
    }

    /** Test seam: load through {@code projectImporter} instead of the default build-system dispatch. */
    public JdtServiceImpl(ProjectImporter projectImporter) {
        this(projectImporter, new WorkspaceManager());
    }

    /**
     * Test seam: load through {@code projectImporter}, creating and removing workspace
     * projects through {@code workspaceManager}.
     */
    public JdtServiceImpl(ProjectImporter projectImporter, WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
        this.projectImporter = projectImporter;
        this.timeoutSetting = TimeoutSetting.fromEnvironment();
        this.diskSyncMode = DiskSyncMode.fromEnvironment(System.getenv("JAVALENS_DISK_SYNC"));
    }

    @Override
    public DiskSyncMode getDiskSyncMode() {
        return diskSyncMode;
    }

    /** Test/config seam; production wiring reads JAVALENS_DISK_SYNC at construction. */
    public void setDiskSyncMode(DiskSyncMode mode) {
        this.diskSyncMode = mode;
    }

    /**
     * Test seam: with {@code enabled} false, a gap the index verification finds is reported
     * without re-indexing its files first, so a test can keep a gap past the repair.
     */
    void setIndexRepairEnabled(boolean enabled) {
        this.indexRepairEnabled = enabled;
    }

    /**
     * Test seam: each load verifies the search index, at load and after disk-sync repairs, with
     * a verifier from {@code indexVerifiers}, so a test can act on a file as it is read.
     */
    void setIndexVerifiers(Function<IJavaProject, IndexVerifier> indexVerifiers) {
        this.indexVerifiers = indexVerifiers;
    }

    /**
     * Test/config seam; production reads JAVALENS_TIMEOUT_SECONDS at construction. The setting's
     * warning is among {@link #getWarnings()} from then on.
     */
    public void setTimeoutSetting(TimeoutSetting setting) {
        this.timeoutSetting = setting;
    }

    /**
     * Test seam: each tool call's deadline is {@code deadlines} applied to the configured limit,
     * so a test can expire a call at a chosen cancellation check. Unused while no limit is set.
     */
    public void setDeadlines(IntFunction<Deadline> deadlines) {
        this.deadlines = deadlines;
    }

    /** Loads run one at a time across every instance, so no two loads change the workspace at once. */
    private static final Object LOAD_LOCK = new Object();

    /**
     * Load a project for analysis.
     *
     * @param path Path to the project root
     * @throws CoreException if project loading fails
     */
    public void loadProject(Path path) throws CoreException {
        loadProject(path, () -> true);
    }

    /**
     * Load a project for analysis unless a later load was requested meanwhile. Loads run one
     * at a time across every instance; when this load's turn comes, {@code stillRequested}
     * decides whether it runs. A load that is no longer requested leaves the workspace
     * untouched.
     *
     * @param path Path to the project root
     * @param stillRequested whether this load is still wanted, asked once its turn comes
     * @return true if the project was loaded, false if the load was no longer requested
     * @throws CoreException if project loading fails
     */
    public boolean loadProject(Path path, BooleanSupplier stillRequested) throws CoreException {
        synchronized (LOAD_LOCK) {
            if (!stillRequested.getAsBoolean()) {
                log.info("Skipping the load of {}: a later load was requested", path);
                return false;
            }
            load(path);
            return true;
        }
    }

    private void load(Path path) throws CoreException {
        log.info("Loading project: {}", path);

        // A load that fails leaves this service as its last completed load left it, a reload of
        // the same root included: the earlier project stays, and so does every answer.
        LoadedState earlier = loadedState();
        IProject project;
        try {
            project = loadNewProject(path);
        } catch (CoreException | RuntimeException | Error e) {
            restore(earlier);
            throw e;
        }
        this.modelWarnings = List.copyOf(projectImporter.getWarnings());

        // The load has completed and is the result even when an earlier project cannot be
        // removed: each one that stays is reported, and the next load removes it.
        this.earlierProjectWarnings = workspaceManager.removeCreatedProjectsExcept(project).stream()
            .map(JdtServiceImpl::earlierProjectKept)
            .toList();

        this.loadedAt = Instant.now();
        log.info("Project loaded successfully at {}", loadedAt);
    }

    /**
     * Resolve the model of the root at {@code path}, then create, configure and index a new
     * project for it. Every earlier project stays.
     *
     * @return the new project
     * @throws CoreException if the load fails; the new project is then removed
     */
    private IProject loadNewProject(Path path) throws CoreException {
        this.projectRoot = path.toAbsolutePath().normalize();
        this.pathUtils = new PathUtilsImpl(projectRoot);
        this.earlierProjectWarnings = List.of();
        this.indexGaps = Set.of();
        this.libraryIndexGaps = List.of();

        // Initialize workspace
        workspaceManager.initialize();

        // Resolve the build model once; project info and configuration all derive from it.
        BuildModel model = projectImporter.resolveModel(projectRoot);
        this.buildModel = model;
        this.sourceFileCount = projectImporter.countSourceFiles(model);
        this.packages = projectImporter.findPackages(model);
        this.packageCount = packages.size();

        log.info("Detected {} build system, {} source files, {} packages",
            model.buildSystem(), sourceFileCount, packageCount);

        // Create project in workspace (metadata stays in workspace, not user's project), under
        // a name of its own: a reload of the same root keeps the earlier project until it loads.
        String projectName = "javalens-" + projectRoot.getFileName();
        IProject project = workspaceManager.createLinkedProject(projectName, projectRoot);

        // The new project replaces the earlier loads' projects only once it has loaded: a load
        // that fails removes its own project and leaves the earlier ones as they were, so the
        // workspace holds one JavaLens project whenever no load is running.
        try {
            configureAndIndex(project, model);
        } catch (CoreException | RuntimeException | Error e) {
            try {
                workspaceManager.removeProject(project);
            } catch (CoreException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return project;
    }

    /** What a load sets on this service, as its last completed load left it. */
    private record LoadedState(Path projectRoot, IPathUtils pathUtils, IJavaProject javaProject,
                               String analysisLevel, Map<Path, IFolder> sourceRootFolders,
                               SearchService searchService, ProjectGraphService projectGraphService,
                               IndexVerifier indexVerifier, Set<Path> indexGaps, List<String> libraryIndexGaps,
                               DiskStampService diskStampService, BuildModel buildModel, int sourceFileCount,
                               List<String> packages, int packageCount, List<LoadWarning> modelWarnings,
                               List<LoadWarning> earlierProjectWarnings, Instant loadedAt) {
    }

    private LoadedState loadedState() {
        return new LoadedState(projectRoot, pathUtils, javaProject, analysisLevel,
            new java.util.LinkedHashMap<>(sourceRootFolders), searchService, projectGraphService,
            indexVerifier, indexGaps, libraryIndexGaps, diskStampService, buildModel, sourceFileCount,
            packages, packageCount, modelWarnings, earlierProjectWarnings, loadedAt);
    }

    private void restore(LoadedState state) {
        this.projectRoot = state.projectRoot();
        this.pathUtils = state.pathUtils();
        this.javaProject = state.javaProject();
        this.analysisLevel = state.analysisLevel();
        this.sourceRootFolders.clear();
        this.sourceRootFolders.putAll(state.sourceRootFolders());
        this.searchService = state.searchService();
        this.projectGraphService = state.projectGraphService();
        this.indexVerifier = state.indexVerifier();
        this.indexGaps = state.indexGaps();
        this.libraryIndexGaps = state.libraryIndexGaps();
        this.diskStampService = state.diskStampService();
        this.buildModel = state.buildModel();
        this.sourceFileCount = state.sourceFileCount();
        this.packages = state.packages();
        this.packageCount = state.packageCount();
        this.modelWarnings = state.modelWarnings();
        this.earlierProjectWarnings = state.earlierProjectWarnings();
        this.loadedAt = state.loadedAt();
    }

    private void configureAndIndex(IProject project, BuildModel model) throws CoreException {
        // Configure as Java project with linked source folders
        this.javaProject = projectImporter.configureJavaProject(project, projectRoot, model, workspaceManager);
        // The level configuration applied, after the runtime fallback and the clamp.
        this.analysisLevel = javaProject.getOption(JavaCore.COMPILER_SOURCE, true);
        // File paths resolve to workspace files through the linked source folders.
        mapSourceRootFolders();

        // Initialize search service
        this.searchService = new SearchService(javaProject);

        // Graph service builds its graph lazily on first query
        this.projectGraphService = new ProjectGraphService(javaProject);

        // Bug F fix: force the JDT search index to be ready before loadProject returns.
        // Without this, callers that issue SearchEngine queries immediately after load can
        // race the background indexer and see zero results for symbols that do exist.
        waitForIndexReady();

        // Have JDT rebuild any library index it cannot search, so no search answers without a
        // library's types; a library still unsearchable afterwards is reported.
        this.libraryIndexGaps = repairLibraryIndexes();

        // Verify the index against every compilation unit, re-indexing any unit it lacks, so
        // no search answers around a gap left by the indexer.
        this.indexVerifier = indexVerifiers.apply(javaProject);
        this.indexGaps = locations(indexVerifier.verifyAndRepair(sourceCompilationUnits(), indexRepairEnabled));
        logIndexGaps();

        initializeDiskSync();
    }

    /**
     * Map each source root's external location to its linked folder. Every file path,
     * from a tool or from disk sync, resolves to its workspace file through this map.
     */
    private void mapSourceRootFolders() throws JavaModelException {
        sourceRootFolders.clear();
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            if (!(root.getResource() instanceof IFolder folder)) continue;
            IPath location = folder.getLocation();
            if (location == null) continue;
            sourceRootFolders.put(Path.of(location.toOSString()).toAbsolutePath().normalize(), folder);
        }
    }

    /**
     * Build the disk-truth stamps for verify-and-repair: stamp every source file
     * under the mapped source roots plus the build files. A failure here degrades
     * to manual-sync behavior with a warning rather than failing the load.
     */
    private void initializeDiskSync() {
        try {
            this.diskStampService = new DiskStampService(
                List.copyOf(sourceRootFolders.keySet()), collectBuildFiles());
            this.diskStampService.stampAll();
        } catch (Exception e) {
            log.warn("Disk-sync stamping failed; falling back to manual sync: {}", e.getMessage());
            this.diskStampService = null;
        }
    }

    private static final java.util.Set<String> BUILD_FILE_NAMES = java.util.Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "MODULE.bazel", "WORKSPACE", "WORKSPACE.bazel", "BUILD", "BUILD.bazel");
    private static final java.util.Set<String> BUILD_WALK_SKIP_DIRS = java.util.Set.of(
        "target", "build", "bin", ".git", "src", "node_modules");

    /** All build files in the project tree (module poms included), skipping outputs and sources. */
    private List<Path> collectBuildFiles() throws IOException {
        List<Path> buildFiles = new ArrayList<>();
        Files.walkFileTree(projectRoot, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(projectRoot)
                    && (BUILD_WALK_SKIP_DIRS.contains(name) || name.startsWith("bazel-"))) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                if (BUILD_FILE_NAMES.contains(file.getFileName().toString())) {
                    buildFiles.add(file.toAbsolutePath().normalize());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return buildFiles;
    }

    /**
     * #26 verify-and-repair: compare disk against the stamps; repair exactly
     * what changed (scoped resource refresh, index barrier, derived-cache
     * invalidation, index verification of the repaired files, restamp); return
     * the repaired paths. Empty list = nothing
     * had changed. Build-file changes are not repairable per-file and raise
     * {@link ReloadRequiredException}; verification I/O failures propagate as
     * {@link IOException} - callers must never answer around either.
     */
    @Override
    public synchronized List<Path> ensureFresh()
            throws IOException, CoreException, ReloadRequiredException {
        if (diskSyncMode == DiskSyncMode.MANUAL || diskStampService == null) {
            return List.of(); // manual mode, or stamping unavailable (load warning surfaced)
        }
        DiskStampService.ChangeSet changes = diskStampService.verify();
        if (changes.isEmpty()) {
            return List.of();
        }
        if (!changes.buildFilesChanged().isEmpty()) {
            throw new ReloadRequiredException(changes.buildFilesChanged());
        }

        for (Path edited : changes.edited()) {
            IFile file = resolveWorkspaceFile(edited);
            if (file != null) {
                file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
            }
        }
        // Adds and deletes change the resource tree shape: refresh the owning
        // source-root link deeply so new packages and removals are both seen.
        java.util.Set<IFolder> foldersToRefresh = new java.util.LinkedHashSet<>();
        for (Path changed : concat(changes.added(), changes.deleted())) {
            Map.Entry<Path, IFolder> owner = owningSourceRoot(changed);
            if (owner != null) {
                foldersToRefresh.add(owner.getValue());
            }
        }
        for (IFolder folder : foldersToRefresh) {
            folder.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }

        waitForIndexReady();
        if (projectGraphService != null) {
            projectGraphService.invalidate();
        }

        List<Path> repaired = concat(concat(changes.edited(), changes.added()), changes.deleted());
        verifyRepairedFiles(repaired);
        diskStampService.restamp(repaired);
        log.info("Disk-sync repaired {} file(s): {}", repaired.size(), repaired);
        return repaired;
    }

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    /**
     * Verify the index against the files a disk-sync repair re-read, re-indexing any it lacks,
     * and update the gaps: a re-read file is a gap only if this verification finds it one, and
     * the gaps of the other files stay as they were. A deleted file is no longer a gap.
     */
    private void verifyRepairedFiles(List<Path> repaired) throws CoreException {
        Set<Path> gaps = new HashSet<>(indexGaps);
        List<ICompilationUnit> units = new ArrayList<>();
        for (Path path : repaired) {
            gaps.remove(path);
            ICompilationUnit unit = getCompilationUnit(path);
            if (unit != null) {
                units.add(unit);
            }
        }
        gaps.addAll(locations(indexVerifier.verifyAndRepair(units, indexRepairEnabled)));
        this.indexGaps = Set.copyOf(gaps);
        logIndexGaps();
    }

    /** Each unit's file as a normalized absolute path. */
    private static Set<Path> locations(List<ICompilationUnit> units) {
        Set<Path> paths = new HashSet<>();
        for (ICompilationUnit unit : units) {
            paths.add(Path.of(unit.getResource().getLocation().toOSString()).toAbsolutePath().normalize());
        }
        return Set.copyOf(paths);
    }

    private void logIndexGaps() {
        if (!indexGaps.isEmpty()) {
            log.warn("The search index is missing declarations from {} file(s): {}",
                indexGaps.size(), getIndexIncompleteFiles());
        }
    }

    @Override
    public List<String> getIndexIncompleteFiles() {
        return indexGaps.stream()
            .map(path -> ProjectPaths.relativeToRoot(projectRoot, path))
            .sorted()
            .toList();
    }

    @Override
    public List<String> getLibraryIndexIncomplete() {
        return libraryIndexGaps;
    }

    /**
     * Map a normalized absolute file path to its workspace IFile in the linked folder of
     * the source root that owns it; null when no source root holds the path.
     */
    private IFile resolveWorkspaceFile(Path externalPath) {
        Map.Entry<Path, IFolder> owner = owningSourceRoot(externalPath);
        if (owner == null) {
            return null;
        }
        Path relative = owner.getKey().relativize(externalPath);
        return owner.getValue().getFile(new org.eclipse.core.runtime.Path(relative.toString().replace('\\', '/')));
    }

    /**
     * The source root that owns {@code externalPath}, as its location and linked folder:
     * of the roots whose location holds the path, the deepest. A root nested in another is
     * excluded from the outer root's source entry, so a file under it belongs to the inner
     * root. Null when no source root holds the path.
     */
    private Map.Entry<Path, IFolder> owningSourceRoot(Path externalPath) {
        Map.Entry<Path, IFolder> owner = null;
        for (Map.Entry<Path, IFolder> entry : sourceRootFolders.entrySet()) {
            if (externalPath.startsWith(entry.getKey())
                    && (owner == null || entry.getKey().getNameCount() > owner.getKey().getNameCount())) {
                owner = entry;
            }
        }
        return owner;
    }

    /**
     * Block until JDT's search index has finished its background work for the loaded project.
     * Issues a no-op {@code searchAllTypeNames} call with {@code WAIT_UNTIL_READY_TO_SEARCH},
     * which the JDT contract documents as flushing pending index jobs before the search runs.
     * A failed wait fails the load or the disk sync that waits: no search runs on an index whose
     * readiness is unknown.
     */
    private void waitForIndexReady() throws CoreException {
        new SearchEngine().searchAllTypeNames(
            null, SearchPattern.R_EXACT_MATCH,
            null, SearchPattern.R_EXACT_MATCH,
            IJavaSearchConstants.TYPE,
            SearchEngine.createJavaSearchScope(new IJavaElement[] { javaProject }),
            new TypeNameRequestor() {
                @Override
                public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
                                       char[][] enclosingTypeNames, String path) {
                    // no-op: only here to flush the indexer
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
            new NullProgressMonitor());
    }

    /**
     * Verify that JDT holds a searchable index for every library on the classpath; drop the
     * index of each library it does not and run the index barrier again, so JDT rebuilds those
     * indexes and the barrier waits for them, then verify again. See {@link LibraryIndexVerifier}
     * for how JDT leaves a library with an index no search reads.
     *
     * @return the libraries still unsearchable, as load warnings show them, sorted
     */
    private List<String> repairLibraryIndexes() throws CoreException {
        LibraryIndexVerifier libraries = new LibraryIndexVerifier(javaProject);
        List<IPath> unsearchable = libraries.findUnsearchable();
        if (unsearchable.isEmpty()) {
            return List.of();
        }
        log.warn("JDT holds no searchable index for {} librar{}; rebuilding {}",
            unsearchable.size(), unsearchable.size() == 1 ? "y" : "ies", unsearchable);
        libraries.dropIndexes(unsearchable);
        waitForIndexReady();
        List<String> shown = libraries.findUnsearchable().stream()
            .map(library -> pathUtils.formatPath(Path.of(library.toOSString())))
            .sorted()
            .toList();
        if (!shown.isEmpty()) {
            log.warn("The search index of {} librar{} could not be built: {}",
                shown.size(), shown.size() == 1 ? "y" : "ies", shown);
        }
        return shown;
    }

    @Override
    public IPathUtils getPathUtils() {
        return pathUtils;
    }

    /**
     * The model's warnings, then TIMEOUT_SECONDS_INVALID when JAVALENS_TIMEOUT_SECONDS holds a
     * value not used as given, then INDEX_INCOMPLETE while the index is verified to miss files'
     * declarations, then LIBRARY_INDEX_INCOMPLETE when the load could not build a library's
     * index, then EARLIER_PROJECT_KEPT for each earlier project the load kept.
     */
    @Override
    public List<LoadWarning> getWarnings() {
        List<LoadWarning> warnings = new ArrayList<>(modelWarnings);
        if (timeoutSetting.warning() != null) {
            warnings.add(timeoutSetting.warning());
        }
        List<String> indexIncompleteFiles = getIndexIncompleteFiles();
        if (!indexIncompleteFiles.isEmpty()) {
            warnings.add(indexIncomplete(indexIncompleteFiles));
        }
        if (!libraryIndexGaps.isEmpty()) {
            warnings.add(libraryIndexIncomplete(libraryIndexGaps));
        }
        warnings.addAll(earlierProjectWarnings);
        return List.copyOf(warnings);
    }

    /** The remediation of a warning that a reload, or failing that a restart, clears. */
    private static final String RELOAD_OR_RESTART = "Reload the project; if the warning persists, restart JavaLens.";

    private static LoadWarning indexIncomplete(List<String> files) {
        return new LoadWarning(LoadWarning.INDEX_INCOMPLETE,
            "The search index is missing declarations from " + files.size()
                + (files.size() == 1 ? " file: " : " files: ") + String.join(", ", files)
                + "; search-based results do not include them",
            RELOAD_OR_RESTART);
    }

    private static LoadWarning libraryIndexIncomplete(List<String> libraries) {
        boolean one = libraries.size() == 1;
        return new LoadWarning(LoadWarning.LIBRARY_INDEX_INCOMPLETE,
            "The search index of " + libraries.size() + (one ? " library" : " libraries")
                + " could not be built: " + String.join(", ", libraries)
                + "; search-based results do not include " + (one ? "its" : "their") + " types",
            "Make sure each library is a readable archive or class folder, then reload the project; "
                + "if the warning persists, restart JavaLens.");
    }

    private static LoadWarning earlierProjectKept(WorkspaceManager.KeptProject kept) {
        return new LoadWarning(LoadWarning.EARLIER_PROJECT_KEPT,
            "The earlier JavaLens project " + kept.name() + " could not be removed: " + kept.reason()
                + "; it stays in the workspace until the next load removes it",
            RELOAD_OR_RESTART);
    }

    @Override
    public BuildModel getBuildModel() {
        return buildModel;
    }

    @Override
    public String getAnalysisLevel() {
        return analysisLevel;
    }

    @Override
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public Integer getTimeoutSeconds() {
        return timeoutSetting.seconds();
    }

    @Override
    public Deadline newDeadline() {
        Integer seconds = timeoutSetting.seconds();
        return seconds == null ? null : deadlines.apply(seconds);
    }

    // Getters for project info (used by LoadProjectTool response)

    @Override
    public IJavaProject getJavaProject() {
        return javaProject;
    }

    @Override
    public SearchService getSearchService() {
        return searchService;
    }

    @Override
    public ProjectGraphService getProjectGraphService() {
        return projectGraphService;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }

    public int getPackageCount() {
        return packageCount;
    }

    public List<String> getPackages() {
        return packages;
    }

    /** The build system of the loaded model, or {@code null} before a project is loaded. */
    public BuildSystem getBuildSystem() {
        return buildModel == null ? null : buildModel.buildSystem();
    }

    /** The number of raw classpath entries; 0 before a load. A classpath JDT cannot read fails. */
    public int getClasspathEntryCount() {
        if (javaProject == null) {
            return 0;
        }
        try {
            return javaProject.getRawClasspath().length;
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read the project's classpath: " + e.getMessage(), e);
        }
    }

    // ========== New Interface Methods for Tools ==========

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        if (javaProject == null) {
            return null;
        }

        // A relative path is relative to the project root, as tools resolve it.
        IFile file = resolveWorkspaceFile(pathUtils.resolve(filePath.toString()));
        ICompilationUnit cu = file == null ? null : JavaCore.createCompilationUnitFrom(file);
        if (cu == null || !cu.exists()) {
            log.debug("Compilation unit not found for: {}", filePath);
            return null;
        }
        return cu;
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) {
            log.debug("Compilation unit not found for: {}", filePath);
            return null;
        }

        try {
            // Ensure the compilation unit is open and reconciled for codeSelect to work
            if (!cu.isOpen()) {
                cu.open(Deadline.monitor());
            }

            // Reconcile to ensure the AST is up to date
            cu.reconcile(ICompilationUnit.NO_AST, false, null, Deadline.monitor());

            int offset = getOffset(cu, line, column);
            log.debug("Looking for element at {}:{}:{} (offset {})", filePath, line, column, offset);

            // Positions inside string/char/text-block literals or any comment
            // have no resolvable symbol. JDT's codeSelect tokenises comment
            // text and may return spurious matches when the comment contains
            // identifier-like words (e.g., "Empty" inside `// Empty catch`
            // would otherwise resolve to sun.invoke.empty.Empty). Short-
            // circuit BEFORE consulting codeSelect or the getElementAt
            // fallback.
            if (isOffsetInsideLiteralOrComment(cu, offset)) {
                log.debug("Offset {} is inside a literal/comment; returning null", offset);
                return null;
            }

            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) {
                log.debug("Found element: {} ({})", elements[0].getElementName(), elements[0].getClass().getSimpleName());
                return elements[0];
            }

            // A name written as a reference that codeSelect cannot resolve (its type is on no
            // classpath entry, say) names no element: the member enclosing it is another symbol.
            if (isUnresolvedReferenceName(cu, offset)) {
                log.debug("Offset {} is on a name that does not resolve; returning null", offset);
                return null;
            }

            // Fallback: a position on no name (indentation, a keyword, punctuation) belongs to
            // the element enclosing it
            IJavaElement element = cu.getElementAt(offset);
            if (element != null) {
                log.debug("Found element via getElementAt: {} ({})", element.getElementName(), element.getClass().getSimpleName());
                return element;
            }

            log.debug("No element found at position");
            return null;
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read the element at line " + line + ", column " + column
                + " of " + cu.getElementName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Whether {@code offset} lies on a simple name that is not the name a declaration declares:
     * a reference, which names a symbol of its own. Read from a syntax tree without bindings.
     */
    private boolean isUnresolvedReferenceName(ICompilationUnit cu, int offset) {
        org.eclipse.jdt.core.dom.ASTParser parser =
            org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(false);
        org.eclipse.jdt.core.dom.ASTNode covering = new org.eclipse.jdt.core.dom.NodeFinder(
            parser.createAST(Deadline.monitor()), offset, 0).getCoveringNode();
        return covering instanceof org.eclipse.jdt.core.dom.SimpleName name && !name.isDeclaration();
    }

    /**
     * Returns true when {@code offset} sits inside a Java string literal,
     * character literal, or any comment (line, block, or Javadoc) in the
     * given compilation unit. Such positions have no resolvable symbol
     * and the fallback to {@link ICompilationUnit#getElementAt(int)} would
     * misleadingly return the smallest enclosing IMember.
     */
    private boolean isOffsetInsideLiteralOrComment(ICompilationUnit cu, int offset) {
        String source = sourceOf(cu);
        if (offset < 0 || offset >= source.length()) return false;
        org.eclipse.jdt.core.dom.ASTParser parser =
            org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(false);
        org.eclipse.jdt.core.dom.CompilationUnit ast =
            (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(Deadline.monitor());
        // Comments: scan ast.getCommentList() and check ranges.
        @SuppressWarnings("unchecked")
        java.util.List<org.eclipse.jdt.core.dom.Comment> comments = ast.getCommentList();
        if (comments != null) {
            for (org.eclipse.jdt.core.dom.Comment c : comments) {
                int s = c.getStartPosition();
                int e = s + c.getLength();
                if (offset >= s && offset < e) return true;
            }
        }
        // Literals: find covering node and check its type.
        org.eclipse.jdt.core.dom.NodeFinder finder =
            new org.eclipse.jdt.core.dom.NodeFinder(ast, offset, 0);
        org.eclipse.jdt.core.dom.ASTNode covering = finder.getCoveringNode();
        if (covering instanceof org.eclipse.jdt.core.dom.StringLiteral) return true;
        if (covering instanceof org.eclipse.jdt.core.dom.CharacterLiteral) return true;
        if (covering instanceof org.eclipse.jdt.core.dom.TextBlock) return true;
        return false;
    }

    /**
     * The type at the position, or else the type enclosing the element there. A lambda
     * expression is no type of its own: a position in a lambda body is within the type the
     * lambda is written in.
     */
    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement current = getElementAtPosition(filePath, line, column);
        while (current != null && !(current instanceof IType type && !type.isLambda())) {
            current = current.getParent();
        }
        return (IType) current;
    }

    @Override
    public IType findType(String typeName) {
        if (javaProject == null || typeName == null || typeName.isBlank()) {
            return null;
        }

        try {
            // First try as fully qualified name. JDT reads a member type's dotted name and its
            // '$' form alike (NameLookup.seekTypes reads p.Outer$Inner as the member Inner of
            // p.Outer), the form every tool reports a member type in. JDT can return the OUTER
            // type when asked for a nested type via dotted form (e.g. findType("p.Outer.Inner")
            // may return Outer), so the type found must carry the name asked for, in either
            // form; if not, force the nested-resolution path. JDT can also answer with a handle
            // the model does not hold (a member "1" of Outer for p.Outer.1), so the type must
            // exist.
            IType type = javaProject.findType(typeName);
            if (type != null && type.exists()
                    && (typeName.equals(type.getFullyQualifiedName('.')) || typeName.equals(type.getFullyQualifiedName()))) {
                return type;
            }

            // Nested-type fallback: replace dots between segments with $ from the right.
            // "a.b.Outer.Inner.Inner2" → try "a.b.Outer.Inner$Inner2", "a.b.Outer$Inner$Inner2".
            int lastDot = typeName.lastIndexOf('.');
            while (lastDot > 0) {
                String candidate = typeName.substring(0, lastDot) + "$" + typeName.substring(lastDot + 1);
                IType nested = javaProject.findType(candidate);
                if (nested != null && nested.exists()) {
                    return nested;
                }
                lastDot = typeName.lastIndexOf('.', lastDot - 1);
            }

            // A local or anonymous class by the Java model's '$' form (p.Outer$Local, p.Outer$1,
            // p.Outer$Inner$2), which JDT's lookup of named types does not know.
            IType local = findLocalByModelName(typeName);
            if (local != null) {
                return local;
            }

            // Try searching for simple name in all packages
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                for (IType t : cu.getTypes()) {
                                    if (t.getElementName().equals(typeName)) {
                                        return t;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return null;
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot look up the type " + typeName + ": " + e.getMessage(), e);
        }
    }

    /**
     * The local or anonymous class, a class declared in a method, field or initializer at any
     * depth, whose model name ({@link IType#getFullyQualifiedName()}, '$' between nested types) is
     * {@code typeName}, within the compilation unit of the top-level type named before the first
     * '$'. A name the unit gives to more than one such class (two local classes of one name in
     * different members) names none of them, as does a name no class carries.
     */
    private IType findLocalByModelName(String typeName) throws JavaModelException {
        int dollar = typeName.indexOf('$');
        if (dollar <= 0) {
            return null;
        }
        IType outer = javaProject.findType(typeName.substring(0, dollar));
        if (outer == null || !outer.exists() || outer.getCompilationUnit() == null) {
            return null;
        }
        List<IType> named = new ArrayList<>();
        collectLocalTypesNamed(outer.getCompilationUnit(), false, typeName, named);
        return named.size() == 1 ? named.get(0) : null;
    }

    private static void collectLocalTypesNamed(IParent parent, boolean local, String typeName, List<IType> into)
            throws JavaModelException {
        for (IJavaElement child : parent.getChildren()) {
            if (child instanceof IType type) {
                if (local && typeName.equals(type.getFullyQualifiedName())) {
                    into.add(type);
                }
                collectLocalTypesNamed(type, local, typeName, into);
            } else if (child instanceof IMember && child instanceof IParent nested) {
                // A method, field or initializer: the classes declared in it are local.
                collectLocalTypesNamed(nested, true, typeName, into);
            }
        }
    }

    /**
     * The source of {@code cu}. A unit whose source JDT cannot read, or gives no source for (a
     * buffer it has closed), fails: no position read from it would be true.
     */
    private static String sourceOf(ICompilationUnit cu) {
        String source;
        try {
            source = cu.getSource();
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot read the source of " + cu.getElementName() + ": " + e.getMessage(), e);
        }
        if (source == null) {
            throw new IllegalStateException("JDT gave no source for " + cu.getElementName());
        }
        return source;
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        String source = sourceOf(cu);

        // Find line start
        int lineStart = offset;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        // Find line end
        int lineEnd = offset;
        while (lineEnd < source.length() && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') {
            lineEnd++;
        }

        String line = source.substring(lineStart, Math.min(lineEnd, lineStart + 200));
        return line.trim();
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        String source = sourceOf(cu);

        int offset = 0;
        int currentLine = 0;

        // Navigate to the correct line
        while (currentLine < line && offset < source.length()) {
            if (source.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }

        // Add column offset
        return Math.min(offset + column, source.length());
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        String source = sourceOf(cu);

        int line = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        String source = sourceOf(cu);

        int column = 0;
        for (int i = offset - 1; i >= 0; i--) {
            if (source.charAt(i) == '\n') {
                break;
            }
            column++;
        }
        return column;
    }

    @Override
    public List<Path> getAllJavaFiles() {
        List<Path> files = new ArrayList<>();

        if (javaProject == null) {
            return files;
        }

        try {
            for (ICompilationUnit cu : sourceCompilationUnits()) {
                IResource resource = cu.getResource();
                if (resource != null) {
                    IPath location = resource.getLocation();
                    if (location != null) {
                        files.add(Path.of(location.toOSString()));
                    }
                }
            }
        } catch (JavaModelException e) {
            throw new IllegalStateException("Cannot list the project's source files: " + e.getMessage(), e);
        }

        return files;
    }

    /** The compilation units of the project's source folders, in classpath and package order. */
    private List<ICompilationUnit> sourceCompilationUnits() throws JavaModelException {
        List<ICompilationUnit> units = new ArrayList<>();
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    units.addAll(Arrays.asList(pkg.getCompilationUnits()));
                }
            }
        }
        return units;
    }
}
