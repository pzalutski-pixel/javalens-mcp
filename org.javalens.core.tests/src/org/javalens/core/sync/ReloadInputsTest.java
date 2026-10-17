package org.javalens.core.sync;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.exceptions.ReloadRequiredException;
import org.javalens.core.fixtures.BazelBinary;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.MavenBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins reload detection on the files each build reports reading: a change to any of them,
 * including one created after the load, makes the next query fail with
 * {@link ReloadRequiredException} naming exactly the changed files; a file no build reads
 * changes nothing.
 */
class ReloadInputsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Gradle: a created gradle.properties and version catalog, an edited applied script and a new buildSrc file require a reload naming each; an edited README does not")
    void gradleInputs() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("Gradle reload inputs")) {
            Path root = helper.copyFixture("simple-gradle");
            write(root.resolve("extra.gradle"), "ext.extraValue = 1\n");
            append(root.resolve("build.gradle"), "\napply from: 'extra.gradle'\n");
            write(root.resolve("README.md"), "readme\n");
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            append(root.resolve("README.md"), "more\n");
            assertEquals(List.of(), service.ensureFresh(), "an edited README needs no reload");

            write(root.resolve("gradle.properties"), "org.gradle.caching=false\n");
            write(root.resolve("gradle/libs.versions.toml"), "[versions]\n");
            append(root.resolve("extra.gradle"), "ext.otherValue = 2\n");
            write(root.resolve("buildSrc/src/main/groovy/Helper.groovy"), "class Helper {}\n");

            assertReloadNames(service, List.of(
                root.resolve("buildSrc/src/main/groovy/Helper.groovy"), root.resolve("extra.gradle"),
                root.resolve("gradle.properties"), root.resolve("gradle/libs.versions.toml")));
        }
    }

    @Test
    @DisplayName("Maven: a created .mvn/maven.config and an edited pom of a module outside the root require a reload naming each")
    void mavenInputs() throws Exception {
        try (MavenBinary maven = MavenBinary.use(helper.getTempDirectory().resolve("no-such-mvn").toString())) {
            // The static read reports the same poms and .mvn files as inputs, without Maven.
            Path root = helper.copyFixture("fidelity-maven").resolve("reactor");
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            write(root.resolve(".mvn/maven.config"), "-Pextras\n");
            append(root.getParent().resolve("outside/pom.xml"), "<!-- edited -->\n");

            assertReloadNames(service, List.of(
                root.resolve(".mvn/maven.config"), root.getParent().resolve("outside/pom.xml")));
        }
    }

    @Test
    @DisplayName("Bazel: an edited .bazelrc and an edited .bzl the build loads require a reload naming each")
    void bazelInputs() throws Exception {
        try (BazelBinary bazel = BazelBinary.require("Bazel reload inputs")) {
            Path root = helper.copyFixture("fidelity-bazel");
            bazel.isolate(root, helper.getTempDirectory().resolve("_bazel"));
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);

            append(root.resolve(".bazelrc"), "build --javacopt=-Xlint\n");
            append(root.resolve("macros.bzl"), "\n# edited\n");

            assertReloadNames(service, List.of(root.resolve(".bazelrc"), root.resolve("macros.bzl")));
        }
    }

    /** The next query fails with a reload naming exactly {@code files}, in path order. */
    private static void assertReloadNames(JdtServiceImpl service, List<Path> files) {
        ReloadRequiredException reload = assertThrows(ReloadRequiredException.class, service::ensureFresh);
        assertEquals(files.stream().map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(Path::toString)).toList(),
            reload.getChangedBuildFiles(), "changed build files");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void append(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
