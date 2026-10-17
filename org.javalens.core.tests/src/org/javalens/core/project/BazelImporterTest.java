package org.javalens.core.project;

import org.javalens.core.fixtures.BazelBinary;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link BazelImporter}'s static read: with a Bazel binary that cannot start, the BUILD
 * files are read as Starlark code. Comments are not code, so a commented-out
 * {@code javacopts} is not read; within a list the last level flag wins; a package's level is
 * the highest of its lists.
 */
class BazelImporterTest {

    @Test
    @DisplayName("javacopts: the last of -source, --release and --release=N in a list wins; comments and strings outside the list are not read")
    void lastLevelFlagWins(@TempDir Path root) throws IOException {
        write(root, "MODULE.bazel", "module(name = \"m\")\n");
        write(root, "a/BUILD.bazel", String.join("\r\n",
            "# javacopts = [\"--release\", \"21\"]",
            "java_library(",
            "    name = \"a\",",
            "    javacopts = [\"-source\", \"11\", \"--release=17\"],  # trailing comment",
            "    tags = [\"javacopts = ['--release', '25']\"],",
            ")",
            ""));
        write(root, "b/BUILD", "java_library(name = \"b\", javacopts = [\"--release=8\", \"-source\", \"11\"])\n");

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of(".", "//a", "//b"), ids(model), "module ids"),
            () -> assertEquals(java.util.Arrays.asList(null, "17", "11"),
                model.modules().stream().map(BuildModule::declaredLevel).toList(), "declared levels"),
            () -> assertEquals("17", model.analysisLevel(), "analysis level"));
    }

    @Test
    @DisplayName("A package's level is the highest of its javacopts lists; # inside a string is not a comment")
    void highestListInAPackage(@TempDir Path root) throws IOException {
        write(root, "MODULE.bazel", "module(name = \"m\")\n");
        write(root, "BUILD.bazel", String.join("\n",
            "java_library(name = \"x\", javacopts = [\"--release\", \"11\"], tags = [\"#not-a-comment\"])",
            "java_library(name = \"y\", javacopts = [r\"--release\", '''21'''])",
            ""));

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of("//"), ids(model), "module ids"),
            () -> assertEquals("21", model.analysisLevel(), "analysis level"));
    }

    @Test
    @DisplayName("Packages are found by their BUILD files outside Bazel's bazel-* trees and ignored directories, each laid out by convention")
    void packagesLaidOutByConvention(@TempDir Path root) throws IOException {
        write(root, "MODULE.bazel", "module(name = \"m\")\n");
        write(root, "lib/BUILD.bazel", "\n");
        Files.createDirectories(root.resolve("lib/src/main/java"));
        write(root, "bazel-out/k8/BUILD", "\n");
        write(root, "node_modules/x/BUILD", "\n");

        BuildModel model = staticRead(root);

        assertEquals(List.of(
                new BuildModule(".", root, List.of(), List.of(), List.of(), null, false),
                new BuildModule("//lib", root.resolve("lib"), List.of(root.resolve("lib/src/main/java")), List.of(),
                    List.of(), null, false)),
            model.modules(), "modules");
    }

    @Test
    @DisplayName("Without any conventional layout, each package directory holding .java files is its own source root")
    void packageDirectoriesAsRoots(@TempDir Path root) throws IOException {
        write(root, "MODULE.bazel", "module(name = \"m\")\n");
        write(root, "app/BUILD", "\n");
        write(root, "app/App.java", "class App {}\n");
        write(root, "docs/BUILD", "\n");

        BuildModel model = staticRead(root);

        assertEquals(List.of(
                new BuildModule(".", root, List.of(), List.of(), List.of(), null, false),
                new BuildModule("//app", root.resolve("app"), List.of(root.resolve("app")), List.of(), List.of(), null,
                    false),
                new BuildModule("//docs", root.resolve("docs"), List.of(), List.of(), List.of(), null, false)),
            model.modules(), "modules");
    }

    @Test
    @DisplayName("A Bazel binary that cannot start gives BUILD_TOOL_UNAVAILABLE and BUILD_MODEL_STATIC after the model's own warnings")
    void unavailableBazel(@TempDir Path root) throws IOException {
        write(root, "MODULE.bazel", "module(name = \"m\")\n");
        String bazel = root.resolve("no-such-bazel").toString();

        BuildModel model;
        try (BazelBinary binary = BazelBinary.use(bazel)) {
            model = new BazelImporter().resolve(root);
        }

        BuildModel read = model;
        assertAll(
            () -> assertEquals(List.of(LoadWarning.BAZEL_NOT_BUILT, LoadWarning.BUILD_TOOL_UNAVAILABLE,
                    LoadWarning.BUILD_MODEL_STATIC),
                read.warnings().stream().map(LoadWarning::code).toList(), "warning codes"),
            () -> assertEquals("Install Bazel (or Bazelisk as 'bazel') and ensure it is on PATH for the process that "
                    + "launches JavaLens.", read.warnings().get(1).remediation(), "remediation"));
    }

    // ========== Helpers ==========

    private static BuildModel staticRead(Path root) {
        try (BazelBinary binary = BazelBinary.use(root.resolve("no-such-bazel").toString())) {
            return new BazelImporter().resolve(root);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<String> ids(BuildModel model) {
        return model.modules().stream().map(BuildModule::id).toList();
    }
}
