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
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Main JDT service implementation.
 * Ties together workspace management, project import, and search capabilities.
 */
public class JdtServiceImpl implements IJdtService {

    private static final Logger log = LoggerFactory.getLogger(JdtServiceImpl.class);

    private final WorkspaceManager workspaceManager;
    private final ProjectImporter projectImporter;
    private final int timeoutSeconds;

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
        this.timeoutSeconds = parseTimeout();
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

    private static int parseTimeout() {
        String timeout = System.getenv("JAVALENS_TIMEOUT_SECONDS");
        if (timeout == null) return 30;
        try {
            int seconds = Integer.parseInt(timeout);
            return Math.max(5, Math.min(seconds, 300));
        } catch (NumberFormatException e) {
            return 30;
        }
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

        this.projectRoot = path.toAbsolutePath().normalize();
        this.pathUtils = new PathUtilsImpl(projectRoot);
        this.earlierProjectWarnings = List.of();
        this.indexGaps = Set.of();

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

        // Create project in workspace (metadata stays in workspace, not user's project)
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
        // The load has completed and is the result even when an earlier project cannot be
        // removed: each one that stays is reported, and the next load removes it.
        this.earlierProjectWarnings = workspaceManager.removeCreatedProjectsExcept(project).stream()
            .map(JdtServiceImpl::earlierProjectKept)
            .toList();

        this.loadedAt = Instant.now();
        log.info("Project loaded successfully at {}", loadedAt);
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
     */
    private void waitForIndexReady() {
        try {
            new SearchEngine().searchAllTypeNames(
                null, SearchPattern.R_EXACT_MATCH,
                null, SearchPattern.R_EXACT_MATCH,
                IJavaSearchConstants.TYPE,
                SearchEngine.createJavaSearchScope(new IJavaElement[] { javaProject }),
                new TypeNameRequestor() {
                    @Override
                    public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
                                           char[][] enclosingTypeNames, String path) {
                        // no-op — only here to flush the indexer
                    }
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                new NullProgressMonitor());
        } catch (CoreException e) {
            log.warn("Index readiness wait failed: {}. First searches may race.", e.getMessage());
        }
    }

    @Override
    public IPathUtils getPathUtils() {
        return pathUtils;
    }

    /**
     * The model's warnings, then INDEX_INCOMPLETE while the index is verified to miss files'
     * declarations, then EARLIER_PROJECT_KEPT for each earlier project the load kept.
     */
    @Override
    public List<LoadWarning> getWarnings() {
        List<LoadWarning> warnings = new ArrayList<>(projectImporter.getWarnings());
        List<String> indexIncompleteFiles = getIndexIncompleteFiles();
        if (!indexIncompleteFiles.isEmpty()) {
            warnings.add(indexIncomplete(indexIncompleteFiles));
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
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        return executeWithTimeout(operation, operationName, timeoutSeconds);
    }

    /**
     * Test-friendly overload: lets unit tests pass a short timeout so the
     * TimeoutException branch can be exercised without sleeping for 30+
     * seconds. Production calls go through the {@link #executeWithTimeout(Callable, String)}
     * variant which uses the configured timeout.
     */
    <T> T executeWithTimeout(Callable<T> operation, String operationName, int timeoutSec) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);

        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(
                operationName + " timed out after " + timeoutSec + " seconds"
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(operationName + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(operationName + " was interrupted");
        } finally {
            executor.shutdownNow();
        }
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

    public int getClasspathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
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
                cu.open(null);
            }

            // Reconcile to ensure the AST is up to date
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);

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

            // Fallback: try to find element at offset using getElementAt
            IJavaElement element = cu.getElementAt(offset);
            if (element != null) {
                log.debug("Found element via getElementAt: {} ({})", element.getElementName(), element.getClass().getSimpleName());
                return element;
            }

            log.debug("No element found at position");
            return null;
        } catch (JavaModelException e) {
            log.warn("Error getting element at position: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns true when {@code offset} sits inside a Java string literal,
     * character literal, or any comment (line, block, or Javadoc) in the
     * given compilation unit. Such positions have no resolvable symbol
     * and the fallback to {@link ICompilationUnit#getElementAt(int)} would
     * misleadingly return the smallest enclosing IMember.
     */
    private boolean isOffsetInsideLiteralOrComment(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null || offset < 0 || offset >= source.length()) return false;
            org.eclipse.jdt.core.dom.ASTParser parser =
                org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(false);
            org.eclipse.jdt.core.dom.CompilationUnit ast =
                (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);
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
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) {
            return type;
        }
        // If the element is within a type, try to get the enclosing type
        if (element != null) {
            IType enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
            return enclosingType;
        }
        return null;
    }

    @Override
    public IType findType(String typeName) {
        if (javaProject == null || typeName == null || typeName.isBlank()) {
            return null;
        }

        try {
            // First try as fully qualified name
            IType type = javaProject.findType(typeName);
            // JDT can return the OUTER type when asked for a nested type via dotted form
            // (e.g. findType("p.Outer.Inner") may return Outer). Validate that the simple
            // name matches the last segment; if not, force the nested-resolution path.
            if (type != null) {
                String lastSeg = typeName.substring(typeName.lastIndexOf('.') + 1);
                if (type.getElementName().equals(lastSeg)) {
                    return type;
                }
            }

            // Nested-type fallback: replace dots between segments with $ from the right.
            // "a.b.Outer.Inner.Inner2" → try "a.b.Outer.Inner$Inner2", "a.b.Outer$Inner$Inner2".
            int lastDot = typeName.lastIndexOf('.');
            while (lastDot > 0) {
                String candidate = typeName.substring(0, lastDot) + "$" + typeName.substring(lastDot + 1);
                IType nested = javaProject.findType(candidate);
                if (nested != null) {
                    return nested;
                }
                lastDot = typeName.lastIndexOf('.', lastDot - 1);
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
            log.warn("Error finding type {}: {}", typeName, e.getMessage());
            return null;
        }
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return "";
            }

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
        } catch (JavaModelException e) {
            log.trace("Error getting context line: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

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
        } catch (JavaModelException e) {
            log.warn("Error calculating offset: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int line = 0;
            for (int i = 0; i < offset && i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        } catch (JavaModelException e) {
            log.warn("Error calculating line number: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int column = 0;
            for (int i = offset - 1; i >= 0; i--) {
                if (source.charAt(i) == '\n') {
                    break;
                }
                column++;
            }
            return column;
        } catch (JavaModelException e) {
            log.warn("Error calculating column number: {}", e.getMessage());
            return 0;
        }
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
            log.warn("Error getting Java files: {}", e.getMessage());
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
