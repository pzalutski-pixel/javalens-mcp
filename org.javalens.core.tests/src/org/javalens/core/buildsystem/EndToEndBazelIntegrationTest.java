package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bazel counterpart of {@link EndToEndIntegrationTest}: a realistic multi-target Bazel
 * build that exercises every Bazel-relevant fix in a single load. Mirrors the Maven and
 * Gradle E2E tests' structure.
 *
 * <p>Layout: three {@code java_library} targets ({@code //model}, {@code //service},
 * {@code //web}) with cross-target dependencies. {@code service} depends on {@code model};
 * {@code web} depends on both. After {@code bazel build //...} the per-target jars land
 * under {@code bazel-bin}/{@code bazel-out}, exercising the Bug E dedup path on a real
 * symlink layout.
 *
 * <p>Bazel APT and compiler-compliance reading are not yet supported in production; this
 * test focuses on classpath aggregation, source discovery across targets, and cross-target
 * navigation — the parts that the production code does claim to support.
 */
class EndToEndBazelIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("realistic Bazel multi-target: every applicable fix exercised in a single load")
    void allFixesExercisedInASingleLoad() throws Exception {
        String bazel = resolveBazelBinary();
        TestEnvironment.requireOrSkip(bazel, "Bazel binary (end-to-end Bazel load)");

        Path projectRoot = helper.copyFixture("realistic-multi-target-bazel");

        // Per-test Bazel output user root so the test gets fully isolated install / cache /
        // repos. Reusing the user-default location (~/_bazel_<user>/) carries stale repo
        // cache entries from prior interrupted runs that point to deleted temp paths and
        // poison subsequent invocations. Anchoring under the test's own tempDirectory
        // means everything Bazel writes goes away with the test cleanup.
        // Bazel requires a path with no spaces; user.home contains spaces on some Windows
        // setups, so we explicitly use the test helper's tempDirectory.
        Path bazelOutputRoot = helper.getTempDirectory().resolve("_bazel");
        Files.createDirectories(bazelOutputRoot);
        String outputUserRoot = "--output_user_root=" + bazelOutputRoot.toAbsolutePath();

        ClasspathSnapshot snapshot;
        JdtServiceImpl service;
        try {
            runBazel(bazel, projectRoot, outputUserRoot, "build", "--lockfile_mode=off", "//...");

            service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            snapshot = ClasspathSnapshot.capture(service.getJavaProject());
        } finally {
            // Always shut the Bazel server down so the install-base lock releases before
            // the temp dir is cleaned up. Otherwise the test runner can't delete the dir.
            try { runBazel(bazel, projectRoot, outputUserRoot, "shutdown"); }
            catch (Exception ignored) {}
        }

        // === Each target's compiled jar shows up exactly once on the classpath =========
        // libmodel.jar, libservice.jar, libweb.jar — each appears in bazel-out under
        // <config>/bin/<package>/, and bazel-bin symlinks into bazel-out. The Bug E fix
        // canonicalizes scan roots so we don't double-count.
        long modelJars = snapshot.libraryCountEndingWith("libmodel.jar");
        long serviceJars = snapshot.libraryCountEndingWith("libservice.jar");
        long webJars = snapshot.libraryCountEndingWith("libweb.jar");
        assertTrue(modelJars >= 1,
            "Expected libmodel.jar on classpath. Libraries: " + snapshot.libraries());
        assertTrue(modelJars == 1,
            "Expected libmodel.jar exactly once after dedup. Got " + modelJars);
        assertTrue(serviceJars == 1,
            "Expected libservice.jar exactly once after dedup. Got " + serviceJars);
        assertTrue(webJars == 1,
            "Expected libweb.jar exactly once after dedup. Got " + webJars);

        // === Each target's source folder is on the classpath ==========================
        // For Bazel layouts where BUILD.bazel sits at <target>/ alongside src/main/java,
        // every target's src/main/java should be a source folder.
        assertTrue(snapshot.sourceFolders().stream().anyMatch(p ->
            p.toString().replace('\\', '/').endsWith("model/src/main/java")),
            "Expected model/src/main/java as a source folder. Got: " + snapshot.sourceFolders());
        assertTrue(snapshot.sourceFolders().stream().anyMatch(p ->
            p.toString().replace('\\', '/').endsWith("service/src/main/java")),
            "Expected service/src/main/java as a source folder. Got: " + snapshot.sourceFolders());
        assertTrue(snapshot.sourceFolders().stream().anyMatch(p ->
            p.toString().replace('\\', '/').endsWith("web/src/main/java")),
            "Expected web/src/main/java as a source folder. Got: " + snapshot.sourceFolders());

        // === Bug G (Bazel): javacopts source/target/release applied ====================
        // The fixture declares -source 17 / -target 17 / --release=17 across its targets;
        // detectBazelCompilerLevel walks every BUILD.bazel and picks the highest level.
        assertEquals("17", snapshot.compilerSource(),
            "Expected COMPILER_SOURCE=17 from javacopts in BUILD.bazel files");
        assertEquals("17", snapshot.compilerCompliance(),
            "Expected COMPILER_COMPLIANCE=17 from javacopts in BUILD.bazel files");

        // === Bug F: search works synchronously after loadProject =======================
        IType user = service.findType("com.example.model.User");
        assertNotNull(user, "Expected to resolve com.example.model.User immediately after load");
        IType userService = service.findType("com.example.service.UserService");
        assertNotNull(userService, "Expected to resolve com.example.service.UserService");
        IType controller = service.findType("com.example.web.UserController");
        assertNotNull(controller, "Expected to resolve com.example.web.UserController");

        // === Cross-target find_references resolves =====================================
        List<SearchMatch> userRefs = service.getSearchService()
            .findReferences(user, IJavaSearchConstants.REFERENCES, 100);
        boolean serviceUsesUser = userRefs.stream().anyMatch(m -> {
            String path = m.getResource() != null ? m.getResource().getFullPath().toString() : "";
            return path.contains("UserService");
        });
        boolean webUsesUser = userRefs.stream().anyMatch(m -> {
            String path = m.getResource() != null ? m.getResource().getFullPath().toString() : "";
            return path.contains("UserController");
        });
        assertTrue(serviceUsesUser,
            "Expected User references in :service target (UserService). Got " + userRefs.size() + " matches");
        assertTrue(webUsesUser,
            "Expected User references in :web target (UserController). Got " + userRefs.size() + " matches");
    }

    private static void runBazel(String bazelBinary, Path projectRoot, String... args)
            throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(bazelBinary);
        for (String a : args) command.add(a);
        Process p = new ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start();
        StringBuilder out = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() < 8192) out.append(line).append('\n');
            }
        }
        // First-time builds download Bazel itself + the JDK toolchain, hence a generous timeout.
        boolean done = p.waitFor(15, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("bazel " + String.join(" ", args) + " timed out\n" + out);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("bazel " + String.join(" ", args)
                + " failed with exit code " + p.exitValue() + "\n" + out);
        }
    }

    private static String resolveBazelBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] names = isWindows ? new String[]{"bazel.exe", "bazelisk.exe"} : new String[]{"bazel", "bazelisk"};

        // 1. PATH lookup
        for (String name : names) {
            try {
                Process p = new ProcessBuilder(name, "version").redirectErrorStream(true).start();
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    while (r.readLine() != null) { /* drain */ }
                }
                p.waitFor();
                if (p.exitValue() == 0) return name;
            } catch (IOException | InterruptedException ignored) {
                if (Thread.interrupted()) Thread.currentThread().interrupt();
            }
        }

        // 2. ~/javalens-tools/bazel*.exe (where the test environment may have placed bazelisk)
        Path tools = Path.of(System.getProperty("user.home"), "javalens-tools");
        if (Files.isDirectory(tools)) {
            try (Stream<Path> entries = Files.list(tools)) {
                for (Path entry : entries.toList()) {
                    String fname = entry.getFileName().toString().toLowerCase();
                    if ((fname.equals("bazel.exe") || fname.equals("bazelisk.exe")
                            || fname.equals("bazel") || fname.equals("bazelisk"))
                            && Files.isRegularFile(entry)) {
                        return entry.toString();
                    }
                }
            } catch (IOException ignored) {}
        }

        return null;
    }
}
