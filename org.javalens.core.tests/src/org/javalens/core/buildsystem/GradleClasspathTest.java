package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.ProjectTree;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.GradleImporter;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Gradle classpath and the static {@code settings.gradle} read.
 *
 * <p>The classpath tests run Gradle on a copy of a fixture and pin the exact jars the
 * load puts on the classpath: the external artifacts every project resolves, without a
 * jar for any project of the build, whose sources are linked. They need a Gradle binary
 * (see {@link GradleBinary}) and the fixtures' dependencies in Gradle's cache or reachable
 * from Maven Central.
 *
 * <p>The {@link GradleImporter#getSubprojects} tests pin the static read the importer
 * falls back to when Gradle does not report the model.
 */
class GradleClasspathTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("simple Gradle project: the classpath is exactly the declared external jar")
    void simpleGradleDependencyIsOnClasspath() throws Exception {
        runWithGradle("simple-gradle", snapshot ->
            assertEquals(List.of("slf4j-api-2.0.9.jar"), libraryNames(snapshot), "library file names"));
    }

    @Test
    @DisplayName("multi-project Gradle: the classpath is exactly every subproject's external jars, with no jar for :lib")
    void multiProjectGradleAggregatesAllSubprojects() throws Exception {
        // :lib declares api slf4j-api; :app declares commons-lang3 and a project dependency
        // on :lib, whose sources are linked.
        runWithGradle("multi-project-gradle", snapshot ->
            assertEquals(List.of("commons-lang3-3.13.0.jar", "slf4j-api-2.0.9.jar"), libraryNames(snapshot),
                "library file names"));
    }

    @Test
    @DisplayName("getSubprojects parses every documented include-statement syntax, in declaration order")
    void getSubprojectsParsesAllIncludeForms() throws Exception {
        // Pure parser test (no Gradle invocation) covering every form documented on
        // getSubprojects.
        Path projectRoot = helper.getTempDirectory().resolve("settings-parser");
        Files.createDirectories(projectRoot);
        Files.createDirectories(projectRoot.resolve("alpha"));
        Files.createDirectories(projectRoot.resolve("beta"));
        Files.createDirectories(projectRoot.resolve("gamma"));
        Files.createDirectories(projectRoot.resolve("delta"));
        Files.createDirectories(projectRoot.resolve("nested/inner"));

        // Four syntactic forms in one settings.gradle:
        //   include 'alpha'
        //   include "beta"
        //   include('gamma')
        //   include 'delta', ':nested:inner'
        Files.writeString(projectRoot.resolve("settings.gradle"), """
            include 'alpha'
            include "beta"
            include('gamma')
            include 'delta', ':nested:inner'
            """);

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(
                    projectRoot.resolve("alpha"),
                    projectRoot.resolve("beta"),
                    projectRoot.resolve("gamma"),
                    projectRoot.resolve("delta"),
                    projectRoot.resolve("nested/inner")),
                subprojects, "subprojects"),
            () -> assertEquals(List.of(), warnings, "every include resolves to an existing directory"));
    }

    @Test
    @DisplayName("getSubprojects reads Groovy settings as code: includes inside comments and strings are not declarations, and a block comment ends at its first */")
    void getSubprojectsIgnoresGroovyCommentsAndStrings() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("groovy-comments");
        for (String dir : List.of("blockGhost", "lineGhost", "afterInner", "stringGhost", "tripleGhost", "app")) {
            Files.createDirectories(projectRoot.resolve(dir));
        }
        // Every ghost directory exists, so only reading it as code keeps it out. Groovy block
        // comments do not nest, so the include after the inner "*/" is code.
        Files.write(projectRoot.resolve("settings.gradle"), String.join("\r\n",
            "/* the 'include' method",
            "include 'blockGhost' */",
            "// include 'lineGhost'",
            "/* outer /* inner */ include 'afterInner'",
            "def note = \"include 'stringGhost'\"",
            "def multi = '''include 'tripleGhost' '''",
            "include 'app'",
            "").getBytes(StandardCharsets.UTF_8));

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("afterInner"), projectRoot.resolve("app")),
                subprojects, "subprojects"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("getSubprojects reads Kotlin settings as code: nested block comments, raw strings and line comments declare nothing, and a literal projectDir places its project")
    void getSubprojectsIgnoresKotlinCommentsAndStrings() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("kotlin-comments");
        for (String dir : List.of("innerGhost", "nestedGhost", "rawGhost", "lineGhost", "app",
                "modules/app-impl", "nested/inner")) {
            Files.createDirectories(projectRoot.resolve(dir));
        }
        // Kotlin block comments nest, so the include after the inner "*/" is still comment.
        // The app directory exists, so only the projectDir assignment moves app elsewhere.
        Files.write(projectRoot.resolve("settings.gradle.kts"), String.join("\r\n",
            "/* outer /* inner include(\"innerGhost\") */ include(\"nestedGhost\") */",
            "val note = \"\"\"include(\"rawGhost\")\"\"\"",
            "// include(\"lineGhost\")",
            "include(\"app\", \":nested:inner\")",
            "project(\":app\").projectDir = file(\"modules/app-impl\")",
            "").getBytes(StandardCharsets.UTF_8));

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("modules/app-impl"), projectRoot.resolve("nested/inner")),
                subprojects, "subprojects"),
            () -> assertEquals(List.of(), warnings, "warnings"));
    }

    @Test
    @DisplayName("getSubprojects: missing settings.gradle returns empty list, no exception")
    void getSubprojectsReturnsEmptyWhenSettingsMissing() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("no-settings");
        Files.createDirectories(projectRoot);
        List<LoadWarning> warnings = new ArrayList<>();
        assertTrue(new GradleImporter().getSubprojects(projectRoot, warnings).isEmpty(),
            "Expected empty list when settings.gradle is absent");
        assertEquals(List.of(), warnings, "no settings file declares anything to report");
    }

    @Test
    @DisplayName("getSubprojects: an include whose directory doesn't exist is skipped and reported as MODULE_NOT_LOADED")
    void getSubprojectsDropsNonexistentDirectories() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("nonexistent-include");
        Files.createDirectories(projectRoot);
        Files.createDirectories(projectRoot.resolve("real-module"));
        Files.writeString(projectRoot.resolve("settings.gradle"), """
            include 'real-module'
            include 'phantom-module'
            """);

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("real-module")), subprojects, "subprojects"),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                    "settings.gradle declares 'phantom-module', but its directory phantom-module does not exist",
                    "Create the directory or remove the declaration from settings.gradle.",
                    "phantom-module")),
                warnings, "warnings"));
    }

    @Test
    @DisplayName("getSubprojects: a missing include and an include that is not a valid path are each reported in declaration order, and later includes still load")
    void getSubprojectsReportsMissingAndInvalidIncludes() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("unloadable-includes");
        Files.createDirectories(projectRoot.resolve("app"));
        // The third include's quoted name holds a NUL character, which is not a valid path
        // on any OS. 'app' is declared last, so it loads only if neither rejection aborts
        // the read.
        Files.writeString(projectRoot.resolve("settings.gradle"),
            "include 'phantom'\n"
                + "include 'bad\0name'\n"
                + "include 'app'\n");

        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> subprojects = new GradleImporter().getSubprojects(projectRoot, warnings);

        assertAll(
            () -> assertEquals(List.of(projectRoot.resolve("app")), subprojects, "subprojects"),
            () -> assertEquals(List.of(
                    new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                        "settings.gradle declares 'phantom', but its directory phantom does not exist",
                        "Create the directory or remove the declaration from settings.gradle.",
                        "phantom"),
                    new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                        "settings.gradle declares 'bad\\0name', which is not a valid path: control character U+0000",
                        "Correct the declaration in settings.gradle.",
                        "bad\\0name")),
                warnings, "warnings"));
    }

    /**
     * Point the importer at a Gradle binary, copy the fixture, load it, and run the
     * caller-supplied assertions on the resulting classpath snapshot.
     *
     * <p>Always asserts, whatever the caller checks: Gradle reported the model, the load
     * surfaced no {@code BUILD_TOOL_*} warning, and the load wrote nothing into the
     * project tree.
     */
    private void runWithGradle(String fixtureName, java.util.function.Consumer<ClasspathSnapshot> assertions)
            throws Exception {
        try (GradleBinary gradle = GradleBinary.require("classpath aggregation")) {
            Path projectRoot = helper.copyFixture(fixtureName);
            Set<String> treeBefore = ProjectTree.relativePaths(projectRoot);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            Set<String> buildToolCodes = Set.of(LoadWarning.BUILD_TOOL_UNAVAILABLE,
                LoadWarning.BUILD_TOOL_FAILED, LoadWarning.BUILD_TOOL_TIMEOUT);
            List<LoadWarning> buildToolWarnings = service.getWarnings().stream()
                .filter(w -> buildToolCodes.contains(w.code()))
                .toList();
            Set<String> treeAfter = ProjectTree.relativePaths(projectRoot);
            assertAll(
                () -> assertEquals(Provenance.BUILD_TOOL, service.getBuildModel().provenance(), "provenance"),
                () -> assertEquals(List.of(), buildToolWarnings, "build-tool warnings on a successful Gradle build"),
                () -> assertEquals(treeBefore, treeAfter, "project tree after load"));

            assertions.accept(snapshot);
        }
    }

    /** The file names of the snapshot's libraries, sorted. */
    private static List<String> libraryNames(ClasspathSnapshot snapshot) {
        return snapshot.libraries().stream()
            .map(library -> library.getFileName().toString())
            .sorted()
            .toList();
    }
}
