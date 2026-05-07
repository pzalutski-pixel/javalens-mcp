package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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

    /**
     * Resolve a usable Gradle binary, set the {@code javalens.gradle.binary} override so
     * ProjectImporter spawns the same one, copy the fixture, load it, and run the
     * caller-supplied assertions on the resulting classpath snapshot.
     */
    private void runWithGradle(String fixtureName, java.util.function.Consumer<ClasspathSnapshot> assertions)
            throws Exception {
        String gradle = resolveGradleBinary();
        Assumptions.assumeTrue(gradle != null,
            "No Gradle binary available — install Gradle or commit a Gradle Wrapper");

        String previousOverride = System.getProperty("javalens.gradle.binary");
        System.setProperty("javalens.gradle.binary", gradle);
        try {
            Path projectRoot = helper.copyFixture(fixtureName);
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());
            assertions.accept(snapshot);
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.gradle.binary");
            else System.setProperty("javalens.gradle.binary", previousOverride);
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
