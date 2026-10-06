package org.javalens.core.fixtures;

import org.javalens.core.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link ClasspathSnapshot} value object and {@link TestProjectHelper#loadFixture}.
 *
 * <p>Most tests use {@code simple-maven} as a known baseline. Library-branch tests use a
 * temp Bazel project with a fake jar in {@code bazel-bin} — that exercises the
 * {@code CPE_LIBRARY} capture path without depending on the host having {@code mvn}
 * installed and without polluting the canonical fixture set with a "library smoke test"
 * project.
 */
class ClasspathSnapshotTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("loadFixture returns service, classpath snapshot, and warning codes from the live service")
    void loadFixtureReturnsStructuredResult() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        assertNotNull(loaded.service(), "service must be present");
        assertNotNull(loaded.classpath(), "classpath snapshot must be present");
        assertNotNull(loaded.warnings(), "warnings list must be present (may be empty)");
        // Whether warnings is empty depends on mvn availability — on a clean simple-maven
        // load with mvn present it's empty; without mvn, BUILD_TOOL_UNAVAILABLE appears.
        // Both shapes are accepted here; environment-dependent strict assertions belong in
        // tests that explicitly arrange for mvn presence/absence.
    }

    @Test
    @DisplayName("snapshot exposes JRE container")
    void snapshotExposesJreContainer() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        List<String> containers = loaded.classpath().containers();
        assertFalse(containers.isEmpty(), "expected at least the JRE container");
        assertTrue(containers.stream().anyMatch(c -> c.contains("JRE_CONTAINER")),
            "expected JRE_CONTAINER, got: " + containers);
    }

    @Test
    @DisplayName("snapshot exposes source folders")
    void snapshotExposesSourceFolders() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        List<Path> sources = loaded.classpath().sourceFolders();
        assertFalse(sources.isEmpty(), "expected at least one source folder");
        assertTrue(loaded.classpath().hasSourceFolderMatching(".*src/main/java.*"),
            "expected src/main/java source folder, got: " + sources);
    }

    @Test
    @DisplayName("snapshot exposes compiler options matching the fixture's declared Java 21")
    void snapshotExposesCompilerOptions() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        // simple-maven's pom declares <maven.compiler.source>21</maven.compiler.source>; the
        // Bug-G fix applies it to the IJavaProject. Strict value check — previously this
        // test only asserted non-null, which would pass even if JDT silently fell back to
        // the workspace default (e.g., 17 from the host JDK).
        assertEquals("21", loaded.classpath().compilerSource(),
            "compilerSource must equal simple-maven's declared <maven.compiler.source>21");
        assertEquals("21", loaded.classpath().compilerCompliance(),
            "compilerCompliance must equal simple-maven's declared level");
    }

    @Test
    @DisplayName("hasLibraryMatching returns false when no libraries match")
    void hasLibraryMatchingReturnsFalseWhenNoMatch() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        // simple-maven has no external dependencies, so this regex should never match.
        assertFalse(loaded.classpath().hasLibraryMatching(".*nonexistent-library.*"));
    }

    @Test
    @DisplayName("hasLibraryMatching returns true when at least one library matches")
    void hasLibraryMatchingReturnsTrueOnMatch() throws Exception {
        // Cover the positive branch without depending on mvn: build a minimal Bazel
        // workspace with a uniquely-named fake jar in bazel-bin. loadProject's Bazel path
        // walks bazel-bin and registers the jar as a CPE_LIBRARY entry, which is exactly
        // what capture() reads.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-with-named-jar");
        Files.createDirectories(projectRoot.resolve("bazel-bin"));
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"lib\")\n");
        Files.write(projectRoot.resolve("bazel-bin/libunique-marker-7e3f.jar"),
            new byte[]{'P','K',3,4});

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(snapshot.hasLibraryMatching(".*libunique-marker-7e3f.*"),
            "hasLibraryMatching must return true when at least one library matches the "
                + "regex; libraries: " + snapshot.libraries());
    }

    @Test
    @DisplayName("hasSourceFolderMatching returns false when no source folder matches")
    void hasSourceFolderMatchingReturnsFalseOnNoMatch() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");
        assertFalse(loaded.classpath().hasSourceFolderMatching(".*generated-sources/annotations.*"),
            "simple-maven has no generated-sources directory; matcher must return false");
    }

    @Test
    @DisplayName("libraryCountEndingWith returns 0 when no libraries end with suffix")
    void libraryCountReturnsZeroForUnknownSuffix() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        assertEquals(0, loaded.classpath().libraryCountEndingWith("nonexistent.jar"));
    }

    @Test
    @DisplayName("libraryCountEndingWith returns exact count when libraries match")
    void libraryCountReturnsExactCountWhenLibrariesMatch() throws Exception {
        // Same fixture pattern as hasLibraryMatchingReturnsTrueOnMatch. Drop two jars with
        // a common suffix to verify the count is exact, not just non-zero.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-count");
        Path binDir = projectRoot.resolve("bazel-bin");
        Files.createDirectories(binDir);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"count\")\n");
        Files.write(binDir.resolve("first-counter.jar"), new byte[]{'P','K',3,4});
        Files.write(binDir.resolve("second-counter.jar"), new byte[]{'P','K',3,4});
        Files.write(binDir.resolve("unrelated.jar"), new byte[]{'P','K',3,4});

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals(2L, snapshot.libraryCountEndingWith("-counter.jar"),
            "Two jars end with `-counter.jar`; count must be exactly 2. Libraries: "
                + snapshot.libraries());
    }

    @Test
    @DisplayName("snapshot toString does not throw and reports all six key labels")
    void snapshotToStringReportsCounts() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        String s = loaded.classpath().toString();
        assertNotNull(s);
        // Cover every key emitted by ClasspathSnapshot.toString(). Previously only three
        // of the six were asserted; a regression that dropped a label (e.g., refactor
        // that removed compilerSource from the format) would have gone unnoticed.
        for (String label : List.of(
                "sourceFolders=", "libraries=", "containers=",
                "projectRefs=", "compilerSource=", "compilerCompliance=")) {
            assertTrue(s.contains(label), "toString missing label `" + label + "`: " + s);
        }
    }

    @Test
    @DisplayName("List accessors return immutable views (mutation throws UnsupportedOperationException)")
    void listAccessorsReturnImmutableViews() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");
        ClasspathSnapshot snapshot = loaded.classpath();

        // Mutating a snapshot accessor would let a flawed test silently change shared
        // state between assertions in the same method. Document the contract via a test.
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.sourceFolders().add(Path.of("/tmp/bogus")));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.libraries().add(Path.of("/tmp/bogus.jar")));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.containers().add("bogus-container"));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.projectRefs().add("/bogus-project"));
    }
}
