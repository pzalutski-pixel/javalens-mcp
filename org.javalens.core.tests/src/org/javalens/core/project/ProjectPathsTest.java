package org.javalens.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how a path is shown relative to the project root in module ids and load warnings:
 * '/' separators, "." for the root itself, relative whenever the path shares the root's
 * file-system root (inside the project or not), and absolute on another file-system root.
 */
class ProjectPathsTest {

    @Test
    @DisplayName("A path inside the project is shown relative to the root with '/' separators")
    void pathInsideProjectIsRelative(@TempDir Path root) {
        assertAll(
            () -> assertEquals("core", ProjectPaths.relativeToRoot(root, root.resolve("core"))),
            () -> assertEquals("core/target/classes",
                ProjectPaths.relativeToRoot(root, root.resolve("core").resolve("target").resolve("classes"))));
    }

    @Test
    @DisplayName("The project root itself is shown as \".\"")
    void projectRootIsDot(@TempDir Path root) {
        assertEquals(".", ProjectPaths.relativeToRoot(root, root));
    }

    @Test
    @DisplayName("A path outside the project on the same file-system root is shown relative, normalized")
    void siblingOutsideProjectIsRelative(@TempDir Path root) {
        assertAll(
            () -> assertEquals("../sibling", ProjectPaths.relativeToRoot(root, root.resolveSibling("sibling"))),
            () -> assertEquals("../sibling",
                ProjectPaths.relativeToRoot(root, root.resolve("core").resolve("../../sibling"))));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("A path on another drive is shown absolute with '/' separators")
    void pathOnAnotherDriveIsAbsolute(@TempDir Path root) {
        // Only Windows has more than one file-system root (one per drive); on a
        // single-root file system every absolute path shares the project's root.
        String otherDrive = root.getRoot().toString().toUpperCase().startsWith("Z") ? "Y:" : "Z:";
        Path elsewhere = Path.of(otherDrive + "\\elsewhere\\classes");

        assertEquals(otherDrive + "/elsewhere/classes", ProjectPaths.relativeToRoot(root, elsewhere));
    }
}
