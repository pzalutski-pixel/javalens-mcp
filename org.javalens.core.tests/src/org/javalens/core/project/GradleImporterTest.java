package org.javalens.core.project;

import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link GradleImporter#resolve} reports a Gradle run that does not produce the
 * project model, using stand-ins for the Gradle binary. Each such run yields exactly one
 * build-tool warning, and the model falls back to the structure read statically from
 * {@code settings.gradle}: its modules laid out by convention, no classpath, no processors
 * and no level, with provenance {@link Provenance#STATIC_READ}. Gradle runs only once.
 *
 * <p>The model Gradle reports when it succeeds is pinned by {@code FidelityGradleTest}.
 */
class GradleImporterTest {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * Sleeps far longer than the tests' time limit. On Windows, ping waits one second
     * between its 31 echo requests; timeout.exe is not used because it refuses to run when
     * its input is redirected, as it is for a build tool.
     */
    private static final String SLEEPS_WINDOWS = "@echo off\nping -n 31 127.0.0.1 >nul\n";
    private static final String SLEEPS_POSIX = "#!/bin/sh\nsleep 30\n";

    /** The file beside a failing stand-in that holds one line per run. */
    private static final String RUNS_FILE = "runs.txt";

    /** Records the run in {@link #RUNS_FILE}, prints two lines and exits 1. */
    private static final String FAILS_WINDOWS = "@echo off\necho run>>\"%~dp0" + RUNS_FILE + "\"\n"
        + "echo first line\necho second line\nexit /b 1\n";
    private static final String FAILS_POSIX = "#!/bin/sh\necho run >> \"$(dirname \"$0\")/" + RUNS_FILE + "\"\n"
        + "echo first line\necho second line\nexit 1\n";

    private static final String SILENT_WINDOWS = "@echo off\nexit /b 0\n";
    private static final String SILENT_POSIX = "#!/bin/sh\nexit 0\n";

    @Test
    @DisplayName("A Gradle binary that cannot start gives exactly one BUILD_TOOL_UNAVAILABLE and the static structure")
    void binaryThatCannotStartFallsBackToStaticStructure(@TempDir Path dir) throws IOException {
        Path project = includesApp(dir.resolve("project"));
        String binary = dir.resolve("does-not-exist-binary").toString();

        BuildModel model;
        try (GradleBinary gradle = GradleBinary.use(binary)) {
            model = new GradleImporter().resolve(project);
        }

        assertEquals(1, model.warnings().size(), () -> "warnings: " + model.warnings());
        LoadWarning warning = model.warnings().get(0);
        assertAll(
            () -> assertEquals(staticModel(project, List.of(warning)), model, "model"),
            () -> assertEquals(LoadWarning.BUILD_TOOL_UNAVAILABLE, warning.code(), "warning code"),
            () -> assertEquals(null, warning.module(), "warning module"),
            () -> assertEquals("Verify the Gradle Wrapper is intact, or install Gradle and ensure 'gradle' is on PATH.",
                warning.remediation(), "warning remediation"),
            // The message ends with the OS's process-start error text, which differs per OS
            // and locale, so only the part GradleImporter composes is pinned.
            () -> assertTrue(warning.message().startsWith("Could not start '" + binary + "': "),
                () -> "warning message: " + warning.message()));
    }

    @Test
    @DisplayName("A Gradle run past the time limit gives exactly BUILD_TOOL_TIMEOUT naming the limit, and the static structure")
    void runPastTheTimeLimitFallsBackToStaticStructure(@TempDir Path dir) throws IOException {
        Path project = includesApp(dir.resolve("project"));
        String binary = fakeGradle(dir.resolve("tools"), SLEEPS_WINDOWS, SLEEPS_POSIX);

        BuildModel model;
        try (GradleBinary gradle = GradleBinary.use(binary)) {
            model = new GradleImporter(Duration.ofSeconds(2)).resolve(project);
        }

        assertEquals(staticModel(project, List.of(new LoadWarning(LoadWarning.BUILD_TOOL_TIMEOUT,
                "Gradle did not finish building the project model within 2 seconds",
                "Check that the project's build.gradle resolves correctly. Try running './gradlew help' manually in "
                    + project))),
            model);
    }

    @Test
    @DisplayName("A load interrupted while Gradle runs gives exactly BUILD_TOOL_FAILED saying so, the static structure, and keeps the interrupt")
    void interruptedRunFallsBackToStaticStructure(@TempDir Path dir) throws IOException {
        Path project = includesApp(dir.resolve("project"));
        String binary = fakeGradle(dir.resolve("tools"), SLEEPS_WINDOWS, SLEEPS_POSIX);

        BuildModel model;
        boolean interruptKept;
        try (GradleBinary gradle = GradleBinary.use(binary)) {
            Thread.currentThread().interrupt();
            try {
                model = new GradleImporter().resolve(project);
            } finally {
                // Clears the flag, so the rest of the test run is not interrupted.
                interruptKept = Thread.interrupted();
            }
        }

        boolean interruptKeptAfterResolve = interruptKept;
        assertAll(
            () -> assertEquals(staticModel(project, List.of(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "Gradle was interrupted before it finished building the project model",
                    "Reload the project to run the build tool again."))),
                model, "model"),
            () -> assertEquals(true, interruptKeptAfterResolve, "interrupt flag after resolve"));
    }

    @Test
    @DisplayName("A Gradle run that exits non-zero gives exactly BUILD_TOOL_FAILED with the exit code and last output, and the static structure without a second run")
    void nonZeroExitFallsBackToStaticStructure(@TempDir Path dir) throws IOException {
        Path project = includesApp(dir.resolve("project"));
        Path tools = dir.resolve("tools");
        String binary = fakeGradle(tools, FAILS_WINDOWS, FAILS_POSIX);

        BuildModel model;
        try (GradleBinary gradle = GradleBinary.use(binary)) {
            model = new GradleImporter().resolve(project);
        }

        List<String> runs = Files.readAllLines(tools.resolve(RUNS_FILE));
        assertAll(
            () -> assertEquals(staticModel(project, List.of(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "Gradle exited with code 1 while building the project model. Last output: first line | second line",
                    "Run './gradlew help' manually in " + project + " to see the full error."))),
                model, "model"),
            () -> assertEquals(List.of("run"), runs, "Gradle runs"));
    }

    @Test
    @DisplayName("A Gradle run that exits 0 without writing the project model gives exactly BUILD_TOOL_FAILED saying so, and the static structure")
    void exitWithoutModelFallsBackToStaticStructure(@TempDir Path dir) throws IOException {
        Path project = includesApp(dir.resolve("project"));
        String binary = fakeGradle(dir.resolve("tools"), SILENT_WINDOWS, SILENT_POSIX);

        BuildModel model;
        try (GradleBinary gradle = GradleBinary.use(binary)) {
            model = new GradleImporter().resolve(project);
        }

        assertEquals(staticModel(project, List.of(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Gradle exited with code 0, but the project model could not be read: no root build was reported",
                "Run './gradlew help' manually in " + project + " to see the full error."))),
            model);
    }

    // ========== Helpers ==========

    /**
     * A project whose {@code settings.gradle} includes {@code app}, which holds one class in
     * {@code src/main/java}.
     */
    private static Path includesApp(Path project) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve("build.gradle"), "// stub\n");
        Files.writeString(project.resolve("settings.gradle"), "include 'app'\n");
        writeSource(project.resolve("app/src/main/java"), "app", "App");
        return project;
    }

    /**
     * The structure read statically from {@link #includesApp}'s settings: the root and
     * {@code app}, each laid out by convention, followed by {@code warnings}.
     */
    private static BuildModel staticModel(Path project, List<LoadWarning> warnings) {
        return new BuildModel(BuildSystem.GRADLE, Provenance.STATIC_READ,
            List.of(
                new BuildModule(".", project, List.of(), List.of(), List.of(), null, false),
                new BuildModule("app", project.resolve("app"), List.of(project.resolve("app/src/main/java")),
                    List.of(), List.of(), null, false)),
            List.of(), List.of(), null, new BuildInputs(List.of(), List.of()), warnings);
    }

    /** Write class {@code packageName.className} under {@code sourceRoot}; returns the root. */
    private static Path writeSource(Path sourceRoot, String packageName, String className) throws IOException {
        Path packageDir = Files.createDirectories(sourceRoot.resolve(packageName));
        Files.writeString(packageDir.resolve(className + ".java"),
            "package " + packageName + ";\n\npublic class " + className + " {\n}\n");
        return sourceRoot;
    }

    /**
     * Write a stand-in for the Gradle binary into {@code dir} and return its path. On
     * Windows it is a batch file, which has no shebang and starts through cmd.exe when its
     * path is run, written with CRLF line endings as cmd.exe expects. Elsewhere it is a shell
     * script, marked executable so the process can start.
     */
    private static String fakeGradle(Path dir, String windowsBody, String posixBody) throws IOException {
        Files.createDirectories(dir);
        if (IS_WINDOWS) {
            Path script = dir.resolve("gradle.cmd");
            Files.writeString(script, windowsBody.replace("\n", "\r\n"));
            return script.toString();
        }
        Path script = dir.resolve("gradle");
        Files.writeString(script, posixBody);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script.toString();
    }
}
