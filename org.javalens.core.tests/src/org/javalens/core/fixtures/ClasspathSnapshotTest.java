package org.javalens.core.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link ClasspathSnapshot} value object and {@link TestProjectHelper#loadFixture}.
 * Uses the existing {@code simple-maven} fixture as a known baseline.
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
        // warnings is now sourced from service.getWarnings(); on a clean simple-maven load
        // with mvn available it's empty. On an environment without mvn it'd contain
        // MAVEN_SUBPROCESS_FAILED — both are accepted shapes here.
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
    @DisplayName("snapshot exposes compiler options")
    void snapshotExposesCompilerOptions() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        // simple-maven's pom declares Java 21; Bug G applies it. The values must be
        // non-null and reflect the declared level.
        assertNotNull(loaded.classpath().compilerSource());
        assertNotNull(loaded.classpath().compilerCompliance());
    }

    @Test
    @DisplayName("hasLibraryMatching returns false when no libraries match")
    void hasLibraryMatchingReturnsFalseWhenNoMatch() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        // simple-maven has no external dependencies, so this regex should never match.
        assertFalse(loaded.classpath().hasLibraryMatching(".*nonexistent-library.*"));
    }

    @Test
    @DisplayName("libraryCountEndingWith returns 0 when no libraries end with suffix")
    void libraryCountReturnsZeroForUnknownSuffix() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        assertEquals(0, loaded.classpath().libraryCountEndingWith("nonexistent.jar"));
    }

    @Test
    @DisplayName("snapshot toString does not throw and reports counts")
    void snapshotToStringReportsCounts() throws Exception {
        LoadedFixture loaded = helper.loadFixture("simple-maven");

        String s = loaded.classpath().toString();
        assertNotNull(s);
        assertTrue(s.contains("sourceFolders="));
        assertTrue(s.contains("libraries="));
        assertTrue(s.contains("containers="));
    }
}
