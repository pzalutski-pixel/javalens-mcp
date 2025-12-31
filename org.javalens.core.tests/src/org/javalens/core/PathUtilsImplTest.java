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
    @DisplayName("isWithinProject should handle relative paths")
    void isWithinProject_handlesRelativePaths() {
        // Even a relative path, when normalized, should be checked properly
        Path relativePath = Path.of("some/relative/path.java");

        // This will be made absolute and normalized, then checked
        // The result depends on the current working directory
        boolean result = pathUtils.isWithinProject(relativePath);
        // Just verify it doesn't throw - result depends on CWD
        assertNotNull(Boolean.valueOf(result));
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
}
