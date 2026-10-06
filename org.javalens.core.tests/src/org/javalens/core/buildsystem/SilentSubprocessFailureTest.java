package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestEnvironment;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug X — silent subprocess failure.
 *
 * <p>The original {@code ProjectImporter.getMavenDependencies()} caught all subprocess
 * failures (mvn-not-on-PATH, exit code != 0, timeout) and returned an empty classpath
 * with no signal to callers. Issue #4 was a direct consequence: the user's response
 * showed {@code classpathEntryCount: 4} (only the JRE container + a couple of fallback
 * dirs) because the GUI shell that launched JavaLens didn't have {@code mvn} on PATH.
 *
 * <p>The fix accumulates {@link LoadWarning}s in {@link JdtServiceImpl} and surfaces them
 * via {@code IJdtService.getWarnings()}, which {@code LoadProjectTool} includes in its
 * response.
 *
 * <p>This fixture deliberately declares a non-existent dependency so {@code mvn
 * dependency:build-classpath} exits non-zero. The test asserts that
 * {@code BUILD_TOOL_FAILED} is the load's one warning.
 *
 * <p>Requires {@code mvn} on PATH: the contract is a build tool that runs and fails.
 * Without Maven the load reports {@code BUILD_TOOL_UNAVAILABLE} instead, which
 * {@code MavenImporterTest} pins.
 */
class SilentSubprocessFailureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("mvn dependency resolution failure surfaces exactly one BUILD_TOOL_FAILED warning naming the exit code")
    void mavenSubprocessFailureIsSurfaced() throws Exception {
        TestEnvironment.requireOrSkip(isMavenAvailable(),
            "Maven binary on PATH (build tool that runs and fails)");

        JdtServiceImpl service = helper.loadProject("broken-maven-deps");
        List<LoadWarning> warnings = service.getWarnings();

        // broken-maven-deps declares <maven.compiler.source>21</maven.compiler.source>, so
        // COMPLIANCE_LEVEL_UNKNOWN must NOT fire. Only the build-tool failure does.
        assertEquals(List.of(LoadWarning.BUILD_TOOL_FAILED),
            warnings.stream().map(LoadWarning::code).toList(), "warning codes");

        LoadWarning w = warnings.get(0);
        assertAll(
            () -> assertEquals(null, w.module(), "warning module"),
            () -> assertEquals("Run 'mvn dependency:build-classpath' manually in " + service.getProjectRoot()
                    + " to see the full error.",
                w.remediation(), "warning remediation"),
            // Maven exits with 1 when the build fails. The message ends with the last lines
            // of Maven's own error output, whose wording belongs to Maven, so only the part
            // MavenImporter composes is pinned.
            () -> assertTrue(w.message().startsWith("mvn dependency:build-classpath exited with code 1. Last output: "),
                () -> "warning message: " + w.message()));
    }

    @Test
    @DisplayName("clean Maven project produces no warnings")
    void cleanProjectProducesNoWarnings() throws Exception {
        // This assertion only makes sense when mvn can actually run. Without mvn on PATH
        // the warning system fires (correctly) on every load. Local dev without mvn skips;
        // CI sets JAVALENS_TESTS_REQUIRE_TOOLS=true so missing tools fail loudly there.
        TestEnvironment.requireOrSkip(isMavenAvailable(),
            "Maven binary on PATH (clean-project warnings assertion)");

        JdtServiceImpl service = helper.loadProject("simple-maven");
        List<LoadWarning> warnings = service.getWarnings();

        assertTrue(warnings.isEmpty(),
            "Expected no warnings for the clean simple-maven fixture. Got: " + warnings);
    }

    private static boolean isMavenAvailable() {
        String command = System.getProperty("os.name").toLowerCase().contains("win")
            ? "mvn.cmd" : "mvn";
        try {
            Process p = new ProcessBuilder(command, "-v").redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
