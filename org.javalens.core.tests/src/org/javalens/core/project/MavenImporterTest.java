package org.javalens.core.project;

import org.javalens.core.fixtures.MavenBinary;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MavenImporter}'s static read: with a Maven binary that cannot start, the model
 * is read from the {@code pom.xml} files as XML. Comments are not elements, so nothing in a
 * comment is read; levels follow maven-compiler-plugin's precedence; parents on disk lend
 * their values.
 */
class MavenImporterTest {

    private static final String POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
        + "<groupId>g</groupId><version>1</version>";

    // ========== Modules ==========

    @Test
    @DisplayName("The reactor is the root and every declared module, recursively, each identified by groupId:artifactId; commented-out modules are not declared")
    void walksDeclaredModulesOnly(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId><packaging>pom</packaging>"
            + "<!-- <modules><module>ghost</module></modules> -->"
            + "<modules><module>a</module><!-- <module>legacy</module> --><module>b</module></modules></project>");
        write(root, "a/pom.xml", POM + "<artifactId>a</artifactId>"
            + "<modules><module>nested</module></modules></project>");
        write(root, "a/nested/pom.xml", POM + "<artifactId>nested</artifactId></project>");
        write(root, "b/pom.xml", POM + "<artifactId>b</artifactId></project>");
        write(root, "legacy/pom.xml", POM + "<artifactId>legacy</artifactId></project>");

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of("g:root", "g:a", "g:nested", "g:b"), ids(model), "module ids"),
            () -> assertEquals(List.of(root, root.resolve("a"), root.resolve("a/nested"), root.resolve("b")),
                model.modules().stream().map(BuildModule::dir).toList(), "module directories"),
            () -> assertEquals(List.of(LoadWarning.BUILD_TOOL_UNAVAILABLE, LoadWarning.BUILD_MODEL_STATIC),
                codes(model), "warning codes"));
    }

    @Test
    @DisplayName("A module that declares its own parent directory again is visited once")
    void cycleIsVisitedOnce(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId><modules><module>a</module></modules></project>");
        write(root, "a/pom.xml", POM + "<artifactId>a</artifactId><modules><module>../a</module></modules></project>");

        assertEquals(List.of("g:root", "g:a"), ids(staticRead(root)), "module ids");
    }

    @Test
    @DisplayName("A module entry naming a pom file loads that pom's module; a missing module is MODULE_NOT_LOADED, root declarations before nested ones")
    void moduleEntriesResolveDirectoriesAndFiles(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId>"
            + "<modules><module>core</module><module>ghost</module><module>alt/build.xml</module></modules></project>");
        write(root, "core/pom.xml", POM + "<artifactId>core</artifactId>"
            + "<modules><module>inner-gone</module></modules></project>");
        write(root, "alt/build.xml", POM + "<artifactId>alt</artifactId></project>");

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of("g:root", "g:core", "g:alt"), ids(model), "module ids"),
            () -> assertEquals(List.of(
                    missingModule("pom.xml", "ghost", "ghost"),
                    missingModule("core/pom.xml", "inner-gone", "core/inner-gone")),
                model.warnings().subList(0, 2), "module warnings"));
    }

    @Test
    @DisplayName("A module declared only in a profile is linked with its conventional layout and INCLUDED_OUTSIDE_BUILD")
    void profileModuleIsIncludedOutsideBuild(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId>"
            + "<profiles><profile><id>extra</id><modules><module>x</module></modules></profile></profiles></project>");
        write(root, "x/pom.xml", POM + "<artifactId>x</artifactId></project>");
        Files.createDirectories(root.resolve("x/src/main/java"));

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of(
                    new BuildModule("g:root", root, List.of(), List.of(),
                        List.of(root.resolve("target/classes"), root.resolve("target/test-classes")), null, false),
                    new BuildModule("g:x", root.resolve("x"), List.of(root.resolve("x/src/main/java")), List.of(),
                        List.of(), null, false)),
                model.modules(), "modules"),
            () -> assertEquals(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                    "x is declared as a module only in profile 'extra' of pom.xml, which the load did not activate, so "
                        + "its dependencies are not resolved; it is linked with its conventional layout",
                    "Activate the profile through JAVALENS_MAVEN_ARGS (for example -Pextra) and reload the project to "
                        + "load the module fully.",
                    "g:x"),
                model.warnings().get(0), "profile warning"));
    }

    @Test
    @DisplayName("A root pom that is not well-formed XML fails the read, so the load falls back to the root's conventional layout")
    void malformedRootPomFailsTheRead(@TempDir Path root) throws IOException {
        write(root, "pom.xml", "<project><modules></project>");

        try (MavenBinary maven = MavenBinary.use(root.resolve("no-such-mvn").toString())) {
            UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> new MavenImporter().resolve(root));
            assertTrue(failure.getMessage().startsWith("The root pom.xml could not be read: pom.xml is not well-formed XML: "),
                failure::getMessage);
        }
    }

    // ========== Levels ==========

    @Test
    @DisplayName("maven.compiler.release wins over maven.compiler.source; a commented-out property is not read")
    void releasePropertyWinsOverSource(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId><properties>"
            + "<maven.compiler.source>17</maven.compiler.source>"
            + "<maven.compiler.release>21</maven.compiler.release>"
            + "<!-- <maven.compiler.release>8</maven.compiler.release> -->"
            + "</properties></project>");

        assertEquals("21", staticRead(root).analysisLevel());
    }

    @Test
    @DisplayName("An explicit plugin <source> wins over maven.compiler.source, and ${...} references resolve against the properties")
    void pluginSourceWinsOverSourceProperty(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId>"
            + "<properties><java.version>11</java.version><maven.compiler.source>17</maven.compiler.source></properties>"
            + "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
            + "<configuration><source>${java.version}</source></configuration></plugin></plugins></build></project>");

        assertEquals("11", staticRead(root).analysisLevel());
    }

    @Test
    @DisplayName("Compiler configuration in pluginManagement applies, and a child inherits its parent's level")
    void pluginManagementAndParentLevel(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId><modules><module>child</module></modules>"
            + "<build><pluginManagement><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
            + "<configuration><release>17</release></configuration></plugin></plugins></pluginManagement></build>"
            + "</project>");
        write(root, "child/pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
            + "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>"
            + "<artifactId>child</artifactId></project>");

        BuildModel model = staticRead(root);

        assertAll(
            () -> assertEquals(List.of("g:root", "g:child"), ids(model), "module ids"),
            () -> assertEquals(java.util.Arrays.asList("17", "17"),
                model.modules().stream().map(BuildModule::declaredLevel).toList(), "declared levels"));
    }

    @Test
    @DisplayName("Without a declared level the model has none")
    void noLevelDeclared(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId></project>");

        assertEquals(null, staticRead(root).analysisLevel());
    }

    // ========== Annotation processors ==========

    @Test
    @DisplayName("A processor path takes a missing version from dependency management; one with no version anywhere is APT_PROCESSOR_JARS_MISSING")
    void processorVersions(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId>"
            + "<dependencyManagement><dependencies><dependency><groupId>p</groupId><artifactId>managed</artifactId>"
            + "<version>2.0</version></dependency></dependencies></dependencyManagement>"
            + "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration>"
            + "<annotationProcessorPaths>"
            + "<path><groupId>p</groupId><artifactId>managed</artifactId></path>"
            + "<path><groupId>p</groupId><artifactId>unversioned</artifactId></path>"
            + "</annotationProcessorPaths></configuration></plugin></plugins></build></project>");

        BuildModel model = staticRead(root);

        Path repository = Path.of(System.getProperty("user.home"), ".m2", "repository");
        assertAll(
            () -> assertEquals(List.of(repository.resolve("p/managed/2.0/managed-2.0.jar")), model.processorJars(),
                "processor jars"),
            () -> assertEquals(new LoadWarning(LoadWarning.APT_PROCESSOR_JARS_MISSING,
                    "Annotation processor paths without a version, in the path or in dependency management, are not "
                        + "registered: p:unversioned (g:root)",
                    "Give each annotation processor path a version, then reload the project."),
                model.warnings().get(0), "unversioned processor"));
    }

    // ========== Maven unavailable ==========

    @Test
    @DisplayName("A Maven binary that cannot start gives BUILD_TOOL_UNAVAILABLE, then BUILD_MODEL_STATIC, and no classpath")
    void unavailableMaven(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM + "<artifactId>root</artifactId></project>");
        String mvn = root.resolve("no-such-mvn").toString();

        BuildModel model;
        try (MavenBinary maven = MavenBinary.use(mvn)) {
            model = new MavenImporter().resolve(root);
        }

        LoadWarning unavailable = model.warnings().get(0);
        BuildModel read = model;
        assertAll(
            () -> assertEquals(List.of(LoadWarning.BUILD_TOOL_UNAVAILABLE, LoadWarning.BUILD_MODEL_STATIC),
                codes(read), "warning codes"),
            () -> assertEquals(List.of(), read.classpath(), "classpath"),
            () -> assertEquals("Install Maven and ensure '" + mvn + "' is on PATH for the process that launches "
                    + "JavaLens.", unavailable.remediation(), "remediation"),
            // The message ends with the OS's process-start error text, which differs per OS and
            // locale, so only the part MavenImporter composes is pinned.
            () -> assertTrue(unavailable.message().startsWith("Could not start '" + mvn + "': "),
                unavailable::message));
    }

    // ========== Helpers ==========

    private static BuildModel staticRead(Path root) {
        try (MavenBinary maven = MavenBinary.use(root.resolve("no-such-mvn").toString())) {
            return new MavenImporter().resolve(root);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> ids(BuildModel model) {
        return model.modules().stream().map(BuildModule::id).toList();
    }

    private static List<String> codes(BuildModel model) {
        return model.warnings().stream().map(LoadWarning::code).toList();
    }

    private static LoadWarning missingModule(String declaringPom, String module, String relativeDir) {
        return new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
            declaringPom + " declares '" + module + "', but its directory " + relativeDir + " does not exist",
            "Create the directory or remove the declaration from " + declaringPom + ".",
            module);
    }
}
