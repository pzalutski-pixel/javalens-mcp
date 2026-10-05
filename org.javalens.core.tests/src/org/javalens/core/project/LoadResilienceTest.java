package org.javalens.core.project;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that nothing in build interpretation aborts a load. A declaration that cannot be
 * loaded costs only that module, reported as {@link LoadWarning#MODULE_NOT_LOADED}. An
 * importer whose whole resolution fails leaves one module at the project root, laid out by
 * convention and reported as {@link LoadWarning#MODULE_LAYOUT_ASSUMED}.
 */
class LoadResilienceTest {

    private static final String GRADLE_BINARY_PROPERTY = "javalens.gradle.binary";

    private static final String COMPLIANCE_UNKNOWN_REMEDIATION =
        "Declare maven.compiler.source/release in pom.xml, sourceCompatibility in build.gradle, "
            + "or javacopts -source/--release in BUILD.bazel so language-level features parse "
            + "against the intended grammar.";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private String savedGradleBinary;

    @BeforeEach
    void saveGradleBinary() {
        savedGradleBinary = System.getProperty(GRADLE_BINARY_PROPERTY);
    }

    @AfterEach
    void restoreGradleBinary() {
        if (savedGradleBinary == null) {
            System.clearProperty(GRADLE_BINARY_PROPERTY);
        } else {
            System.setProperty(GRADLE_BINARY_PROPERTY, savedGradleBinary);
        }
    }

    @Test
    @DisplayName("A Gradle include that is not a valid path is reported as MODULE_NOT_LOADED and the other subproject loads")
    void invalidGradleIncludeCostsOnlyThatModule() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("gradle-invalid-include"));
        // The first include's quoted name holds a NUL character, which is not a valid path
        // on any OS.
        Files.writeString(root.resolve("settings.gradle"),
            "include 'bad\0name'\n"
                + "include 'app'\n");
        Files.writeString(root.resolve("build.gradle"), "// stub\n");
        Path appJava = root.resolve("app/src/main/java");
        writeSource(appJava, "app", "App");
        // A Gradle binary that cannot start makes the build-tool step fail at once.
        System.setProperty(GRADLE_BINARY_PROPERTY,
            helper.getTempDirectory().resolve("no-such-gradle").toString());
        String runtimeLevel = String.valueOf(Runtime.version().feature());

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        List<LoadWarning> warnings = service.getWarnings();
        assertAll(
            () -> assertEquals(BuildSystem.GRADLE, service.getBuildSystem(), "build system"),
            () -> assertEquals(List.of(appJava), snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals(1, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("app"), service.getPackages(), "packages"),
            () -> assertEquals(List.of(LoadWarning.MODULE_NOT_LOADED, LoadWarning.GRADLE_SUBPROCESS_FAILED,
                    LoadWarning.COMPLIANCE_LEVEL_UNKNOWN),
                warnings.stream().map(LoadWarning::code).toList(), "warning codes in order"),
            () -> assertEquals(new LoadWarning(LoadWarning.MODULE_NOT_LOADED,
                    "settings.gradle declares 'bad\\0name', which is not a valid path: control character U+0000",
                    "Correct the declaration in settings.gradle.",
                    "bad\\0name"),
                warnings.get(0), "module warning"),
            // The message embeds the OS's process-start error text, which differs per OS and
            // locale, so only the fields GradleImporter composes itself are pinned.
            () -> assertEquals(null, warnings.get(1).module(), "subprocess warning module"),
            () -> assertEquals("Verify the Gradle Wrapper is intact, or install Gradle and ensure 'gradle' is on PATH.",
                warnings.get(1).remediation(), "subprocess warning remediation"),
            () -> assertEquals(new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
                    "Could not determine declared Java source level for GRADLE project; "
                        + "defaulting to runtime JVM major version " + runtimeLevel,
                    COMPLIANCE_UNKNOWN_REMEDIATION),
                warnings.get(2), "compliance warning"));
    }

    @Test
    @DisplayName("An importer whose resolve throws leaves the root's conventional layout and MODULE_LAYOUT_ASSUMED; the load completes")
    void throwingResolveFallsBackToRootConventionLoad() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("maven-resolve-throws"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path srcMain = root.resolve("src/main/java");
        writeSource(srcMain, "p", "P");
        String runtimeLevel = String.valueOf(Runtime.version().feature());

        JdtServiceImpl service = new JdtServiceImpl(
            new ProjectImporter(Map.of(BuildSystem.MAVEN, new ThrowingImporter())));
        service.loadProject(root);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertAll(
            () -> assertEquals(BuildSystem.MAVEN, service.getBuildSystem(), "build system"),
            () -> assertEquals(List.of(srcMain), snapshot.sourceFolders(), "linked source folders"),
            () -> assertEquals(1, service.getSourceFileCount(), "source file count"),
            () -> assertEquals(List.of("p"), service.getPackages(), "packages"),
            () -> assertEquals(List.of(
                    layoutAssumed("MAVEN"),
                    new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
                        "Could not determine declared Java source level for MAVEN project; "
                            + "defaulting to runtime JVM major version " + runtimeLevel,
                        COMPLIANCE_UNKNOWN_REMEDIATION)),
                service.getWarnings(), "warnings"));
    }

    @Test
    @DisplayName("resolveModel turns a failed resolve into one root module from the root's conventional and generated layouts, without reading declared modules")
    void throwingResolveYieldsRootConventionModel() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("maven-fallback-model"));
        // The pom declares a module with its own conventional layout. The fallback probes
        // the root only, so that layout must not appear.
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>sub</module></modules></project>\n");
        Files.createDirectories(root.resolve("sub/src/main/java"));
        Path srcMain = Files.createDirectories(root.resolve("src/main/java"));
        Path srcTest = Files.createDirectories(root.resolve("src/test/java"));
        Path generated = Files.createDirectories(root.resolve("target/generated-sources/annotations"));

        BuildModel model = new ProjectImporter(Map.of(BuildSystem.MAVEN, new ThrowingImporter()))
            .resolveModel(root);

        assertEquals(new BuildModel(BuildSystem.MAVEN, Provenance.CONVENTION,
                List.of(new BuildModule(".", root, List.of(srcMain, srcTest), List.of(generated),
                    List.of(), null, false)),
                List.of(), List.of(), null, new BuildInputs(List.of(), List.of()),
                List.of(layoutAssumed("MAVEN"))),
            model);
    }

    // ========== Helpers ==========

    /** Importer whose resolution fails outright. */
    private static final class ThrowingImporter implements BuildSystemImporter {
        @Override
        public BuildModel resolve(Path projectRoot) {
            throw new IllegalStateException("boom");
        }

        @Override
        public String detectCompilerLevel(Path projectPath) {
            return null;
        }

        @Override
        public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
            return List.of();
        }
    }

    private static LoadWarning layoutAssumed(String buildSystem) {
        return new LoadWarning(LoadWarning.MODULE_LAYOUT_ASSUMED,
            "Resolving the " + buildSystem + " build model failed (IllegalStateException: boom); "
                + "source roots were assumed from conventional layouts at the project root",
            "Check the project's build files, then reload the project.",
            ".");
    }

    private static void writeSource(Path sourceRoot, String packageName, String className) throws IOException {
        Path packageDir = Files.createDirectories(sourceRoot.resolve(packageName));
        Files.writeString(packageDir.resolve(className + ".java"),
            "package " + packageName + ";\n\npublic class " + className + " {\n}\n");
    }
}
