package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestEnvironment;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.GradleImporter;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug D — Gradle classpath was unsupported.
 *
 * <p>The original {@code getGradleDependencies} returned {@code List.of()}, leaving every
 * Gradle project with an empty classpath. The fix ships an init script that registers a
 * {@code javalensWriteClasspath} task in every Java subproject and runs it via the project's
 * Gradle Wrapper (preferred) or a {@code gradle} on PATH; per-subproject classpath files
 * are then walked and unioned, mirroring the multi-module Maven approach.
 *
 * <p>These tests need a working Gradle binary and network access (Gradle resolves
 * dependencies from Maven Central on first run). They skip via {@link Assumptions} when
 * neither {@code ./gradlew} nor {@code gradle} on PATH can run; CI provisions Gradle so
 * these run live there.
 */
class GradleClasspathTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("simple Gradle project: declared external dep is on the classpath")
    void simpleGradleDependencyIsOnClasspath() throws Exception {
        runWithGradle("simple-gradle", snapshot -> {
            assertTrue(snapshot.hasLibraryMatching(".*slf4j-api.*\\.jar"),
                "Expected slf4j-api on classpath of simple-gradle. Libraries: " + snapshot.libraries());
        });
    }

    @Test
    @DisplayName("multi-project Gradle: every subproject contributes its own deps")
    void multiProjectGradleAggregatesAllSubprojects() throws Exception {
        runWithGradle("multi-project-gradle", snapshot -> {
            assertTrue(snapshot.hasLibraryMatching(".*slf4j-api.*\\.jar"),
                "Expected slf4j-api (declared in :lib) on classpath. Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*commons-lang3.*\\.jar"),
                "Expected commons-lang3 (declared in :app) on classpath. Libraries: " + snapshot.libraries());
        });
    }

    @Test
    @DisplayName("getSubprojects parses every documented include-statement syntax")
    void getSubprojectsParsesAllIncludeForms() throws Exception {
        // Pure parser test (no Gradle invocation) — fast and exercises every regex branch
        // documented on getSubprojects. The Gradle-classpath tests above use real Gradle
        // and only exercise one syntactic form (whatever the canonical fixture uses).
        Path projectRoot = helper.getTempDirectory().resolve("settings-parser");
        Files.createDirectories(projectRoot);
        Files.createDirectories(projectRoot.resolve("alpha"));
        Files.createDirectories(projectRoot.resolve("beta"));
        Files.createDirectories(projectRoot.resolve("gamma"));
        Files.createDirectories(projectRoot.resolve("delta"));
        Files.createDirectories(projectRoot.resolve("nested/inner"));

        // Four syntactic forms in one settings.gradle:
        //   include 'alpha'
        //   include "beta"
        //   include('gamma')
        //   include 'delta', ':nested:inner'
        Files.writeString(projectRoot.resolve("settings.gradle"), """
            include 'alpha'
            include "beta"
            include('gamma')
            include 'delta', ':nested:inner'
            """);

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);
        // Compare by relative path to the project root for OS-independence.
        List<String> rel = subprojects.stream()
            .map(p -> projectRoot.relativize(p).toString().replace('\\', '/'))
            .toList();
        assertEquals(List.of(), warnings, "every include resolves to an existing directory");
        assertEquals(5, subprojects.size(),
            "Expected 5 subprojects (alpha, beta, gamma, delta, nested/inner); got: " + rel);
        assertTrue(rel.contains("alpha"), "missing 'alpha' (single-quote form); got: " + rel);
        assertTrue(rel.contains("beta"), "missing 'beta' (double-quote form); got: " + rel);
        assertTrue(rel.contains("gamma"), "missing 'gamma' (include(...) form); got: " + rel);
        assertTrue(rel.contains("delta"), "missing 'delta' (multi-arg form); got: " + rel);
        assertTrue(rel.contains("nested/inner"),
            "missing 'nested/inner' (colon-notation normalized to /); got: " + rel);
    }

    @Test
    @DisplayName("getSubprojects: missing settings.gradle returns empty list, no exception")
    void getSubprojectsReturnsEmptyWhenSettingsMissing() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("no-settings");
        Files.createDirectories(projectRoot);
        List<LoadWarning> warnings = new ArrayList<>();
        assertTrue(new GradleImporter().getSubprojects(projectRoot, warnings).isEmpty(),
            "Expected empty list when settings.gradle is absent");
        assertEquals(List.of(), warnings, "no settings file declares anything to report");
    }

    @Test
    @DisplayName("getSubprojects: an include whose directory doesn't exist is skipped and reported as MODULE_NOT_LOADED")
    void getSubprojectsDropsNonexistentDirectories() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("nonexistent-include");
        Files.createDirectories(projectRoot);
        Files.createDirectories(projectRoot.resolve("real-module"));
        Files.writeString(projectRoot.resolve("settings.gradle"), """
            include 'real-module'
            include 'phantom-module'
            """);

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("real-module")), subprojects, "subprojects"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                    "settings.gradle declares 'phantom-module', but its directory phantom-module does not exist",
                    "Create the directory or remove the declaration from settings.gradle.",
                    "phantom-module")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("getSubprojects: a missing include and an include that is not a valid path are each reported in declaration order, and later includes still load")
    void getSubprojectsReportsMissingAndInvalidIncludes() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("unloadable-includes");
        Files.createDirectories(projectRoot.resolve("app"));
        // The third include's quoted name holds a NUL character, which is not a valid path
        // on any OS. 'app' is declared last, so it loads only if neither rejection aborts
        // the read.
        Files.writeString(projectRoot.resolve("settings.gradle"),
            "include 'phantom'\n"
                + "include 'bad\0name'\n"
                + "include 'app'\n");

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("app")), subprojects, "subprojects"),
            () -> assertEquals(List.of(
                    new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                        "settings.gradle declares 'phantom', but its directory phantom does not exist",
                        "Create the directory or remove the declaration from settings.gradle.",
                        "phantom"),
                    new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                        "settings.gradle declares 'bad\\0name', which is not a valid path: control character U+0000",
                        "Correct the declaration in settings.gradle.",
                        "bad\\0name")),
                warnings, "warnings"));
    }

    /**
     * Resolve a usable Gradle binary, set the {@code javalens.gradle.binary} override so
     * ProjectImporter spawns the same one, copy the fixture, load it, and run the
     * caller-supplied assertions on the resulting classpath snapshot.
     *
     * <p>Always asserts (regardless of the caller-supplied assertions): the load surfaced
     * no {@link LoadWarning#GRADLE_SUBPROCESS_FAILED}, and the init script's aux files
     * ({@code javalens-classpath.txt}, {@code javalens-compliance.txt},
     * {@code javalens-processors.txt}) were cleaned up. Both pin behaviors that the
     * single-positive-assertion tests would have silently regressed on.
     */
    private void runWithGradle(String fixtureName, java.util.function.Consumer<ClasspathSnapshot> assertions)
            throws Exception {
        String gradle = resolveGradleBinary();
        TestEnvironment.requireOrSkip(gradle, "Gradle binary (classpath aggregation)");

        String previousOverride = System.getProperty("javalens.gradle.binary");
        System.setProperty("javalens.gradle.binary", gradle);
        try {
            Path projectRoot = helper.copyFixture(fixtureName);
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            // Silent-pass: a successful Gradle build must not emit any subprocess-failed
            // warning. Without this, a regression that broke Gradle invocation entirely
            // but returned a cached-from-elsewhere jar list could still pass the
            // single-library-present check.
            List<LoadWarning> warnings = service.getWarnings();
            assertFalse(warnings.stream()
                    .anyMatch(w -> LoadWarning.GRADLE_SUBPROCESS_FAILED.equals(w.code())),
                "GRADLE_SUBPROCESS_FAILED must not fire on a successful Gradle build. "
                    + "Warnings: " + warnings);

            // Cleanup pin: the three aux files written by GRADLE_INIT_SCRIPT must be
            // removed after load. cleanupGradleClasspathFiles runs in the finally block of
            // getDependencies; a regression that skipped cleanup would leave artifacts in
            // the project tree (visible to the user as build/ litter).
            assertAuxFilesCleanedUp(projectRoot);

            assertions.accept(snapshot);
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.gradle.binary");
            else System.setProperty("javalens.gradle.binary", previousOverride);
        }
    }

    private static void assertAuxFilesCleanedUp(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            List<String> leftovers = walk
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.equals("javalens-classpath.txt")
                          || n.equals("javalens-compliance.txt")
                          || n.equals("javalens-processors.txt"))
                .toList();
            assertTrue(leftovers.isEmpty(),
                "Expected no javalens-*.txt aux files after load; cleanup did not run. "
                    + "Leftover names: " + leftovers);
        }
    }

    /**
     * Locate a Gradle binary the test can invoke. Looks in (1) the {@code JAVALENS_TEST_GRADLE_HOME}
     * env var if set, (2) the {@code gradle}/{@code gradle.bat} on PATH, (3) common extracted
     * locations: {@code ~/javalens-tools/gradle-*&#47;bin/} and the Gradle Wrapper distributions
     * cache {@code ~/.gradle/wrapper/dists/gradle-*&#47;<hash>/gradle-*&#47;bin/}.
     */
    private static String resolveGradleBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String binaryName = isWindows ? "gradle.bat" : "gradle";

        // 1. Env override
        String envHome = System.getenv("JAVALENS_TEST_GRADLE_HOME");
        if (envHome != null && !envHome.isBlank()) {
            Path bin = Path.of(envHome).resolve("bin").resolve(binaryName);
            if (Files.isRegularFile(bin)) return bin.toString();
        }

        // 2. PATH
        try {
            Process p = new ProcessBuilder(binaryName, "-v").redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            p.waitFor();
            if (p.exitValue() == 0) return binaryName;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }

        // 3. ~/javalens-tools/gradle-*/bin/
        Path tools = Path.of(System.getProperty("user.home"), "javalens-tools");
        if (Files.isDirectory(tools)) {
            try (Stream<Path> entries = Files.list(tools)) {
                for (Path entry : entries.toList()) {
                    Path bin = entry.resolve("bin").resolve(binaryName);
                    if (Files.isRegularFile(bin)) return bin.toString();
                }
            } catch (IOException ignored) {}
        }

        // 4. Gradle Wrapper distribution cache: ~/.gradle/wrapper/dists/gradle-*-{bin,all}/<hash>/gradle-*/bin/
        Path wrapperDists = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        if (Files.isDirectory(wrapperDists)) {
            try (Stream<Path> distros = Files.walk(wrapperDists, 4)) {
                return distros.filter(p -> p.getFileName() != null
                            && binaryName.equals(p.getFileName().toString()))
                        .filter(p -> p.getParent() != null
                            && "bin".equals(p.getParent().getFileName().toString()))
                        .map(Path::toString)
                        .findFirst()
                        .orElse(null);
            } catch (IOException ignored) {}
        }

        return null;
    }
}
