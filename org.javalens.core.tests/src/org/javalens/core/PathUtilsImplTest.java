package org.javalens.core;

import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PathUtilsImpl.
 * Tests path formatting, relative path calculation, and project boundary detection.
 */
class PathUtilsImplTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private Path projectRoot;
    private PathUtilsImpl pathUtils;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = helper.getFixturePath("simple-maven");
        pathUtils = new PathUtilsImpl(projectRoot);
    }

    // ========== formatPath Tests ==========

    @Test
    @DisplayName("formatPath should convert backslashes to forward slashes")
    void formatPath_convertsBackslashToForwardSlash() {
        // The path formatting should use forward slashes consistently
        Path testPath = projectRoot.resolve("src/main/java/com/example");
        String formatted = pathUtils.formatPath(testPath);

        assertFalse(formatted.contains("\\"),
            "Formatted path should not contain backslashes: " + formatted);
        assertTrue(formatted.contains("/"),
            "Formatted path should contain forward slashes: " + formatted);
    }

    @Test
    @DisplayName("formatPath should make paths relative to project root")
    void formatPath_makesPathsRelativeToProjectRoot() {
        // When not using absolute paths, should make relative to project root
        Path subPath = projectRoot.resolve("src/main/java/com/example/Calculator.java");
        String formatted = pathUtils.formatPath(subPath);

        // Should be relative (not start with drive letter on Windows or / on Unix)
        if (!pathUtils.isUsingAbsolutePaths()) {
            assertEquals("src/main/java/com/example/Calculator.java", formatted);
        }
    }

    @Test
    @DisplayName("formatPath should return absolute path when outside project")
    void formatPath_returnsAbsoluteWhenOutsideProject() {
        // Path outside project root should remain absolute
        Path externalPath = Path.of(System.getProperty("java.io.tmpdir"), "external-file.java");
        String formatted = pathUtils.formatPath(externalPath);

        // Should be absolute since it's outside project
        assertTrue(formatted.startsWith("/") || formatted.contains(":"),
            "External path should be absolute: " + formatted);
    }

    @Test
    @DisplayName("formatPath should handle string input")
    void formatPath_handlesStringInput() {
        String pathString = projectRoot.resolve("pom.xml").toString();
        String formatted = pathUtils.formatPath(pathString);

        assertNotNull(formatted);
        assertFalse(formatted.contains("\\"), "Should convert backslashes");
    }

    // ========== getProjectRoot Tests ==========

    @Test
    @DisplayName("getProjectRoot should return normalized path")
    void getProjectRoot_returnsNormalizedPath() {
        Path root = pathUtils.getProjectRoot();

        assertNotNull(root, "Project root should not be null");
        assertTrue(root.isAbsolute(), "Project root should be absolute");
        assertEquals(root, root.normalize(), "Project root should be normalized");
    }

    // ========== resolve Tests ==========

    @Test
    @DisplayName("resolve should resolve relative paths against project root")
    void resolve_resolvesRelativeToRoot() {
        Path resolved = pathUtils.resolve("src/main/java");

        assertNotNull(resolved);
        assertTrue(resolved.isAbsolute(), "Resolved path should be absolute");
        assertTrue(resolved.startsWith(projectRoot),
            "Resolved path should be under project root");
        assertTrue(resolved.endsWith(Path.of("src/main/java")),
            "Resolved path should end with the relative path");
    }

    @Test
    @DisplayName("resolve should normalize the result")
    void resolve_normalizesResult() {
        Path resolved = pathUtils.resolve("src/../src/main/java");

        // Should be normalized (no .. components)
        assertFalse(resolved.toString().contains(".."),
            "Resolved path should be normalized");
    }

    // ========== isWithinProject Tests ==========

    @Test
    @DisplayName("isWithinProject should return true for paths within project")
    void isWithinProject_returnsTrueForProjectPaths() {
        Path insidePath = projectRoot.resolve("src/main/java/com/example/Calculator.java");

        assertTrue(pathUtils.isWithinProject(insidePath),
            "Path inside project should be within project");
    }

    @Test
    @DisplayName("isWithinProject should return false for paths outside project")
    void isWithinProject_returnsFalseForExternalPaths() {
        Path externalPath = Path.of(System.getProperty("java.io.tmpdir"), "external.java");

        assertFalse(pathUtils.isWithinProject(externalPath),
            "Path outside project should not be within project");
    }

    @Test
    @DisplayName("isWithinProject normalizes a relative path to absolute before the check")
    void isWithinProject_handlesRelativePaths() {
        // The source normalizes relative paths via toAbsolutePath(), so the relative
        // path is resolved against the JVM's working directory. We don't control CWD
        // in a JUnit run, so we can't pin the boolean result. What WE pin is:
        //  (a) the call returns without throwing
        //  (b) the result equals what we'd compute by manually normalizing the path
        // Previous assertion `assertNotNull(Boolean.valueOf(result))` was tautological —
        // Boolean.valueOf(boolean) returns a non-null wrapper unconditionally; the
        // assertion could never fail.
        Path relativePath = Path.of("some/relative/path.java");
        Path manuallyNormalized = relativePath.toAbsolutePath().normalize();
        boolean expected = manuallyNormalized.startsWith(projectRoot);
        boolean actual = pathUtils.isWithinProject(relativePath);
        assertEquals(expected, actual,
            "isWithinProject must equal startsWith on the absolute-normalized path; "
                + "relativePath=" + relativePath + ", normalized=" + manuallyNormalized
                + ", projectRoot=" + projectRoot);
    }

    // ========== isWindows Tests ==========

    @Test
    @DisplayName("isWindows should detect operating system correctly")
    void isWindows_detectsOperatingSystem() {
        boolean isWindows = PathUtilsImpl.isWindows();
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            assertTrue(isWindows, "Should detect Windows OS");
        } else {
            assertFalse(isWindows, "Should detect non-Windows OS");
        }
    }

    // ========== isUsingAbsolutePaths Tests ==========

    @Test
    @DisplayName("isUsingAbsolutePaths should respect environment variable")
    void isUsingAbsolutePaths_reflectsEnvironment() {
        // Default should be false (relative paths preferred)
        // unless JAVALENS_ABSOLUTE_PATHS env var is set
        boolean usesAbsolute = pathUtils.isUsingAbsolutePaths();

        String envValue = System.getenv("JAVALENS_ABSOLUTE_PATHS");
        if ("true".equalsIgnoreCase(envValue)) {
            assertTrue(usesAbsolute);
        } else {
            assertFalse(usesAbsolute);
        }
    }

    @Test
    @DisplayName("formatPath against an unrelated absolute path produces an absolute string with forward slashes")
    void formatPath_unrelatedAbsolutePath() {
        // Project-relative branch is covered above; this test covers the
        // "absolute path outside project" branch with a stricter check than the existing
        // formatPath_returnsAbsoluteWhenOutsideProject (which only verified the format).
        Path absoluteOutside = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
            .resolve("javalens-pathutils-marker.java");
        String formatted = pathUtils.formatPath(absoluteOutside);
        // The unrelated absolute path normalizes to itself (forward-slashed). Comparing
        // textually because no relativization should occur.
        String expected = absoluteOutside.toAbsolutePath().normalize().toString()
            .replace('\\', '/');
        assertEquals(expected, formatted,
            "Unrelated absolute path must format as the absolute normalized form with "
                + "forward slashes; got: " + formatted);
    }

    @Test
    @DisplayName("getProjectRoot returns the constructor's normalized argument (idempotence)")
    void getProjectRoot_returnsConstructorArgument() {
        // The constructor calls toAbsolutePath().normalize() on its input. A second
        // PathUtilsImpl built with the SAME projectRoot must report the same getProjectRoot,
        // bit-for-bit. Pinning this avoids a regression that e.g. accidentally double-
        // normalizes through a different code path.
        PathUtilsImpl another = new PathUtilsImpl(projectRoot);
        assertEquals(pathUtils.getProjectRoot(), another.getProjectRoot(),
            "Two PathUtilsImpl built from the same root must report equal roots");
        assertEquals(projectRoot.toAbsolutePath().normalize(), pathUtils.getProjectRoot(),
            "getProjectRoot must equal constructor argument after toAbsolutePath().normalize()");
    }

    @Test
    @DisplayName("formatPath returns absolute path when useAbsolutePaths=true, even for paths under the project root")
    void formatPath_absoluteBranch_pathUnderProject() {
        // useAbsolutePaths=true short-circuits the project-relative branch.
        // A path that WOULD have been relativized (because it's under projectRoot) stays
        // absolute. Exercised via the package-private test constructor that bypasses
        // the env-var read.
        PathUtilsImpl absolute = new PathUtilsImpl(projectRoot, true);
        Path under = projectRoot.resolve("src/main/java/com/example/Calculator.java");
        String formatted = absolute.formatPath(under);

        String expected = under.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertEquals(expected, formatted,
            "useAbsolutePaths=true must NOT relativize a path under projectRoot; got: " + formatted);
        assertTrue(absolute.isUsingAbsolutePaths());
    }

    @Test
    @DisplayName("formatPath always uses forward slashes regardless of useAbsolutePaths setting")
    void formatPath_forwardSlashes_independentOfBranch() {
        // Both branches replace backslashes — pin that the forward-slash invariant
        // holds in the absolute branch too. Previous coverage only verified it for the
        // relative branch.
        PathUtilsImpl absolute = new PathUtilsImpl(projectRoot, true);
        Path under = projectRoot.resolve("src/main/java/com/example/Calculator.java");
        assertFalse(absolute.formatPath(under).contains("\\"),
            "Even with useAbsolutePaths=true the formatter must produce forward slashes");
    }
}
