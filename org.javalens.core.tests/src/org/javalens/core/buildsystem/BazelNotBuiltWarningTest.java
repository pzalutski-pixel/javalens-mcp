package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.BazelBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code BAZEL_NOT_BUILT} on the static read, which a Bazel binary that cannot start forces.
 * The warning has two triggers and one silent-pass case:
 *
 * <ul>
 *   <li>No output tree: neither {@code bazel-bin} nor {@code bazel-out} exists.</li>
 *   <li>Empty output trees: typically after {@code bazel clean}, the trees exist but hold no
 *       jar.</li>
 *   <li>Built project: a jar is reachable under {@code bazel-bin}; the warning does not
 *       fire.</li>
 * </ul>
 */
class BazelNotBuiltWarningTest {

    private static final String REMEDIATION = "Run 'bazel build //...' (or the relevant target set) in the project "
        + "root before loading. JavaLens reads dependency jars from Bazel's build output, not from the source tree.";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("No output tree: exactly one BAZEL_NOT_BUILT saying neither bazel-bin nor bazel-out exists")
    void warnsWhenNoBazelOutputExists() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("bazel-no-outputs"));
        Files.writeString(root.resolve("MODULE.bazel"), "module(name = \"empty\")\n");

        assertEquals(List.of(new LoadWarning(LoadWarning.BAZEL_NOT_BUILT,
                "Neither bazel-bin nor bazel-out exists in " + root + "; classpath will be empty", REMEDIATION)),
            notBuiltWarnings(root));
    }

    @Test
    @DisplayName("Empty output trees: exactly one BAZEL_NOT_BUILT saying they contain no jars")
    void warnsWhenBazelOutputsEmpty() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("bazel-empty-outputs"));
        Files.writeString(root.resolve("MODULE.bazel"), "module(name = \"empty\")\n");
        Files.createDirectories(root.resolve("bazel-bin"));
        Files.createDirectories(root.resolve("bazel-out"));

        assertEquals(List.of(new LoadWarning(LoadWarning.BAZEL_NOT_BUILT,
                "bazel-bin/bazel-out exist in " + root + " but contain no jars (likely bazel clean was just run); "
                    + "classpath will be empty", REMEDIATION)),
            notBuiltWarnings(root));
    }

    @Test
    @DisplayName("Built project: a jar under bazel-bin means no BAZEL_NOT_BUILT")
    void silentWhenJarsPresent() throws Exception {
        Path root = Files.createDirectories(helper.getTempDirectory().resolve("bazel-with-jars"));
        Files.writeString(root.resolve("MODULE.bazel"), "module(name = \"built\")\n");
        Path binDir = Files.createDirectories(root.resolve("bazel-bin"));
        Files.createFile(binDir.resolve("libfake-classpath.jar"));

        assertEquals(List.of(), notBuiltWarnings(root));
    }

    /** The BAZEL_NOT_BUILT warnings of a static-read load of {@code root}. */
    private static List<LoadWarning> notBuiltWarnings(Path root) throws Exception {
        try (BazelBinary bazel = BazelBinary.use(root.resolve("no-such-bazel").toString())) {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            return service.getWarnings().stream()
                .filter(warning -> LoadWarning.BAZEL_NOT_BUILT.equals(warning.code()))
                .toList();
        }
    }
}
