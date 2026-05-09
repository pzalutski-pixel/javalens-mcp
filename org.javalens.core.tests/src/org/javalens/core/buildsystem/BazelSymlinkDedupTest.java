package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestEnvironment;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bug E — Bazel scan double-counts jars.
 *
 * <p>In a typical Bazel checkout {@code bazel-bin} is a symlink whose target lives under
 * {@code bazel-out/<config>/bin/}. The original {@code getBazelDependencies} walked both
 * roots independently, so every jar appeared on the classpath twice — once via the
 * symlinked alias and once via the canonical path under {@code bazel-out}.
 *
 * <p>The fix canonicalizes each scan root with {@link Path#toRealPath()} and skips any
 * root whose canonical form is already covered by another. Within a single scan, paths are
 * keyed by their canonical form so any remaining overlap dedupes.
 *
 * <p>The fixture is built dynamically because it requires symlink creation, which on
 * Windows needs Developer Mode or admin. The test skips when symlink creation isn't
 * permitted in the runner's environment.
 */
class BazelSymlinkDedupTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("bazel-bin symlink into bazel-out does not duplicate jars on the classpath")
    void symlinkedBazelBinDoesNotDuplicateJars() throws Exception {
        Path projectRoot = helper.getTempDirectory().resolve("bazel-symlink-trap");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"trap\")\n");

        // Real jar under bazel-out
        Path canonicalBin = projectRoot.resolve("bazel-out/k8-fastbuild/bin");
        Files.createDirectories(canonicalBin);
        Path libJar = canonicalBin.resolve("libapp.jar");
        Files.write(libJar, new byte[]{'P','K',3,4});  // minimal jar-ish bytes

        // bazel-bin → bazel-out/k8-fastbuild/bin
        // On Linux/macOS this is a symbolic link. On Windows symlink creation requires
        // admin or Developer Mode, so we fall back to a directory junction (mklink /J) which
        // does not. Path.toRealPath observes both as canonicalizing to the target.
        Path bazelBinLink = projectRoot.resolve("bazel-bin");
        TestEnvironment.requireOrSkip(createDirectoryLink(bazelBinLink, canonicalBin),
            "directory symlink / junction creation (Windows: needs Developer Mode or admin)");

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        long libAppEntries = snapshot.libraryCountEndingWith("libapp.jar");
        assertEquals(1L, libAppEntries,
            "Expected exactly one classpath entry for libapp.jar; the bazel-bin symlink " +
            "into bazel-out should be deduped. Got " + libAppEntries +
            ". Libraries: " + snapshot.libraries());
    }

    @Test
    @DisplayName("real Bazel layout: bazel-out symlinks outside projectRoot, jars still found")
    void realBazelLayoutWithBazelOutSymlinkOutsideProjectRoot() throws Exception {
        // This test reproduces the actual layout `bazel build` produces on Linux/macOS:
        // both bazel-bin and bazel-out are symbolic links, with bazel-out pointing at a
        // directory tree that lives OUTSIDE projectRoot (Bazel uses ~/_bazel_<user>/...
        // or the configured --output_user_root). The previous canonicalize-and-dedupe
        // implementation kept the original symlink path; on Linux Files.walk(symlink-to-
        // directory) without FOLLOW_LINKS treats the symlink as a non-directory single
        // entry and emits zero results. This test would have failed on Linux/macOS
        // (CI run 25585823533) before the canonical-path-return fix.

        // Real output tree, deliberately outside projectRoot.
        Path realOutputRoot = helper.getTempDirectory().resolve("real-bazel-output");
        Path realBin = realOutputRoot.resolve("k8-fastbuild/bin");
        Files.createDirectories(realBin);
        Files.write(realBin.resolve("libapp.jar"), new byte[]{'P','K',3,4});

        // The Bazel workspace itself.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-real-layout");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"trap\")\n");

        Path bazelOutLink = projectRoot.resolve("bazel-out");
        TestEnvironment.requireOrSkip(createDirectoryLink(bazelOutLink, realOutputRoot),
            "directory symlink / junction creation (bazel-out)");

        Path bazelBinLink = projectRoot.resolve("bazel-bin");
        TestEnvironment.requireOrSkip(createDirectoryLink(bazelBinLink, realBin),
            "directory symlink / junction creation (bazel-bin)");

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        long libAppEntries = snapshot.libraryCountEndingWith("libapp.jar");
        assertEquals(1L, libAppEntries,
            "Expected libapp.jar exactly once when bazel-bin/bazel-out symlink to an " +
            "external real directory. Without canonicalizeAndDedupe returning canonical " +
            "paths, the scan would walk a symlink-to-directory and emit zero entries on " +
            "Linux/macOS. Got " + libAppEntries + ". Libraries: " + snapshot.libraries());
    }

    /**
     * Create a directory link at {@code link} pointing to {@code target}. Tries a symbolic
     * link first; on Windows where symlinks need admin/Developer Mode, falls back to a
     * directory junction via {@code mklink /J}.
     *
     * @return true if a link was created, false if neither approach worked
     */
    private static boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException ignored) {
            // fall through
        }
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
