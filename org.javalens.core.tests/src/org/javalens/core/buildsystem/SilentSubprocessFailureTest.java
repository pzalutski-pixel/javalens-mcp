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

import static org.junit.jupiter.api.Assertions.*;

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
 * {@code MAVEN_SUBPROCESS_FAILED} appears in the warnings list.
 *
 * <p>Requires {@code mvn} on PATH (the test failure mode is "exit non-zero", not "binary
 * missing"). On a runner without Maven both failure modes still produce the same warning
 * code, so the assertion remains valid.
 */
class SilentSubprocessFailureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("mvn dependency resolution failure surfaces exactly one MAVEN_SUBPROCESS_FAILED warning")
    void mavenSubprocessFailureIsSurfaced() throws Exception {
        JdtServiceImpl service = helper.loadProject("broken-maven-deps");
        List<LoadWarning> warnings = service.getWarnings();

        // broken-maven-deps declares <maven.compiler.source>21</maven.compiler.source>, so
        // COMPLIANCE_LEVEL_UNKNOWN must NOT fire. Only the subprocess failure does.
        // Pinning the exact count catches a regression that emitted spurious extras.
        List<LoadWarning> mavenWarnings = warnings.stream()
            .filter(w -> LoadWarning.MAVEN_SUBPROCESS_FAILED.equals(w.code()))
            .toList();
        assertEquals(1, mavenWarnings.size(),
            "Expected exactly one MAVEN_SUBPROCESS_FAILED warning; got warnings: " + warnings);
        assertEquals(1, warnings.size(),
            "broken-maven-deps declares compiler.source=21, so the load surfaces ONLY "
                + "MAVEN_SUBPROCESS_FAILED. Any extra warning indicates a regression that "
                + "emitted a spurious code; got warnings: " + warnings);

        LoadWarning w = mavenWarnings.get(0);
        assertNotNull(w.message(), "Warning must include a message");
        assertFalse(w.message().isBlank(), "Warning message must not be blank");
        assertNotNull(w.remediation(), "Warning must include actionable remediation");
        assertFalse(w.remediation().isBlank(), "Warning remediation must not be blank");
        // Pin message content: source.code emits one of several forms — non-zero exit,
        // could-not-start, or timeout — every form contains "mvn" or "Maven" or the
        // "exit code" wording. Without this, a regression emitting "" or generic text
        // (e.g. just "warning") would still pass the non-blank check.
        String message = w.message().toLowerCase();
        assertTrue(message.contains("mvn") || message.contains("maven")
                || message.contains("exit code") || message.contains("could not start"),
            "MAVEN_SUBPROCESS_FAILED message must describe the subprocess failure; got: "
                + w.message());
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
