package org.javalens.core.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract test for {@link ProjectTree#relativePaths}. */
class ProjectTreeTest {

    @Test
    @DisplayName("relativePaths lists every file and directory under the root, relative with '/', sorted, without the root")
    void listsFilesAndDirectoriesRelativeToTheRoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        Files.createDirectories(root.resolve("empty"));
        Files.writeString(root.resolve("a/b/File.java"), "class File {}\n");
        Files.writeString(root.resolve("top.txt"), "top\n");

        Set<String> paths = ProjectTree.relativePaths(root);

        assertEquals(List.of("a", "a/b", "a/b/File.java", "empty", "top.txt"), List.copyOf(paths));
    }

    @Test
    @DisplayName("relativePaths of an empty directory is empty")
    void emptyRootListsNothing(@TempDir Path root) throws IOException {
        assertEquals(Set.of(), ProjectTree.relativePaths(root));
    }
}
