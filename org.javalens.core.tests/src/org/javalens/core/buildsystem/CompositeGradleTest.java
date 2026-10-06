package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the load of a Gradle composite build: the root project of {@code composite-gradle}
 * declares {@code includeBuild 'shared-lib'} and depends on {@code com.example:shared-lib},
 * which Gradle substitutes with the included build's project.
 *
 * <p>Gradle reports the included build's project as a module of the model, so its sources
 * are linked and its types resolve from source, and the substituted dependency puts no
 * jar on the classpath. Each load runs Gradle on a copy of the fixture.
 */
class CompositeGradleTest {

    private static final String FIXTURE = "composite-gradle";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("The model holds the root build's project and the included build's project, as Gradle reports them, with no warning")
    void compositeGradle_modelHoldsBothBuilds() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("composite build model")) {
            Path root = helper.copyFixture(FIXTURE);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();

            assertAll(
                () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                () -> assertEquals(List.of(
                        new BuildModule(":", root, List.of(root.resolve("src/main/java")), List.of(),
                            List.of(root.resolve("build/classes/java/main"), root.resolve("build/classes/java/test")),
                            "21", true),
                        new BuildModule(":shared-lib", root.resolve("shared-lib"),
                            List.of(root.resolve("shared-lib/src/main/java")), List.of(),
                            List.of(root.resolve("shared-lib/build/classes/java/main"),
                                root.resolve("shared-lib/build/classes/java/test")),
                            "21", true)),
                    model.modules(), "modules"),
                () -> assertEquals("21", model.analysisLevel(), "analysis level"),
                () -> assertEquals(List.of(), service.getWarnings(), "warnings"));
        }
    }

    @Test
    @DisplayName("The root build's App and the included build's SharedUtil resolve from their linked sources")
    void compositeGradle_typesOfBothBuildsResolveFromSource() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("composite build types")) {
            Path root = helper.copyFixture(FIXTURE);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            assertAll(
                () -> assertEquals(root.resolve("src/main/java/com/example/composite/App.java"),
                    location(service.findType("com.example.composite.App")), "App"),
                () -> assertEquals(root.resolve("shared-lib/src/main/java/com/example/sharedlib/SharedUtil.java"),
                    location(service.findType("com.example.sharedlib.SharedUtil")), "SharedUtil"));
        }
    }

    @Test
    @DisplayName("Both builds' sources are linked and enumerated, and the substituted dependency adds no library")
    void compositeGradle_linksBothBuildsAndNoLibrary() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("composite build classpath")) {
            Path root = helper.copyFixture(FIXTURE);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(List.of(root.resolve("src/main/java"), root.resolve("shared-lib/src/main/java")),
                    snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(
                        root.resolve("src/main/java/com/example/composite/App.java"),
                        root.resolve("shared-lib/src/main/java/com/example/sharedlib/SharedUtil.java")),
                    service.getAllJavaFiles(), "Java files in source-folder order"),
                () -> assertEquals(List.of(), snapshot.libraries(), "libraries"));
        }
    }

    @Test
    @DisplayName("Both build directories are present on disk (fixture sanity)")
    void compositeGradle_bothBuildsPresent() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        assertTrue(Files.exists(root.resolve("build.gradle")),
            "Root build.gradle must be present");
        assertTrue(Files.exists(root.resolve("shared-lib/build.gradle")),
            "Included build's build.gradle must be present");
        assertTrue(Files.exists(root.resolve("shared-lib/settings.gradle")),
            "Included build must have its own settings.gradle (composite-build requirement)");
        assertTrue(Files.exists(root.resolve("shared-lib/src/main/java/com/example/sharedlib/SharedUtil.java")),
            "Shared library source must be present in the included build");
    }

    /** The file {@code type} was read from, or null when the type did not resolve. */
    private static Path location(IType type) {
        if (type == null) {
            return null;
        }
        return Path.of(type.getResource().getLocation().toOSString());
    }
}
