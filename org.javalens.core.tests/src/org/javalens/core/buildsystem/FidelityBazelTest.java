package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.BazelBinary;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the Bazel project model against the {@code fidelity-bazel} fixture: {@code //core}
 * compiles sources under a non-conventional {@code java/} root with {@code --release 11},
 * below a commented-out {@code --release 21}; {@code //app}, declared through a
 * {@code .bzl} macro, sets no level, so it compiles at the {@code .bazelrc}'s
 * {@code --java_language_version=17}; App.java has a commented-out package line before its
 * declaration.
 */
class FidelityBazelTest {

    private static final String FIXTURE = "fidelity-bazel";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Bazel reports the targets, the sources' real roots and each javac action's level, .bazelrc and comments included; the classpath has no target of the project, and nothing is written into the tree")
    void bazelReportsTheModel() throws Exception {
        try (BazelBinary bazel = BazelBinary.require("fidelity-bazel model")) {
            Path root = helper.copyFixture(FIXTURE);
            bazel.isolate(root, helper.getTempDirectory().resolve("_bazel"));
            Set<String> before = ProjectTree.relativePaths(root);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();

            assertAll(
                () -> assertEquals(BuildSystem.BAZEL, model.buildSystem(), "build system"),
                () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                () -> assertEquals(List.of("//app", "//core"),
                    model.modules().stream().map(BuildModule::id).toList(), "module ids"),
                () -> assertEquals(List.of(root.resolve("app"), root.resolve("core")),
                    model.modules().stream().map(BuildModule::dir).toList(), "module directories"),
                () -> assertEquals(List.of(List.of(root.resolve("app/src/main/java")), List.of(root.resolve("core/java"))),
                    model.modules().stream().map(BuildModule::sourceRoots).toList(), "source roots"),
                // The jars sit in Bazel's output tree, whose path and configuration name belong to
                // Bazel and the platform, so only their names are pinned.
                () -> assertEquals(List.of(List.of("libapp.jar"), List.of("libcore.jar")),
                    model.modules().stream().map(module -> module.outputDirs().stream()
                        .map(output -> output.getFileName().toString()).toList()).toList(), "outputs"),
                () -> assertEquals(java.util.Arrays.asList("17", "11"),
                    model.modules().stream().map(BuildModule::declaredLevel).toList(), "declared levels"),
                () -> assertEquals("17", model.analysisLevel(), "analysis level"),
                () -> assertEquals(List.of(), model.classpath(), "classpath"),
                () -> assertEquals(List.of(), model.processorJars(), "processor jars"),
                () -> assertEquals(new BuildInputs(List.of(
                        root.resolve("BUILD.bazel"), root.resolve("macros.bzl"),
                        root.resolve("app/BUILD.bazel"), root.resolve("core/BUILD.bazel"),
                        root.resolve("MODULE.bazel"), root.resolve("WORKSPACE"), root.resolve("WORKSPACE.bazel"),
                        root.resolve(".bazelrc"), root.resolve(".bazelversion")), List.of()),
                    model.inputs(), "inputs"),
                () -> assertEquals(List.of(new LoadWarning(LoadWarning.COMPLIANCE_LEVEL_MIXED,
                        "Modules declare different Java levels (//app 17, //core 11); the project is analyzed at 17, "
                            + "the highest",
                        "Code valid only at a lower level is analyzed at the highest level, so a module's own level is "
                            + "not enforced.")),
                    model.warnings(), "model warnings"),
                () -> assertEquals(root.resolve("app/src/main/java/org/fidelity/app/App.java"),
                    location(service.findType("org.fidelity.app.App")), "App"),
                () -> assertEquals(root.resolve("core/java/org/fidelity/core/Core.java"),
                    location(service.findType("org.fidelity.core.Core")), "Core"),
                () -> assertEquals(before, ProjectTree.relativePaths(root), "files in the tree"));
        }
    }

    @Test
    @DisplayName("JAVALENS_BAZEL_ARGS is passed to Bazel: --java_language_version=21 raises app's level, and core keeps its own --release 11")
    void bazelArgsReachTheBuild() throws Exception {
        try (BazelBinary bazel = BazelBinary.require("JAVALENS_BAZEL_ARGS")) {
            Path root = helper.copyFixture(FIXTURE);
            bazel.isolate(root, helper.getTempDirectory().resolve("_bazel"));
            System.setProperty("JAVALENS_BAZEL_ARGS", "--java_language_version=21");
            BuildModel model;
            try {
                model = new org.javalens.core.project.ProjectImporter().resolveModel(root);
            } finally {
                System.clearProperty("JAVALENS_BAZEL_ARGS");
            }

            BuildModel read = model;
            assertAll(
                () -> assertEquals(java.util.Arrays.asList("21", "11"),
                    read.modules().stream().map(BuildModule::declaredLevel).toList(), "declared levels"),
                () -> assertEquals("21", read.analysisLevel(), "analysis level"));
        }
    }

    @Test
    @DisplayName("Bazel unavailable: the BUILD files are read as code, so core's commented-out release 21 is not read; packages are laid out by convention")
    void staticReadIgnoresComments() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        try (BazelBinary bazel = BazelBinary.use(root.resolve("no-such-bazel").toString())) {
            BuildModel model = new org.javalens.core.project.ProjectImporter().resolveModel(root);

            assertAll(
                () -> assertEquals(Provenance.STATIC_READ, model.provenance(), "provenance"),
                () -> assertEquals(List.of(
                        new BuildModule("//", root, List.of(), List.of(), List.of(), null, false),
                        new BuildModule("//app", root.resolve("app"), List.of(root.resolve("app/src/main/java")),
                            List.of(), List.of(), null, false),
                        new BuildModule("//core", root.resolve("core"), List.of(), List.of(), List.of(), "11", false)),
                    model.modules(), "modules"),
                () -> assertEquals("11", model.analysisLevel(), "analysis level"),
                () -> assertEquals(List.of(), model.classpath(), "classpath"),
                // The start failure's text is the operating system's, so that warning is pinned by its code.
                () -> assertEquals(List.of(LoadWarning.BAZEL_NOT_BUILT, LoadWarning.BUILD_TOOL_UNAVAILABLE,
                        LoadWarning.BUILD_MODEL_STATIC),
                    model.warnings().stream().map(LoadWarning::code).toList(), "warning codes"),
                () -> assertEquals(new LoadWarning(LoadWarning.BUILD_MODEL_STATIC,
                        "Bazel did not report the project model, so the packages and Java levels were read from the BUILD "
                            + "files; a static read cannot see .bazelrc flags, the toolchain's default level, macros or "
                            + "generated sources, so each package is laid out by convention and the classpath is every jar "
                            + "in Bazel's output trees",
                        "Fix the build-tool problem reported above, then reload the project."),
                    model.warnings().get(2), "static read warning"));
        }
    }

    private static Path location(IType type) {
        return type == null ? null : Path.of(type.getResource().getLocation().toOSString());
    }
}
