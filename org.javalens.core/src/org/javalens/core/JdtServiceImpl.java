package org.javalens.core;

import org.eclipse.core.resources.IFile;
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
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.search.SearchService;
import org.javalens.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private Instant loadedAt;

    // Project info for health_check
    private int sourceFileCount;
    private int packageCount;
    private List<String> packages;
    private ProjectImporter.BuildSystem buildSystem;

    public JdtServiceImpl() {
        this.workspaceManager = new WorkspaceManager();
        this.projectImporter = new ProjectImporter();
        this.timeoutSeconds = parseTimeout();
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

    /**
     * Load a project for analysis.
     *
     * @param path Path to the project root
     * @throws CoreException if project loading fails
     */
    public void loadProject(Path path) throws CoreException {
        long startTime = System.currentTimeMillis();
        System.err.println("=== PROJECT LOADING START: " + path + " ===");

        this.projectRoot = path.toAbsolutePath().normalize();
        this.pathUtils = new PathUtilsImpl(projectRoot);

        // 1. Initialize workspace
        long stepStart = System.currentTimeMillis();
        System.err.println("[1/6] Initializing Eclipse workspace...");
        workspaceManager.initialize();
        System.err.println("Workspace initialized in " + (System.currentTimeMillis() - stepStart) + " ms");

        // 2. Build system detection and project info
        stepStart = System.currentTimeMillis();
        System.err.println("[2/6] Detecting build system and scanning for sources...");
        this.buildSystem = projectImporter.detectBuildSystem(projectRoot);
        this.sourceFileCount = projectImporter.countSourceFiles(projectRoot);
        this.packages = projectImporter.findPackages(projectRoot);
        this.packageCount = packages.size();

        System.err.println("Detected " + buildSystem + " build system, " + sourceFileCount + " source files, " + packageCount + " packages in " + (System.currentTimeMillis() - stepStart) + " ms");

        // 3. Create project in workspace
        stepStart = System.currentTimeMillis();
        String projectName = "javalens-" + projectRoot.getFileName();
        System.err.println("[3/6] Creating project in workspace: " + projectName);
        IProject project = workspaceManager.createLinkedProject(projectName, projectRoot);
        System.err.println("Project created in " + (System.currentTimeMillis() - stepStart) + " ms");

        // 4. Configure as Java project
        stepStart = System.currentTimeMillis();
        System.err.println("[4/6] Configuring JDT classpath and linked folders...");
        this.javaProject = projectImporter.configureJavaProject(project, projectRoot, workspaceManager);
        System.err.println("Classpath configured in " + (System.currentTimeMillis() - stepStart) + " ms. Entry count: " + getClasspathEntryCount());

        // 5. Initialize search service
        stepStart = System.currentTimeMillis();
        System.err.println("[5/6] Initializing Search Service (indexing)...");
        this.searchService = new SearchService(javaProject);
        System.err.println("Search service ready in " + (System.currentTimeMillis() - stepStart) + " ms");

        // 6. Finalize
        this.loadedAt = Instant.now();
        long totalTime = System.currentTimeMillis() - startTime;
        System.err.println("[6/6] Project loaded successfully in " + totalTime + " ms at " + loadedAt);
        System.err.println("=== PROJECT LOADING COMPLETE ===");
    }

    @Override
    public IPathUtils getPathUtils() {
        return pathUtils;
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(
                operationName + " timed out after " + timeoutSeconds + " seconds"
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

    @Override
    public IJavaProject getJavaProject() {
        return javaProject;
    }

    @Override
    public SearchService getSearchService() {
        return searchService;
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

    public ProjectImporter.BuildSystem getBuildSystem() {
        return buildSystem;
    }

    public int getClasspathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        if (javaProject == null) return null;
        try {
            String pathStr = filePath.toString().replace('\\', '/');
            String classPath = pathStr;
            String[] sourcePrefixes = {"src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/", "src/"};
            for (String prefix : sourcePrefixes) {
                if (pathStr.contains(prefix)) {
                    int idx = pathStr.indexOf(prefix);
                    classPath = pathStr.substring(idx + prefix.length());
                    break;
                }
            }
            String withoutExt = classPath.replace(".java", "");
            String qualifiedName = withoutExt.replace('/', '.');
            IType type = javaProject.findType(qualifiedName);
            if (type != null) return type.getCompilationUnit();
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    int lastSlash = classPath.lastIndexOf('/');
                    String packageName = lastSlash > 0 ? classPath.substring(0, lastSlash).replace('/', '.') : "";
                    String className = lastSlash > 0 ? classPath.substring(lastSlash + 1) : classPath;
                    IPackageFragment pkg = root.getPackageFragment(packageName);
                    if (pkg != null && pkg.exists()) {
                        ICompilationUnit cu = pkg.getCompilationUnit(className);
                        if (cu != null && cu.exists()) return cu;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) return null;
        try {
            if (!cu.isOpen()) cu.open(null);
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
            int offset = getOffset(cu, line, column);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) return elements[0];
            return cu.getElementAt(offset);
        } catch (JavaModelException e) { return null; }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) return type;
        if (element != null) return (IType) element.getAncestor(IJavaElement.TYPE);
        return null;
    }

    @Override
    public IType findType(String typeName) {
        if (javaProject == null || typeName == null || typeName.isBlank()) return null;
        try {
            IType type = javaProject.findType(typeName);
            if (type != null) return type;
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                for (IType t : cu.getTypes()) {
                                    if (t.getElementName().equals(typeName)) return t;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        } catch (JavaModelException e) { return null; }
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) return "";
            int lineStart = offset;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
            int lineEnd = offset;
            while (lineEnd < source.length() && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') lineEnd++;
            return source.substring(lineStart, Math.min(lineEnd, lineStart + 200)).trim();
        } catch (JavaModelException e) { return ""; }
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        try {
            String source = cu.getSource();
            if (source == null) return 0;
            int offset = 0;
            int currentLine = 0;
            while (currentLine < line && offset < source.length()) {
                if (source.charAt(offset) == '\n') currentLine++;
                offset++;
            }
            return Math.min(offset + column, source.length());
        } catch (JavaModelException e) { return 0; }
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) return 0;
            int line = 0;
            for (int i = 0; i < offset && i < source.length(); i++) {
                if (source.charAt(i) == '\n') line++;
            }
            return line;
        } catch (JavaModelException e) { return 0; }
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) return 0;
            int column = 0;
            for (int i = offset - 1; i >= 0; i--) {
                if (source.charAt(i) == '\n') break;
                column++;
            }
            return column;
        } catch (JavaModelException e) { return 0; }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        List<Path> files = new ArrayList<>();
        if (javaProject == null) return files;
        try {
            for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) collectJavaFiles(root, files);
            }
        } catch (JavaModelException e) { }
        return files;
    }

    private void collectJavaFiles(IPackageFragmentRoot root, List<Path> files) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment pkg) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    IResource resource = cu.getResource();
                    if (resource != null) {
                        IPath location = resource.getLocation();
                        if (location != null) files.add(Path.of(location.toOSString()));
                    }
                }
            }
        }
    }
}
