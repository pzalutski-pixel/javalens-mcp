package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
    @DisplayName("mvn dependency resolution failure surfaces a MAVEN_SUBPROCESS_FAILED warning")
    void mavenSubprocessFailureIsSurfaced() throws Exception {
        JdtServiceImpl service = helper.loadProject("broken-maven-deps");
        List<LoadWarning> warnings = service.getWarnings();

        assertFalse(warnings.isEmpty(),
            "Expected at least one warning when mvn dependency resolution fails. " +
            "Today this list is empty — that is Bug X.");

        boolean hasMavenWarning = warnings.stream()
            .anyMatch(w -> LoadWarning.MAVEN_SUBPROCESS_FAILED.equals(w.code()));
        assertTrue(hasMavenWarning,
            "Expected a MAVEN_SUBPROCESS_FAILED warning. Got: " + warnings);

        // Sanity: the warning includes a remediation hint
        warnings.stream()
            .filter(w -> LoadWarning.MAVEN_SUBPROCESS_FAILED.equals(w.code()))
            .findFirst()
            .ifPresent(w -> {
                assertNotNull(w.remediation(), "Warning must include actionable remediation");
                assertNotNull(w.message(), "Warning must include a message");
            });
    }

    @Test
    @DisplayName("clean Maven project produces no warnings")
    void cleanProjectProducesNoWarnings() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        List<LoadWarning> warnings = service.getWarnings();

        assertTrue(warnings.isEmpty(),
            "Expected no warnings for the clean simple-maven fixture. Got: " + warnings);
    }
}
