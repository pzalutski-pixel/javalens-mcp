package org.javalens.core;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.javalens.core.fixtures.LibraryIndexRace;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every library on a loaded project's classpath answers index-backed searches, whatever the
 * order in which JDT indexed it during the load; a library JDT cannot index at all is named
 * by LIBRARY_INDEX_INCOMPLETE.
 */
class LibraryIndexTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String REMEDIATION = "Make sure each library is a readable archive or class folder, "
        + "then reload the project; if the warning persists, restart JavaLens.";

    @Test
    @DisplayName("a first load whose JRE index JDT removes while indexing the JRE finds java.util.List in the JRE and reports no warning")
    void firstLoad_jreIndexRemovedWhileIndexed_jdkTypesFound() throws Exception {
        Path root = helper.getFixturePath("index-fidelity");
        IPath jre = jreLibrary(root);
        removeEveryJavaLensProject();
        JdtServiceImpl service = new JdtServiceImpl();

        List<String> interleaving;
        try (LibraryIndexRace race = LibraryIndexRace.arm(jre)) {
            service.loadProject(root);
            interleaving = race.events();
        }

        assertAll(
            () -> assertEquals(List.of("started", "held", "removed", "released"), interleaving),
            () -> assertEquals(List.of("java.util.List"), jreTypes(service.getJavaProject(), "java.util", "List")),
            () -> assertEquals(List.of(), service.getWarnings()));
    }

    @Test
    @DisplayName("a library that is not a zip archive is named by LIBRARY_INDEX_INCOMPLETE; a readable library beside it is not, and the JRE stays searchable")
    void unreadableLibrary_namedByWarning() throws Exception {
        Path broken = notAnArchive(helper.getTempDirectory().resolve("libs/broken.jar"));
        Path readable = readableArchive(helper.getTempDirectory().resolve("libs/readable.jar"));
        JdtServiceImpl service = new JdtServiceImpl(withLibraries(broken, readable));

        service.loadProject(helper.getFixturePath("index-fidelity"));

        assertAll(
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.LIBRARY_INDEX_INCOMPLETE,
                    "The search index of 1 library could not be built: " + shown(broken)
                        + "; search-based results do not include its types",
                    REMEDIATION)),
                service.getWarnings()),
            () -> assertEquals(List.of("java.util.List"), jreTypes(service.getJavaProject(), "java.util", "List")));
    }

    @Test
    @DisplayName("two libraries that are not zip archives are named by one LIBRARY_INDEX_INCOMPLETE, sorted")
    void unreadableLibraries_namedSorted() throws Exception {
        Path second = notAnArchive(helper.getTempDirectory().resolve("libs/b-broken.jar"));
        Path first = notAnArchive(helper.getTempDirectory().resolve("libs/a-broken.jar"));
        JdtServiceImpl service = new JdtServiceImpl(withLibraries(second, first));

        service.loadProject(helper.getFixturePath("index-fidelity"));

        assertEquals(List.of(new LoadWarning(LoadWarning.LIBRARY_INDEX_INCOMPLETE,
                "The search index of 2 libraries could not be built: " + shown(first) + ", " + shown(second)
                    + "; search-based results do not include their types",
                REMEDIATION)),
            service.getWarnings());
    }

    // ========== Helpers ==========

    /** The JRE's library on the classpath of a load of {@code root}: the JDK's jrt-fs.jar. */
    private static IPath jreLibrary(Path root) throws Exception {
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        List<IPath> jre = new ArrayList<>();
        for (IClasspathEntry entry : service.getJavaProject().getResolvedClasspath(true)) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY
                    && "jrt-fs.jar".equals(entry.getPath().lastSegment())) {
                jre.add(entry.getPath());
            }
        }
        assertEquals(1, jre.size(), "one JRE library on the classpath of " + root + ": " + jre);
        return jre.get(0);
    }

    /**
     * Remove every project a JavaLens load created, as the workspace is when JavaLens starts:
     * JDT then holds no timestamp for the JRE, and the next load configures it as a new library.
     */
    private static void removeEveryJavaLensProject() throws Exception {
        WorkspaceManager workspace = new WorkspaceManager();
        workspace.initialize();
        assertEquals(List.of(), workspace.removeCreatedProjectsExcept(null));
    }

    /** A library path as load warnings show it: outside the project root, absolute with '/' separators. */
    private static String shown(Path library) {
        return library.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** A file named like a jar whose content is not a zip archive. */
    private static Path notAnArchive(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "not a zip archive");
        return jar;
    }

    /** A zip archive holding one text entry. */
    private static Path readableArchive(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("notes.txt"));
            jarOut.write("readable".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    /** An importer whose resolved build model carries {@code libraries} after its own classpath. */
    private static ProjectImporter withLibraries(Path... libraries) {
        return new ProjectImporter() {
            @Override
            public BuildModel resolveModel(Path projectPath) {
                BuildModel model = super.resolveModel(projectPath);
                List<String> classpath = new ArrayList<>(model.classpath());
                for (Path library : libraries) {
                    classpath.add(library.toAbsolutePath().normalize().toString());
                }
                return new BuildModel(model.buildSystem(), model.provenance(), model.modules(), classpath,
                    model.processorJars(), model.analysisLevel(), model.inputs(), model.warnings());
            }
        };
    }

    /** The types named {@code typeName} in {@code packageName} that the index finds in the project's JRE. */
    private static List<String> jreTypes(IJavaProject project, String packageName, String typeName)
            throws JavaModelException {
        List<String> names = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(
            packageName.toCharArray(), SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
            typeName.toCharArray(), SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
            IJavaSearchConstants.TYPE,
            SearchEngine.createJavaSearchScope(new IJavaElement[] { project }, IJavaSearchScope.SYSTEM_LIBRARIES),
            new TypeNameRequestor() {
                @Override
                public void acceptType(int modifiers, char[] pkg, char[] simpleTypeName,
                                       char[][] enclosingTypeNames, String path) {
                    names.add(new String(pkg) + "." + new String(simpleTypeName));
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
            new NullProgressMonitor());
        return names;
    }
}
