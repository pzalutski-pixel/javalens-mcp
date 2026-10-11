package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.CompilationProblems;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.ProjectTree;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.BuildSystem;
import org.javalens.core.project.GradleImporter;
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins that a Gradle project's model is the one Gradle reports, on the
 * {@code fidelity-gradle} fixture:
 * <ul>
 *   <li>{@code settings.gradle} opens with the stock {@code gradle init} comment block,
 *       whose {@code include 'shared'}, {@code include 'api'} and
 *       {@code include 'services:webservice'} are comments. A {@code shared/} directory
 *       with a Java source exists and must never load.</li>
 *   <li>{@code :app} declares {@code srcDirs = ['src/java']}, level 21, a project
 *       dependency on {@code :lib}, a dependency on {@code org.fidelity:core:1.0} that
 *       Gradle substitutes with composite-lib's {@code :core}, {@code slf4j-api 2.0.9}
 *       and {@code files('libs/local.jar')}; it also holds an undeclared
 *       {@code src/main/java}.</li>
 *   <li>{@code :lib} lives in {@code libs/lib-impl}, declares level 17, and registers a
 *       {@code kotlin} source directory set holding {@code src/main/kotlin}, as the Kotlin
 *       plugin does.</li>
 *   <li>The included build {@code composite-lib} has {@code :core} at level 21.</li>
 * </ul>
 *
 * <p>Those tests copy the fixture, write {@code settings.gradle} with explicit line
 * endings and create {@code app/libs/local.jar}, then run Gradle. One more test runs Gradle
 * on a project whose declared source directory holds a conventional layout.
 */
class FidelityGradleTest {

    private static final String FIXTURE = "fidelity-gradle";
    private static final String CRLF = "\r\n";
    private static final String LF = "\n";

    /** The prefix of the scratch directory a Gradle model run creates in the system temporary directory. */
    private static final String SCRATCH_PREFIX = "javalens-gradle-model-";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("CRLF settings: the load holds exactly the projects Gradle reports, their declared source roots, levels and inputs, and the same settings with LF give the identical model")
    void crlfAndLfSettingsGiveGradlesModel() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle model")) {
            Path root = prepare(CRLF);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(BuildSystem.GRADLE, model.buildSystem(), "build system"),
                () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                () -> assertEquals(expectedModules(root, List.of()), model.modules(), "modules"),
                () -> assertEquals(sourceFolders(root, List.of()), snapshot.sourceFolders(), "linked source folders in order"),
                () -> assertEquals("21", model.analysisLevel(), "analysis level"),
                () -> assertEquals("21", snapshot.compilerSource(), "compiler source"),
                () -> assertEquals(List.of(), model.processorJars(), "processor jars"),
                () -> assertEquals(expectedInputs(root), model.inputs(), "inputs"),
                () -> assertEquals(List.of(undeclaredAppSources(), mixedLevels()), model.warnings(), "model warnings"),
                () -> assertEquals(List.of(undeclaredAppSources(), mixedLevels()), service.getWarnings(), "load warnings"));

            writeSettings(root, LF);
            BuildModel lfModel = new GradleImporter().resolve(root);

            assertEquals(model, lfModel, "LF settings give the identical model");
        }
    }

    @Test
    @DisplayName("The classpath holds the external jar and the file dependency only; :lib and the substituted composite-lib :core are linked as sources, never as jars, and App.java compiles against them")
    void classpathHoldsExternalArtifactsOnly() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle classpath")) {
            Path root = prepare(LF);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            List<String> libraryNames = snapshot.libraries().stream()
                .map(library -> library.getFileName().toString())
                .sorted()
                .toList();
            List<Path> localJars = snapshot.libraries().stream()
                .filter(library -> library.getFileName().toString().equals("local.jar"))
                .toList();
            IType app = service.findType("org.fidelity.app.App");
            assertAll(
                () -> assertEquals(List.of("local.jar", "slf4j-api-2.0.9.jar"), libraryNames, "library file names"),
                () -> assertEquals(List.of(root.resolve("app/libs/local.jar")), localJars, "file dependency"),
                () -> assertEquals(sourceFolders(root, List.of()), snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(root.resolve("composite-lib/core/src/main/java/org/fidelity/core/Core.java"),
                    location(service.findType("org.fidelity.core.Core")), "Core"),
                () -> assertEquals(root.resolve("app/src/java/org/fidelity/app/App.java"), location(app), "App"),
                () -> assertEquals(List.of(), CompilationProblems.ids(app.getCompilationUnit()),
                    "problems in App.java"));
        }
    }

    @Test
    @DisplayName("A generated source at app's annotation-processor output directory, as Gradle reports it, is linked with no warning")
    void generatedSourceAtReportedDirectoryIsLinkedWithoutWarning() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle generated sources")) {
            Path root = prepare(LF);
            Path generatedRoot = root.resolve("app/build/generated/sources/annotationProcessor/java/main");
            Path generatedFile = writeSource(generatedRoot, "Generated");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(expectedModules(root, List.of(generatedRoot)), service.getBuildModel().modules(), "modules"),
                () -> assertEquals(sourceFolders(root, List.of(generatedRoot)), snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(undeclaredAppSources(), mixedLevels()), service.getWarnings(), "warnings"),
                () -> assertEquals(generatedFile, location(service.findType("org.fidelity.app.Generated")), "Generated resolves from its file"));
        }
    }

    @Test
    @DisplayName("A generated source in the <task>/main/java layout Gradle does not report stays linked, named by INCLUDED_OUTSIDE_BUILD")
    void generatedSourceAtUnreportedLayoutIsKeptWithWarning() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle generated sources")) {
            Path root = prepare(LF);
            Path generatedRoot = root.resolve("app/build/generated/sources/annotationProcessor/main/java");
            Path generatedFile = writeSource(generatedRoot, "OldLayout");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            LoadWarning keptGenerated = new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                "app/build/generated/sources/annotationProcessor/main/java is linked because it holds a conventional "
                    + "source layout, but the build does not compile it as part of module ':app'",
                "Declare it as a source directory in the build, or remove it.",
                ":app");
            assertAll(
                () -> assertEquals(expectedModules(root, List.of(generatedRoot)), service.getBuildModel().modules(), "modules"),
                () -> assertEquals(sourceFolders(root, List.of(generatedRoot)), snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(undeclaredAppSources(), keptGenerated, mixedLevels()), service.getWarnings(), "warnings"),
                () -> assertEquals(generatedFile, location(service.findType("org.fidelity.app.OldLayout")), "OldLayout resolves from its file"));
        }
    }

    @Test
    @DisplayName("Loading writes nothing into the project tree and leaves no scratch directory behind")
    void loadWritesNothingIntoTheProjectTree() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle tree")) {
            Path root = prepare(CRLF);
            Set<String> treeBefore = ProjectTree.relativePaths(root);
            Set<String> scratchBefore = scratchDirectories();

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            assertAll(
                () -> assertEquals(Provenance.BUILD_TOOL, service.getBuildModel().provenance(),
                    "Gradle reported the model, so the tree was walked after a Gradle run"),
                () -> assertEquals(treeBefore, ProjectTree.relativePaths(root), "project tree"),
                () -> assertEquals(scratchBefore, scratchDirectories(), "scratch directories"));
        }
    }

    @Test
    @DisplayName("findType resolves app's declared and undeclared sources, :lib's Java and Kotlin source directories and composite-lib's :core from their files; the commented-out shared project does not resolve")
    void typesResolveFromTheProjectsGradleReports() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("fidelity-gradle types")) {
            Path root = prepare(CRLF);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            assertAll(
                () -> assertEquals(root.resolve("app/src/java/org/fidelity/app/App.java"),
                    location(service.findType("org.fidelity.app.App")), "App"),
                () -> assertEquals(root.resolve("app/src/main/java/org/fidelity/app/Undeclared.java"),
                    location(service.findType("org.fidelity.app.Undeclared")), "Undeclared"),
                () -> assertEquals(root.resolve("libs/lib-impl/src/main/java/org/fidelity/lib/Lib.java"),
                    location(service.findType("org.fidelity.lib.Lib")), "Lib"),
                () -> assertEquals(root.resolve("libs/lib-impl/src/main/kotlin/org/fidelity/lib/FromKotlinDir.java"),
                    location(service.findType("org.fidelity.lib.FromKotlinDir")), "FromKotlinDir"),
                () -> assertEquals(root.resolve("composite-lib/core/src/main/java/org/fidelity/core/Core.java"),
                    location(service.findType("org.fidelity.core.Core")), "Core"),
                () -> assertNull(service.findType("org.fidelity.shared.Shared"), "Shared"));
        }
    }

    @Test
    @DisplayName("A conventional layout inside the source directory the build declares is no root of its own and gets no warning; each file is counted once")
    void conventionalLayoutInsideADeclaredRootIsNotLinkedAgain() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("declared root holding a conventional layout")) {
            Path root = Files.createDirectories(helper.getTempDirectory().resolve("src-root"));
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'src-root'\n");
            Files.writeString(root.resolve("build.gradle"), "plugins {\n    id 'java'\n}\n\n"
                + "sourceSets.main.java.srcDirs = ['src']\n\n"
                + "java {\n    sourceCompatibility = JavaVersion.VERSION_17\n}\n");
            Path conventional = Files.createDirectories(root.resolve("src/main/java/p"));
            Files.writeString(conventional.resolve("P.java"), "package p;\n\npublic class P {\n}\n");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(List.of(new BuildModule(":", root, List.of(root.resolve("src")), List.of(),
                        List.of(root.resolve("build/classes/java/main"), root.resolve("build/classes/java/test")),
                        "17", true)),
                    service.getBuildModel().modules(), "modules"),
                () -> assertEquals(List.of(root.resolve("src")), snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(1, service.getSourceFileCount(), "source file count"),
                () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
        }
    }

    @Test
    @DisplayName("Gradle unavailable, CRLF or LF settings: the static read holds the root, app and lib at its literal projectDir; the commented-out includes, whose shared directory exists, are not modules")
    void staticReadIgnoresCommentedIncludes() throws Exception {
        String previous = System.getProperty("javalens.gradle.binary");
        try {
            Path root = prepare(CRLF);
            System.setProperty("javalens.gradle.binary", root.resolve("no-such-gradle").toString());

            BuildModel crlf = new GradleImporter().resolve(root);
            writeSettings(root, LF);
            BuildModel lf = new GradleImporter().resolve(root);

            assertAll(
                () -> assertEquals(Provenance.STATIC_READ, crlf.provenance(), "provenance"),
                () -> assertEquals(List.of(root, root.resolve("app"), root.resolve("libs/lib-impl")),
                    crlf.modules().stream().map(BuildModule::dir).toList(), "module directories"),
                // The start failure's text is the operating system's, so only the code is pinned.
                () -> assertEquals(List.of(LoadWarning.BUILD_TOOL_UNAVAILABLE),
                    crlf.warnings().stream().map(LoadWarning::code).toList(), "warning codes"),
                () -> assertEquals(crlf, lf, "LF settings give the identical model"));
        } finally {
            if (previous == null) {
                System.clearProperty("javalens.gradle.binary");
            } else {
                System.setProperty("javalens.gradle.binary", previous);
            }
        }
    }

    // ========== Expected values ==========

    /**
     * The modules Gradle reports for the fixture: the root build's projects in Gradle's
     * {@code allprojects} order, then the included build's. {@code appGenerated} holds the
     * generated roots a test staged under {@code app}.
     */
    private static List<BuildModule> expectedModules(Path root, List<Path> appGenerated) {
        return List.of(
            new BuildModule(":", root, List.of(), List.of(), List.of(), null, true),
            new BuildModule(":app", root.resolve("app"),
                List.of(root.resolve("app/src/java"), root.resolve("app/src/main/java")),
                appGenerated,
                List.of(root.resolve("app/build/classes/java/main"), root.resolve("app/build/classes/java/test")),
                "21", true),
            new BuildModule(":lib", root.resolve("libs/lib-impl"),
                List.of(root.resolve("libs/lib-impl/src/main/java"), root.resolve("libs/lib-impl/src/main/kotlin")),
                List.of(),
                List.of(root.resolve("libs/lib-impl/build/classes/java/main"),
                    root.resolve("libs/lib-impl/build/classes/java/test")),
                "17", true),
            new BuildModule(":composite-lib", root.resolve("composite-lib"),
                List.of(), List.of(), List.of(), null, true),
            new BuildModule(":composite-lib:core", root.resolve("composite-lib/core"),
                List.of(root.resolve("composite-lib/core/src/main/java")),
                List.of(),
                List.of(root.resolve("composite-lib/core/build/classes/java/main"),
                    root.resolve("composite-lib/core/build/classes/java/test")),
                "21", true));
    }

    /** The linked source folders in link order: per module, its source roots, then its generated roots. */
    private static List<Path> sourceFolders(Path root, List<Path> appGenerated) {
        return Stream.of(
                Stream.of(root.resolve("app/src/java"), root.resolve("app/src/main/java")),
                appGenerated.stream(),
                Stream.of(root.resolve("libs/lib-impl/src/main/java"),
                    root.resolve("libs/lib-impl/src/main/kotlin"),
                    root.resolve("composite-lib/core/src/main/java")))
            .flatMap(paths -> paths)
            .toList();
    }

    /**
     * The build's inputs: settings files, existing project build files in module order
     * (composite-lib's root project has none), {@code gradle.properties} candidates per
     * module directory, version-catalog candidates per build root, the root build's wrapper
     * properties, and each build root's {@code buildSrc}.
     */
    private static BuildInputs expectedInputs(Path root) {
        return new BuildInputs(
            List.of(
                root.resolve("settings.gradle"),
                root.resolve("composite-lib/settings.gradle"),
                root.resolve("build.gradle"),
                root.resolve("app/build.gradle"),
                root.resolve("libs/lib-impl/build.gradle"),
                root.resolve("composite-lib/core/build.gradle"),
                root.resolve("gradle.properties"),
                root.resolve("app/gradle.properties"),
                root.resolve("libs/lib-impl/gradle.properties"),
                root.resolve("composite-lib/gradle.properties"),
                root.resolve("composite-lib/core/gradle.properties"),
                root.resolve("gradle/libs.versions.toml"),
                root.resolve("composite-lib/gradle/libs.versions.toml"),
                root.resolve("gradle/wrapper/gradle-wrapper.properties")),
            List.of(root.resolve("buildSrc"), root.resolve("composite-lib/buildSrc")));
    }

    private static LoadWarning undeclaredAppSources() {
        return new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
            "app/src/main/java is linked because it holds a conventional source layout, "
                + "but the build does not compile it as part of module ':app'",
            "Declare it as a source directory in the build, or remove it.",
            ":app");
    }

    private static LoadWarning mixedLevels() {
        return new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
            "Modules declare different Java levels (:app 21, :lib 17, :composite-lib:core 21); "
                + "the project is analyzed at 21, the highest",
            "Code valid only at a lower level is analyzed at the highest level, so a module's own level is not enforced.");
    }

    // ========== Helpers ==========

    /**
     * Copy the fixture, rewrite {@code settings.gradle} with {@code lineEnding} after every
     * line, and create {@code app/libs/local.jar}, the file dependency {@code :app} declares.
     */
    private Path prepare(String lineEnding) throws IOException {
        Path root = helper.copyFixture(FIXTURE);
        writeSettings(root, lineEnding);
        writeLocalJar(root.resolve("app/libs/local.jar"));
        return root;
    }

    /** Rewrite {@code settings.gradle}'s lines with explicit {@code lineEnding} bytes. */
    private static void writeSettings(Path root, String lineEnding) throws IOException {
        Path settings = root.resolve("settings.gradle");
        List<String> lines = Files.readAllLines(settings, StandardCharsets.UTF_8);
        Files.write(settings, (String.join(lineEnding, lines) + lineEnding).getBytes(StandardCharsets.UTF_8));
    }

    /** Write a jar holding only a manifest, which is a valid jar. */
    private static void writeLocalJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
    }

    /** Write class {@code org.fidelity.app.<name>} under {@code sourceRoot}; returns the file. */
    private static Path writeSource(Path sourceRoot, String name) throws IOException {
        Path packageDir = Files.createDirectories(sourceRoot.resolve("org/fidelity/app"));
        Path file = packageDir.resolve(name + ".java");
        Files.writeString(file, "package org.fidelity.app;\n\npublic final class " + name + " {\n}\n");
        return file;
    }

    /** The file {@code type} was read from, or null when the type did not resolve. */
    private static Path location(IType type) {
        if (type == null) {
            return null;
        }
        return Path.of(type.getResource().getLocation().toOSString());
    }

    /** The Gradle model scratch directories present in the system temporary directory. */
    private static Set<String> scratchDirectories() throws IOException {
        try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.map(entry -> entry.getFileName().toString())
                .filter(name -> name.startsWith(SCRATCH_PREFIX))
                .collect(Collectors.toSet());
        }
    }
}
