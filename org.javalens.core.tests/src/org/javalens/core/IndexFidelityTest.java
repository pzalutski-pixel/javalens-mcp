package org.javalens.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
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
import org.javalens.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    // ========== Helpers ==========

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
