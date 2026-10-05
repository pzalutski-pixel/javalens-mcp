package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how a module directory is built from a name a build file declares. A declaration
 * either yields its existing directory and no warning, or yields nothing and exactly one
 * {@link LoadWarning#MODULE_NOT_LOADED} naming the declaring file, the declared name with
 * control characters escaped, and why it was not loaded.
 *
 * <p>Names holding a control character are rejected before any path is built, so the
 * outcome is the same on every OS. Inputs hold real control characters written with Java
 * escapes; expected names and messages hold their escaped text.
 */
class DeclaredPathsTest {

    @Test
    @DisplayName("A name holding CR is rejected as an invalid path naming U+000D, with CR escaped as \\r")
    void carriageReturnRejected(@TempDir Path root) {
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("pom.xml"), "bad\rname", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(invalid("pom.xml", "bad\\rname", "control character U+000D")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("A name holding LF is rejected as an invalid path naming U+000A, with LF escaped as \\n")
    void lineFeedRejected(@TempDir Path root) {
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.gradleProjectDir(root, root.resolve("settings.gradle"), "bad\nname", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(invalid("settings.gradle", "bad\\nname", "control character U+000A")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("A name holding NUL is rejected as an invalid path naming U+0000, with NUL escaped as \\0")
    void nulRejected(@TempDir Path root) {
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.gradleProjectDir(root, root.resolve("settings.gradle"), "bad\0name", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(invalid("settings.gradle", "bad\\0name", "control character U+0000")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("The reason names the first control character; tab is escaped as \\t, others as uppercase \\uXXXX")
    void firstControlCharacterNamedAndOthersEscaped(@TempDir Path root) {
        // ESC (U+001B) precedes the tab, so it is the one the reason names.
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("pom.xml"), "x\u001By\tz", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(invalid("pom.xml", "x\\u001By\\tz", "control character U+001B")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("A name the OS rejects as a path is reported with the JDK's reason; where the OS accepts it, its missing directory is reported")
    void osInvalidPathReportedWithJdkReason(@TempDir Path root) {
        // '|' is reserved in Windows paths and an ordinary file-name character on Linux and
        // macOS, so the expected outcome is fixed per OS.
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("pom.xml"), "a|b", warnings);

        LoadWarning expected = windows
            ? invalid("pom.xml", "a|b", "Illegal char <|>")
            : missing("pom.xml", "a|b", "a|b");
        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(expected), warnings, "warnings"));
    }

    @Test
    @DisplayName("A valid name whose directory does not exist is reported with its directory relative to the project root")
    void missingDirectoryReported(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("core/pom.xml"), "gone", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(missing("core/pom.xml", "gone", "core/gone")), warnings, "warnings"));
    }

    @Test
    @DisplayName("A name that resolves to a regular file is reported as a missing directory")
    void regularFileIsNotADirectory(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("notes"), "not a module\n");
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("pom.xml"), "notes", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(missing("pom.xml", "notes", "notes")), warnings, "warnings"));
    }

    @Test
    @DisplayName("A name whose directory exists yields that directory, resolved against the declaring file's directory, and no warning")
    void existingDirectoryReturned(@TempDir Path root) throws IOException {
        Path inner = Files.createDirectories(root.resolve("core/inner"));
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.mavenModuleDir(root, root.resolve("core/pom.xml"), "inner", warnings);

        assertAll(
            () -> assertEquals(Optional.of(inner), dir, "directory"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("Gradle colon notation :nested:inner resolves to nested/inner under the settings directory")
    void gradleColonNotationResolves(@TempDir Path root) throws IOException {
        Path nestedInner = Files.createDirectories(root.resolve("nested/inner"));
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.gradleProjectDir(root, root.resolve("settings.gradle"), ":nested:inner", warnings);

        assertAll(
            () -> assertEquals(Optional.of(nestedInner), dir, "directory"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("A missing Gradle project in colon notation is reported with its colon-normalized directory")
    void gradleColonNotationMissingReported(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("nested"));
        List<LoadWarning> warnings = new ArrayList<>();

        Optional<Path> dir = DeclaredPaths.gradleProjectDir(root, root.resolve("settings.gradle"), ":nested:gone", warnings);

        assertAll(
            () -> assertEquals(Optional.empty(), dir, "directory"),
            () -> assertEquals(List.of(missing("settings.gradle", ":nested:gone", "nested/gone")),
                warnings, "warnings"));
    }

    private static LoadWarning invalid(String declaringFile, String escapedName, String reason) {
        return new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
            declaringFile + " declares '" + escapedName + "', which is not a valid path: " + reason,
            "Correct the declaration in " + declaringFile + ".",
            escapedName);
    }

    private static LoadWarning missing(String declaringFile, String escapedName, String relativeDir) {
        return new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
            declaringFile + " declares '" + escapedName + "', but its directory " + relativeDir + " does not exist",
            "Create the directory or remove the declaration from " + declaringFile + ".",
            escapedName);
    }
}
