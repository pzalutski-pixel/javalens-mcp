package org.javalens.core.project;

import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link ConventionalFolders#keepUndeclared}: a folder the conventional layout probes
 * find in a module's directory, but that no module of the build reports, stays linked
 * after the module's reported roots and is named by one
 * {@link LoadWarning#INCLUDED_OUTSIDE_BUILD}.
 */
class ConventionalFoldersTest {

    private static final String REMEDIATION = "Declare it as a source directory in the build, or remove it.";

    @Test
    @DisplayName("An undeclared src/main/java is kept after the reported root, with one INCLUDED_OUTSIDE_BUILD")
    void undeclaredStandardLayoutIsKept(@TempDir Path root) throws IOException {
        Path app = root.resolve("app");
        Path declared = Files.createDirectories(app.resolve("src/java"));
        Path undeclared = Files.createDirectories(app.resolve("src/main/java"));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root,
            List.of(module(":app", app, List.of(declared), List.of())), warnings);

        assertAll(
            () -> assertEquals(List.of(module(":app", app, List.of(declared, undeclared), List.of())), kept, "modules"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                    "app/src/main/java is linked because it holds a conventional source layout, "
                        + "but the build does not compile it as part of module ':app'",
                    REMEDIATION, ":app")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("Conventional folders the build reports are left as they are, with no warning")
    void reportedStandardLayoutsAreUnchanged(@TempDir Path root) throws IOException {
        Path main = Files.createDirectories(root.resolve("src/main/java"));
        Path test = Files.createDirectories(root.resolve("src/test/java"));
        List<BuildModule> modules = List.of(module(":", root, List.of(main, test), List.of()));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root, modules, warnings);

        assertAll(
            () -> assertEquals(modules, kept, "modules"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("A module that reports no source root keeps its src/ fallback, with one INCLUDED_OUTSIDE_BUILD")
    void srcFallbackOfAModuleWithoutRootsIsKept(@TempDir Path root) throws IOException {
        Path src = Files.createDirectories(root.resolve("src/pkg"));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root,
            List.of(module(":", root, List.of(), List.of())), warnings);

        Path srcRoot = src.getParent();
        assertAll(
            () -> assertEquals(List.of(module(":", root, List.of(srcRoot), List.of())), kept, "modules"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                    "src is linked because it holds a conventional source layout, "
                        + "but the build does not compile it as part of module ':'",
                    REMEDIATION, ":")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("A module that reports only a generated root still keeps its src/ fallback, as the layout probe always did")
    void srcFallbackBesideOnlyAGeneratedRootIsKept(@TempDir Path root) throws IOException {
        Path src = Files.createDirectories(root.resolve("src/pkg")).getParent();
        Path generated = Files.createDirectories(root.resolve("target/generated-sources/annotations"));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root,
            List.of(module(":", root, List.of(), List.of(generated))), warnings);

        assertAll(
            () -> assertEquals(List.of(module(":", root, List.of(src), List.of(generated))), kept, "modules"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                    "src is linked because it holds a conventional source layout, "
                        + "but the build does not compile it as part of module ':'",
                    REMEDIATION, ":")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("A module that reports a root under its directory gets no src/ fallback, so no root is nested in another")
    void noSrcFallbackBesideAReportedRoot(@TempDir Path root) throws IOException {
        Path declared = Files.createDirectories(root.resolve("src/java"));
        List<BuildModule> modules = List.of(module(":", root, List.of(declared), List.of()));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root, modules, warnings);

        assertAll(
            () -> assertEquals(modules, kept, "modules"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("An unreported generated-source folder is kept after the reported generated root, with one INCLUDED_OUTSIDE_BUILD")
    void undeclaredGeneratedFolderIsKept(@TempDir Path root) throws IOException {
        Path reported = Files.createDirectories(root.resolve("build/generated/sources/annotationProcessor/java/main"));
        Path unreported = Files.createDirectories(root.resolve("target/generated-sources/annotations"));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root,
            List.of(module(":", root, List.of(), List.of(reported))), warnings);

        assertAll(
            () -> assertEquals(List.of(module(":", root, List.of(), List.of(reported, unreported))), kept, "modules"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                    "target/generated-sources/annotations is linked because it holds a conventional source layout, "
                        + "but the build does not compile it as part of module ':'",
                    REMEDIATION, ":")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("Conventional folders inside a root the build reports are not kept and get no warning: the reported root holds their files")
    void foldersInsideAReportedRootAreNotKept(@TempDir Path root) throws IOException {
        Path declared = root.resolve("src");
        Files.createDirectories(declared.resolve("main/java"));
        Files.createDirectories(declared.resolve("test/kotlin"));
        List<BuildModule> modules = List.of(module(":", root, List.of(declared), List.of()));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root, modules, warnings);

        assertAll(
            () -> assertEquals(modules, kept, "modules"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("A conventional folder another module reports is not added again and gets no warning")
    void folderReportedByAnotherModuleIsNotAddedAgain(@TempDir Path root) throws IOException {
        Path lib = root.resolve("lib");
        Path libSources = Files.createDirectories(lib.resolve("src/main/java"));
        // The root project compiles lib's sources itself; lib reports none.
        List<BuildModule> modules = List.of(
            module(":", root, List.of(libSources), List.of()),
            module(":lib", lib, List.of(), List.of()));
        List<LoadWarning> warnings = new ArrayList<>();

        List<BuildModule> kept = ConventionalFolders.keepUndeclared(root, modules, warnings);

        assertAll(
            () -> assertEquals(modules, kept, "modules"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    private static BuildModule module(String id, Path dir, List<Path> sourceRoots, List<Path> generatedRoots) {
        return new BuildModule(id, dir, sourceRoots, generatedRoots, List.of(dir.resolve("out")), "17", true);
    }
}
