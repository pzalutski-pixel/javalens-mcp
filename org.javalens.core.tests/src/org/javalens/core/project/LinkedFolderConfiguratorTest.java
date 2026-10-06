package org.javalens.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-function pieces of {@link LinkedFolderConfigurator}:
 * source-layout discovery and generated-source detection. The Eclipse-coupled
 * piece (linked folder + classpath entry construction) is covered by the
 * build-system integration tests via {@code WorkspaceManagerTest.createLinkedFolder}
 * and the end-to-end fixtures.
 */
class LinkedFolderConfiguratorTest {

    private final LinkedFolderConfigurator configurator = new LinkedFolderConfigurator();

    @Test
    @DisplayName("Standard Maven layout (src/main/java, src/test/java) is discovered")
    void addSourcePaths_standardMavenLayout(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.createDirectories(projectRoot.resolve("src/test/java/com/example"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.contains(projectRoot.resolve("src/main/java")),
            "src/main/java must be discovered; got: " + sources);
        assertTrue(sources.contains(projectRoot.resolve("src/test/java")),
            "src/test/java must be discovered; got: " + sources);
        // The fallback "src" must NOT be added when standard layouts matched.
        assertFalse(sources.contains(projectRoot.resolve("src")),
            "Bare 'src' must not be added when standard layouts exist; got: " + sources);
    }

    @Test
    @DisplayName("Kotlin source layouts (src/main/kotlin, src/test/kotlin) are discovered")
    void addSourcePaths_kotlinLayout(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve("src/main/kotlin"));
        Files.createDirectories(projectRoot.resolve("src/test/kotlin"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.contains(projectRoot.resolve("src/main/kotlin")));
        assertTrue(sources.contains(projectRoot.resolve("src/test/kotlin")));
    }

    @Test
    @DisplayName("Bare 'src/' fallback is added when no standard layout exists")
    void addSourcePaths_bareSrcFallback(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve("src/com/example"));
        // No src/main/java, no src/test/java, etc.

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.contains(projectRoot.resolve("src")),
            "Bare 'src' must be the fallback when no standard layout matched; got: " + sources);
    }

    @Test
    @DisplayName("No source paths added when none of the recognized layouts exist")
    void addSourcePaths_noLayout_emptyList(@TempDir Path projectRoot) {
        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.isEmpty(),
            "Empty project root must yield empty source paths; got: " + sources);
    }

    @Test
    @DisplayName("Maven generated-sources subdirectories are discovered (one per processor)")
    void addSourcePaths_mavenGeneratedSources(@TempDir Path projectRoot) throws IOException {
        // Maven layout: each annotation processor gets its own subdir of
        // target/generated-sources/. The configurator probes one level deep so each
        // subdir becomes a separate source folder.
        Files.createDirectories(projectRoot.resolve("target/generated-sources/annotations/com"));
        Files.createDirectories(projectRoot.resolve("target/generated-sources/jpamodelgen/com"));
        Files.createDirectories(projectRoot.resolve("target/generated-test-sources/test-annotations/com"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.contains(projectRoot.resolve("target/generated-sources/annotations")),
            "Maven generated-sources/annotations must be discovered; got: " + sources);
        assertTrue(sources.contains(projectRoot.resolve("target/generated-sources/jpamodelgen")),
            "Maven generated-sources/jpamodelgen must be discovered; got: " + sources);
        assertTrue(sources.contains(projectRoot.resolve("target/generated-test-sources/test-annotations")),
            "Maven generated-test-sources must be discovered; got: " + sources);
    }

    @Test
    @DisplayName("Gradle generated sources: per task, <task>/{main,test}/java and Gradle's own <task>/java/{main,test} are discovered, in that order")
    void addSourcePaths_gradleGeneratedSourcesBothLayouts(@TempDir Path projectRoot) throws IOException {
        Path task = projectRoot.resolve("build/generated/sources/annotationProcessor");
        Files.createDirectories(task.resolve("main/java/com"));
        Files.createDirectories(task.resolve("test/java/com"));
        Files.createDirectories(task.resolve("java/main/com"));
        Files.createDirectories(task.resolve("java/test/com"));
        // Neither layout: a Kotlin leaf is not probed.
        Files.createDirectories(task.resolve("kotlin/main/com"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertEquals(List.of(
                task.resolve("main/java"),
                task.resolve("test/java"),
                task.resolve("java/main"),
                task.resolve("java/test")),
            sources);
    }

    @Test
    @DisplayName("Gradle generated sources: every <task> directory is probed, whichever layout it uses")
    void addSourcePaths_gradleGeneratedSourcesEveryTask(@TempDir Path projectRoot) throws IOException {
        Path generated = projectRoot.resolve("build/generated/sources");
        Files.createDirectories(generated.resolve("annotationProcessor/java/main/com"));
        Files.createDirectories(generated.resolve("other/main/java/com"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        // Task directories are visited in the order the file system lists them, which no
        // API fixes, so the two tasks' roots are compared as a set, with the count pinned.
        assertEquals(2, sources.size(), () -> "sources: " + sources);
        assertEquals(Set.of(generated.resolve("annotationProcessor/java/main"), generated.resolve("other/main/java")),
            Set.copyOf(sources));
    }

    @Test
    @DisplayName("Standard + generated layouts coexist (both discovered)")
    void addSourcePaths_standardAndGenerated(@TempDir Path projectRoot) throws IOException {
        // Mixed: a real project has BOTH src/main/java AND target/generated-sources/*.
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createDirectories(projectRoot.resolve("target/generated-sources/annotations"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertEquals(2, sources.size(),
            "Expected 2 source paths (src/main/java + target/generated-sources/annotations); got: " + sources);
        assertTrue(sources.contains(projectRoot.resolve("src/main/java")));
        assertTrue(sources.contains(projectRoot.resolve("target/generated-sources/annotations")));
    }

    @Test
    @DisplayName("Empty generated-sources directory contributes nothing (no subdirs to enumerate)")
    void addSourcePaths_emptyGeneratedSources(@TempDir Path projectRoot) throws IOException {
        // target/generated-sources exists but is empty — defensive: no subdirs means no
        // source paths added.
        Files.createDirectories(projectRoot.resolve("target/generated-sources"));

        List<Path> sources = new ArrayList<>();
        configurator.addSourcePathsFromDirectory(projectRoot, sources);

        assertTrue(sources.isEmpty(),
            "Empty generated-sources dir must contribute nothing; got: " + sources);
    }
}
