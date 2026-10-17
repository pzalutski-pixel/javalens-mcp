package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.MavenBinary;
import org.javalens.core.fixtures.ProjectTree;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.BuildSystem;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the Maven project model against the {@code fidelity-maven} fixture, whose project root
 * is {@code reactor/}: a reactor of {@code core} (a custom source directory, plus a
 * conventional {@code src/main/java} the build does not compile), {@code app} (an explicit
 * compiler {@code <release>21</release>} over the inherited {@code maven.compiler.release}
 * of 17, a build-helper source directory, two annotation processor paths of which one is not
 * in any repository) and {@code ../outside}; a commented-out {@code legacy} module whose
 * directory exists; a module declared only in the {@code extras} profile; and a Gradle
 * project a {@code settings.gradle} beside the root pom includes.
 *
 * <p>Each test copies the fixture under a groupId of its own, so a module installed by one
 * test is never in the local repository for another, and removes what it installed.
 */
class FidelityMavenTest {

    private static final String FIXTURE = "fidelity-maven";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven reports the reactor: the commented-out legacy module is no module, app's dependency on the uninstalled core leaves app's dependencies unresolved and named, and nothing is written into the tree")
    void mavenReportsTheReactor() throws Exception {
        try (MavenBinary maven = MavenBinary.require("fidelity-maven model")) {
            String group = uniqueGroup();
            Path root = prepare(group);
            Set<String> before = ProjectTree.relativePaths(root.getParent());

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            List<LoadWarning> modelWarnings = model.warnings();
            assertAll(
                () -> assertEquals(BuildSystem.MAVEN, model.buildSystem(), "build system"),
                () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                () -> assertEquals(expectedModules(root, group, true), model.modules(), "modules"),
                () -> assertEquals("21", model.analysisLevel(), "analysis level"),
                () -> assertEquals(List.of("slf4j-api-2.0.9.jar", "commons-lang3-3.13.0.jar"),
                    fileNames(model.classpath()), "classpath in module order"),
                () -> assertEquals(expectedProcessors(), model.processorJars(), "processor jars"),
                () -> assertEquals(expectedInputs(root), model.inputs(), "inputs"),
                () -> assertEquals(List.of(extrasOutsideBuild(group), gradleOutsideBuild(), undeclaredCore(group),
                        mixedLevels(group), LoadWarning.BUILD_TOOL_FAILED),
                    codesAndModels(modelWarnings), "model warnings"),
                () -> assertEquals(group + ":app", modelWarnings.get(4).module(), "unresolved module"),
                () -> assertEquals("If the module depends on another module of this build, install the build once "
                        + "('mvn install -DskipTests') so Maven can resolve it, then reload the project.",
                    modelWarnings.get(4).remediation(), "unresolved remediation"),
                () -> assertNull(service.findType("org.fidelity.legacy.Legacy"), "Legacy"),
                () -> assertEquals(root.resolve("core/src/java/org/fidelity/core/Core.java"),
                    location(service.findType("org.fidelity.core.Core")), "Core"),
                () -> assertEquals(root.resolve("app/src/extra/org/fidelity/extra/Extra.java"),
                    location(service.findType("org.fidelity.extra.Extra")), "Extra"),
                () -> assertEquals(root.getParent().resolve("outside/src/main/java/org/fidelity/outside/Outside.java"),
                    location(service.findType("org.fidelity.outside.Outside")), "Outside"),
                () -> assertEquals("21", snapshot.compilerSource(), "compiler source"),
                () -> assertEquals(new LoadWarning(LoadWarning.APT_PROCESSOR_JARS_MISSING,
                        "1 of the 2 declared annotation-processor jar(s) were not found on disk, so those processors "
                            + "do not run. Missing: [" + expectedProcessors().get(1) + "]",
                        "Run your build's dependency-resolution step (e.g. 'mvn dependency:resolve', "
                            + "'gradle resolveDependencies') so the declared processor jars are present "
                            + "in the local cache, then reload the project."),
                    service.getWarnings().get(service.getWarnings().size() - 1), "missing processor jar"),
                () -> assertEquals(before, ProjectTree.relativePaths(root.getParent()), "files in the tree"));
        }
    }

    @Test
    @DisplayName("With core installed, app's dependencies resolve and core's installed jar is left off the classpath, since core's sources are linked")
    void installedReactorModuleIsNotALibrary() throws Exception {
        try (MavenBinary maven = MavenBinary.require("fidelity-maven installed core")) {
            String group = uniqueGroup();
            Path root = prepare(group);
            try {
                maven.run(root, "-B", "-q", "install", "-pl", "core", "-am", "-DskipTests");

                JdtServiceImpl service = new JdtServiceImpl();
                service.loadProject(root);
                BuildModel model = service.getBuildModel();

                assertAll(
                    () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                    () -> assertEquals(List.of("slf4j-api-2.0.9.jar", "commons-lang3-3.13.0.jar"),
                        fileNames(model.classpath()), "classpath in module order"),
                    () -> assertEquals(List.of(extrasOutsideBuild(group), gradleOutsideBuild(), undeclaredCore(group),
                            mixedLevels(group)),
                        codesAndModels(model.warnings()), "model warnings"));
            } finally {
                deleteInstalled(group);
            }
        }
    }

    @Test
    @DisplayName("JAVALENS_MAVEN_ARGS=-Pextras activates the profile, so extras is a reactor module Maven reports, not a module included outside the build")
    void mavenArgsActivateAProfile() throws Exception {
        try (MavenBinary maven = MavenBinary.require("fidelity-maven with -Pextras")) {
            String group = uniqueGroup();
            Path root = prepare(group);
            System.setProperty("JAVALENS_MAVEN_ARGS", "-Pextras");
            BuildModel model;
            try {
                model = new org.javalens.core.project.ProjectImporter().resolveModel(root);
            } finally {
                System.clearProperty("JAVALENS_MAVEN_ARGS");
            }

            List<BuildModule> expected = new java.util.ArrayList<>(expectedModules(root, group, true).subList(0, 4));
            expected.add(new BuildModule(group + ":extras", root.resolve("extras"),
                List.of(root.resolve("extras/src/main/java")), List.of(),
                List.of(root.resolve("extras/target/classes"), root.resolve("extras/target/test-classes")), "17", true));
            expected.add(expectedModules(root, group, true).get(5));
            BuildModel read = model;
            assertAll(
                () -> assertEquals(expected, read.modules(), "modules"),
                () -> assertEquals(List.of(gradleOutsideBuild(), undeclaredCore(group),
                        new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
                            "Modules declare different Java levels (" + group + ":reactor 17, " + group + ":core 17, "
                                + group + ":app 21, " + group + ":outside 17, " + group + ":extras 17); the project is "
                                + "analyzed at 21, the highest",
                            "Code valid only at a lower level is analyzed at the highest level, so a module's own "
                                + "level is not enforced."),
                        LoadWarning.BUILD_TOOL_FAILED),
                    codesAndModels(read.warnings()), "model warnings"));
        }
    }

    @Test
    @DisplayName("Maven unavailable: the pom.xml files are read as XML, so the commented-out legacy module and release 8 are not read; levels come from the inherited properties and app's explicit release")
    void staticReadIgnoresComments() throws Exception {
        String group = uniqueGroup();
        Path root = prepare(group);
        try (MavenBinary maven = MavenBinary.use(root.resolve("no-such-mvn").toString())) {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();

            assertAll(
                () -> assertEquals(Provenance.STATIC_READ, model.provenance(), "provenance"),
                () -> assertEquals(expectedModules(root, group, false), model.modules(), "modules"),
                () -> assertEquals("21", model.analysisLevel(), "analysis level"),
                () -> assertEquals(List.of(), model.classpath(), "classpath"),
                () -> assertEquals(expectedProcessors(), model.processorJars(), "processor jars"),
                // The start failure's text is the operating system's, so only the codes are pinned.
                () -> assertEquals(List.of(extrasOutsideBuild(group), gradleOutsideBuild(), undeclaredCore(group),
                        mixedLevels(group), LoadWarning.BUILD_TOOL_UNAVAILABLE, staticRead()),
                    codesAndModels(model.warnings()), "model warnings"),
                () -> assertNull(service.findType("org.fidelity.legacy.Legacy"), "Legacy"));
        }
    }

    // ========== Expected values ==========

    private static List<BuildModule> expectedModules(Path root, String group, boolean evaluated) {
        Path outside = root.getParent().resolve("outside");
        return List.of(
            new BuildModule(group + ":reactor", root, List.of(), List.of(),
                List.of(root.resolve("target/classes"), root.resolve("target/test-classes")), "17", evaluated),
            new BuildModule(group + ":core", root.resolve("core"),
                List.of(root.resolve("core/src/java"), root.resolve("core/src/main/java")), List.of(),
                List.of(root.resolve("core/target/classes"), root.resolve("core/target/test-classes")), "17", evaluated),
            new BuildModule(group + ":app", root.resolve("app"),
                List.of(root.resolve("app/src/main/java"), root.resolve("app/src/extra")), List.of(),
                List.of(root.resolve("app/target/classes"), root.resolve("app/target/test-classes")), "21", evaluated),
            new BuildModule(group + ":outside", outside,
                List.of(outside.resolve("src/main/java")), List.of(),
                List.of(outside.resolve("target/classes"), outside.resolve("target/test-classes")), "17", evaluated),
            new BuildModule(group + ":extras", root.resolve("extras"),
                List.of(root.resolve("extras/src/main/java")), List.of(), List.of(), null, false),
            new BuildModule("gradle-extra", root.resolve("gradle-extra"),
                List.of(root.resolve("gradle-extra/src/main/java")), List.of(), List.of(), null, false));
    }

    private static List<Path> expectedProcessors() {
        Path repository = Path.of(System.getProperty("user.home"), ".m2", "repository");
        return List.of(
            repository.resolve("org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar"),
            repository.resolve("com/missing/missing-processor/9.9/missing-processor-9.9.jar"));
    }

    private static BuildInputs expectedInputs(Path root) {
        return new BuildInputs(List.of(
            root.resolve("pom.xml"),
            root.resolve("core/pom.xml"),
            root.resolve("app/pom.xml"),
            root.getParent().resolve("outside/pom.xml"),
            root.resolve("extras/pom.xml"),
            root.resolve("settings.gradle"),
            root.resolve(".mvn/maven.config"),
            root.resolve(".mvn/jvm.config"),
            root.resolve(".mvn/extensions.xml")), List.of());
    }

    private static LoadWarning extrasOutsideBuild(String group) {
        return new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
            "extras is declared as a module only in profile 'extras' of pom.xml, which the load did not activate, "
                + "so its dependencies are not resolved; it is linked with its conventional layout",
            "Activate the profile through JAVALENS_MAVEN_ARGS (for example -Pextras) and reload the project to load "
                + "the module fully.",
            group + ":extras");
    }

    private static LoadWarning gradleOutsideBuild() {
        return new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
            "gradle-extra is a Gradle project the settings file declares, but the project is loaded as a Maven build, "
                + "so its dependencies are not resolved; it is linked with its conventional layout",
            "Load it as part of its Gradle build, or remove it from the settings file.",
            "gradle-extra");
    }

    private static LoadWarning undeclaredCore(String group) {
        return new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
            "core/src/main/java is linked because it holds a conventional source layout, but the build does not "
                + "compile it as part of module '" + group + ":core'",
            "Declare it as a source directory in the build, or remove it.",
            group + ":core");
    }

    private static LoadWarning mixedLevels(String group) {
        return new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
            "Modules declare different Java levels (" + group + ":reactor 17, " + group + ":core 17, " + group
                + ":app 21, " + group + ":outside 17); the project is analyzed at 21, the highest",
            "Code valid only at a lower level is analyzed at the highest level, so a module's own level is not "
                + "enforced.");
    }

    private static LoadWarning staticRead() {
        return new LoadWarning(LoadWarning.BUILD_MODEL_STATIC,
            "Maven did not report the project model, so the modules, source directories, Java levels and annotation "
                + "processors were read from the pom.xml files; a static read resolves no dependencies (the classpath "
                + "holds none), does not evaluate profile activation or parents outside the project tree, and looks "
                + "for processor jars in the default local repository "
                + Path.of(System.getProperty("user.home"), ".m2", "repository"),
            "Fix the build-tool problem reported above, then reload the project.");
    }

    // ========== Helpers ==========

    /** A groupId no other test run uses. */
    private static String uniqueGroup() {
        return "org.fidelity.t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Copy the fixture with every {@code org.fidelity} groupId replaced by {@code group}; returns the project root. */
    private Path prepare(String group) throws IOException {
        Path copy = helper.copyFixture(FIXTURE);
        try (Stream<Path> files = Files.walk(copy)) {
            for (Path pom : files.filter(file -> file.getFileName().toString().equals("pom.xml")).toList()) {
                String content = Files.readString(pom, StandardCharsets.UTF_8);
                Files.writeString(pom, content.replace("<groupId>org.fidelity</groupId>",
                    "<groupId>" + group + "</groupId>"), StandardCharsets.UTF_8);
            }
        }
        return copy.resolve("reactor");
    }

    /** Remove {@code group}'s artifacts from the local repository. */
    private static void deleteInstalled(String group) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".m2", "repository").resolve(group.replace('.', '/'));
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Each warning itself, except the build-tool ones, whose text holds the tool's output, reduced to their code. */
    private static List<Object> codesAndModels(List<LoadWarning> warnings) {
        return warnings.stream()
            .map(warning -> warning.code().startsWith("BUILD_TOOL_") ? (Object) warning.code() : warning)
            .toList();
    }

    private static List<String> fileNames(List<String> classpath) {
        return classpath.stream().map(entry -> Path.of(entry).getFileName().toString()).toList();
    }

    private static Path location(IType type) {
        return type == null ? null : Path.of(type.getResource().getLocation().toOSString());
    }
}
