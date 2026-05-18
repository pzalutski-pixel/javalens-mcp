package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.BazelImporter;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BAZEL_NOT_BUILT} regression coverage. The warning has two distinct triggers and one
 * silent-pass case; each must be observable independently.
 *
 * <ul>
 *   <li>Trigger A — <b>no scan roots</b>: neither {@code bazel-bin} nor {@code bazel-out}
 *       exists. Detail message: "Neither bazel-bin nor bazel-out exists ...".</li>
 *   <li>Trigger B — <b>roots present but empty</b>: typically after {@code bazel clean}, the
 *       symlinks survive but point at empty trees. Detail message: "... contain no jars
 *       (likely bazel clean was just run)".</li>
 *   <li>Silent pass — <b>built project</b>: at least one jar is reachable under
 *       {@code bazel-bin}/{@code bazel-out}. {@code BAZEL_NOT_BUILT} must NOT fire.</li>
 * </ul>
 *
 * <p>Plus: {@link BazelImporter#getDependencies} dedupes the warning against the supplied
 * {@code warnings} list — repeat calls within a single project load must not produce
 * duplicate entries.
 */
class BazelNotBuiltWarningTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Trigger A: BAZEL_NOT_BUILT fires with 'neither ... exists' detail when no bazel-bin/bazel-out")
    void warnsWhenNoBazelOutputExists() throws Exception {
        // Build a minimal Bazel workspace with no build outputs at all. detectBuildSystem
        // sees MODULE.bazel and routes the load through the Bazel path; getDependencies
        // finds no scan roots and surfaces the warning via branch A.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-no-outputs");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"empty\")\n");

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        List<LoadWarning> warnings = service.getWarnings();
        List<LoadWarning> notBuilt = warnings.stream()
            .filter(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code()))
            .toList();
        assertEquals(1, notBuilt.size(),
            "Expected exactly one BAZEL_NOT_BUILT warning. Got: " + warnings);
        // Branch-discriminating assertion: only branch A produces "Neither bazel-bin nor
        // bazel-out exists". Without this we couldn't tell apart "the branch we intended to
        // exercise fired" from "the other branch incorrectly fired".
        assertTrue(notBuilt.get(0).message().contains("Neither bazel-bin nor bazel-out exists"),
            "Expected branch-A detail message; got: " + notBuilt.get(0).message());
        assertTrue(notBuilt.get(0).remediation().contains("bazel build"),
            "Remediation must mention 'bazel build'; got: " + notBuilt.get(0).remediation());
    }

    @Test
    @DisplayName("Trigger B: BAZEL_NOT_BUILT fires with 'contain no jars' detail when bazel-bin/bazel-out exist but empty")
    void warnsWhenBazelOutputIsEmpty() throws Exception {
        // Stand in for the post-`bazel clean` state: directories are present but empty.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-empty-outputs");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"empty\")\n");
        Files.createDirectories(projectRoot.resolve("bazel-bin"));
        Files.createDirectories(projectRoot.resolve("bazel-out"));

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        List<LoadWarning> warnings = service.getWarnings();
        List<LoadWarning> notBuilt = warnings.stream()
            .filter(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code()))
            .toList();
        assertEquals(1, notBuilt.size(),
            "Expected exactly one BAZEL_NOT_BUILT warning. Got: " + warnings);
        // Branch-discriminating assertion: only branch B mentions "contain no jars" and the
        // `bazel clean` hint.
        assertTrue(notBuilt.get(0).message().contains("contain no jars"),
            "Expected branch-B detail message; got: " + notBuilt.get(0).message());
        assertTrue(notBuilt.get(0).message().contains("bazel clean"),
            "Branch-B detail should mention the 'bazel clean' likely cause; got: "
                + notBuilt.get(0).message());
    }

    @Test
    @DisplayName("Silent pass: Bazel project with built jar in bazel-bin does NOT fire BAZEL_NOT_BUILT")
    void doesNotWarnWhenBazelOutputHasJars() throws Exception {
        // Bazel project that has been built at least once: bazel-bin holds a jar. The empty
        // file is enough — scanBazelDirForJars filters by name (".jar") and Files.isRegularFile;
        // it never opens the file.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-with-jars");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"built\")\n");
        Path binDir = projectRoot.resolve("bazel-bin");
        Files.createDirectories(binDir);
        Files.createFile(binDir.resolve("libfake-classpath.jar"));

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        List<LoadWarning> warnings = service.getWarnings();
        boolean fired = warnings.stream()
            .anyMatch(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code()));
        assertFalse(fired,
            "BAZEL_NOT_BUILT must NOT fire when at least one jar is reachable under "
                + "bazel-bin/bazel-out. Got warnings: " + warnings);
    }

    @Test
    @DisplayName("Dedup: BazelImporter.getDependencies emits BAZEL_NOT_BUILT at most once for a given warnings list")
    void doesNotDuplicateWarningOnRepeatCalls() throws Exception {
        // applyAnnotationProcessing → getResolvedClasspathJars → getDependencies calls
        // getDependencies a second time after addDependencyEntries already called it. The
        // source's `warnings.stream().noneMatch(...)` guard makes the second invocation a
        // no-op for warning emission. Unit-test directly via BazelImporter so we don't
        // depend on the call ordering inside JdtServiceImpl/ProjectImporter.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-dedup");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"empty\")\n");

        BazelImporter importer = new BazelImporter();
        List<LoadWarning> warnings = new ArrayList<>();
        importer.getDependencies(projectRoot, warnings);
        importer.getDependencies(projectRoot, warnings);
        importer.getDependencies(projectRoot, warnings);

        long count = warnings.stream()
            .filter(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code()))
            .count();
        assertEquals(1, count,
            "BAZEL_NOT_BUILT must be deduped across repeat getDependencies calls. Got: "
                + warnings);
    }
}
