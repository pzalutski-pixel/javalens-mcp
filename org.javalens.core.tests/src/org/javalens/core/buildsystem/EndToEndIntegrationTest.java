package org.javalens.core.buildsystem;

import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestEnvironment;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test against a representative real-world-shaped project.
 *
 * <p>Each individual bug fix has its own focused test against a minimal fixture, but those
 * exercise the bugs in isolation. This test loads a single realistic multi-module project
 * — three modules with cross-module references, Lombok APT, real external dependencies,
 * declared compiler compliance — and asserts that every bug fix is exercised together in
 * a single load. It is the smallest synthetic stand-in for the OSS smoke test the plan
 * called for; rather than depending on external repos, it ships a self-contained fixture
 * that catches integration bugs which interaction between fixes might introduce.
 *
 * <p>The fixture (model -> service -> web) is shaped like a typical Spring-style backend:
 * <ul>
 *   <li>{@code model} declares {@code @Data User} (Bug H — Lombok APT).</li>
 *   <li>{@code service} depends on {@code :model} + slf4j + commons-lang3, references
 *       Lombok-generated getters of User (Bug C — multi-module dependency capture).</li>
 *   <li>{@code web} depends on {@code :service} + spring-core, references types from
 *       both upstream modules (cross-module navigation regression).</li>
 *   <li>The pom declares Java 17 source level, deliberately distinct from the workspace
 *       default (Bug G).</li>
 * </ul>
 *
 * <p>The test additionally stages a generated source under one module's
 * {@code target/generated-sources/} (Bug B).
 */
class EndToEndIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("realistic multi-module project: every bug fix is exercised in a single load")
    void allFixesExercisedInASingleLoad() throws Exception {
        String mvn = resolveMavenBinary();
        TestEnvironment.requireOrSkip(mvn,
            "Maven binary (end-to-end Maven load)");

        String previousOverride = System.getProperty("javalens.maven.binary");
        System.setProperty("javalens.maven.binary", mvn);
        try {
            Path projectRoot = helper.copyFixture("realistic-multi-module");

            // Seed ~/.m2 with sibling artifacts AND lombok jar in one go.
            runMaven(mvn, projectRoot, "install", "-DskipTests", "-B", "-fae", "-q");

            // Bug B (Maven side): stage a generated source the way an annotation processor
            // would. Place it under :model so we can verify it's discovered alongside the
            // module's declared source folder.
            Path generatedDir = projectRoot.resolve("model/target/generated-sources/annotations/com/example/model");
            Files.createDirectories(generatedDir);
            Files.writeString(generatedDir.resolve("UserMetadata.java"), """
                package com.example.model;
                public final class UserMetadata {
                    public static final String VERSION = "1.0";
                    private UserMetadata() {}
                }
                """);

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            // === Bug X: clean reactor, no warnings (mvn ran successfully) =============
            List<LoadWarning> warnings = service.getWarnings();
            assertTrue(warnings.isEmpty(),
                "Expected zero warnings on a clean reactor build. Got: " + warnings);

            // === Bug C: every reactor module's unique external dep is on the classpath ==
            assertTrue(snapshot.hasLibraryMatching(".*slf4j-api.*\\.jar"),
                "Expected slf4j-api (declared in :service). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*commons-lang3.*\\.jar"),
                "Expected commons-lang3 (declared in :service). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*spring-core.*\\.jar"),
                "Expected spring-core (declared in :web). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*lombok.*\\.jar"),
                "Expected lombok (declared in :model). Libraries: " + snapshot.libraries());

            // === Bug B: target/generated-sources/* discovered as a source folder ========
            assertTrue(
                snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                    .endsWith("target/generated-sources/annotations")),
                "Expected target/generated-sources/annotations among source folders. " +
                "Got: " + snapshot.sourceFolders());

            // === Bug G: declared compliance level applied =============================
            assertEquals("17", snapshot.compilerSource(),
                "Expected COMPILER_SOURCE=17 from pom <maven.compiler.source>");
            assertEquals("17", snapshot.compilerCompliance(),
                "Expected COMPILER_COMPLIANCE=17 from pom <maven.compiler.source>");

            // === Bug H: APT enabled and lombok on the factory path =====================
            // The display name promises "lombok on the factory path" but isEnabled-only
            // would pass even in the silent-degradation state (every candidate jar missing,
            // APT enabled with empty factory path). Now strictly verifies:
            //  - APT enabled
            //  - hasProjectSpecificFactoryPath (not workspace default)
            //  - lombok jar id present in the registered containers (via reflection helper)
            //  - APT_PROCESSOR_JARS_MISSING did NOT fire
            IJavaProject javaProject = service.getJavaProject();
            assertTrue(AptConfig.isEnabled(javaProject),
                "Expected APT to be enabled because :model declares <annotationProcessorPaths>");
            assertTrue(AptConfig.hasProjectSpecificFactoryPath(javaProject),
                "hasProjectSpecificFactoryPath must be true after wireApt registers lombok");
            assertTrue(factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)).stream()
                    .anyMatch(id -> id != null && id.toLowerCase().contains("lombok")),
                "Factory path must include the lombok processor jar; container ids: "
                    + factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)));
            assertFalse(service.getWarnings().stream()
                    .anyMatch(w -> LoadWarning.APT_PROCESSOR_JARS_MISSING.equals(w.code())),
                "APT_PROCESSOR_JARS_MISSING must not fire on a clean reactor with lombok in "
                    + "~/.m2; warnings: " + service.getWarnings());

            // === Bug F: search works synchronously immediately after loadProject =======
            // (No sleep before this call. If the index isn't ready, findType returns null.)
            IType user = service.findType("com.example.model.User");
            assertNotNull(user, "Expected to resolve com.example.model.User immediately after load");
            IType userService = service.findType("com.example.service.UserService");
            assertNotNull(userService, "Expected to resolve com.example.service.UserService");
            IType controller = service.findType("com.example.web.UserController");
            assertNotNull(controller, "Expected to resolve com.example.web.UserController");

            // === Bug B follow-through: generated type resolves =========================
            IType userMetadata = service.findType("com.example.model.UserMetadata");
            assertNotNull(userMetadata,
                "Expected to resolve com.example.model.UserMetadata generated under target/generated-sources");

            // === Cross-module navigation: User <- UserService <- UserController =======
            // Exact counts pin the search behavior. The fixture's User type has 3 refs
            // per caller file (import + two method-param type usages) × 2 callers = 6.
            // UserService has 3 refs in UserController (import + field-type + constructor
            // call `new UserService()`). An anyMatch-only check would silently pass even
            // if stale-index regressions leaked extras.
            List<SearchMatch> userRefs = service.getSearchService()
                .findReferences(user, IJavaSearchConstants.REFERENCES, 100).matches();
            List<String> userRefFiles = userRefs.stream()
                .map(m -> m.getResource() != null ? m.getResource().getFullPath().toString() : "")
                .toList();
            assertEquals(6, userRefs.size(),
                "Expected exactly 6 User references (3 per caller file × 2 files: import + "
                    + "two method-param type usages each). Got " + userRefs.size()
                    + " matches in files: " + userRefFiles);
            assertEquals(3, userRefFiles.stream().filter(f -> f.contains("UserService")).count(),
                "Expected 3 User refs in UserService.java; got files: " + userRefFiles);
            assertEquals(3, userRefFiles.stream().filter(f -> f.contains("UserController")).count(),
                "Expected 3 User refs in UserController.java; got files: " + userRefFiles);

            // Direct service -> web cross-module hop. UserService has 4 references total:
            //  - 1 self-reference in UserService.java: `LoggerFactory.getLogger(UserService.class)`
            //  - 3 in UserController.java: import + field type + `new UserService()`
            // The self-reference is real and should be reported; the test pins both counts.
            List<SearchMatch> svcRefs = service.getSearchService()
                .findReferences(userService, IJavaSearchConstants.REFERENCES, 100).matches();
            List<String> svcRefFiles = svcRefs.stream()
                .map(m -> m.getResource() != null ? m.getResource().getFullPath().toString() : "")
                .toList();
            assertEquals(4, svcRefs.size(),
                "Expected exactly 4 UserService references (1 self-ref via .class literal in "
                    + "UserService.java + 3 in UserController: import + field type + constructor). "
                    + "Got " + svcRefs.size() + " matches in files: " + svcRefFiles);
            assertEquals(1, svcRefFiles.stream().filter(f -> f.contains("UserService.java")).count(),
                "Expected exactly 1 UserService self-reference (UserService.class on line 9); "
                    + "files: " + svcRefFiles);
            assertEquals(3, svcRefFiles.stream().filter(f -> f.contains("UserController")).count(),
                "Expected 3 UserService refs in UserController.java (import + field-type + "
                    + "constructor); files: " + svcRefFiles);

            // === Sanity: classpath contains the JRE container, all three module sources,
            //     and the generated source from :model ===
            assertTrue(snapshot.containers().stream().anyMatch(c -> c.contains("JRE_CONTAINER")),
                "Expected JRE container on the classpath. Containers: " + snapshot.containers());
            assertFalse(snapshot.sourceFolders().isEmpty(),
                "Expected source folders to be discovered for the reactor");
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.maven.binary");
            else System.setProperty("javalens.maven.binary", previousOverride);
        }
    }

    private static void runMaven(String mvnBinary, Path projectRoot, String... goals)
            throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(mvnBinary);
        for (String g : goals) command.add(g);
        Process p = new ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start();
        StringBuilder captured = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (captured.length() < 8192) captured.append(line).append('\n');
            }
        }
        boolean done = p.waitFor(5, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("mvn " + String.join(" ", goals) + " timed out\n" + captured);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("mvn " + String.join(" ", goals)
                + " failed with exit code " + p.exitValue() + "\n" + captured);
        }
    }

    /**
     * Pull the registered container ids out of an {@link IFactoryPath} via reflection.
     * Duplicated from {@code AnnotationProcessingTest} and {@code EndToEndGradleIntegrationTest}
     * — third copy now exists; the fixtures audit pass should consolidate into
     * {@code AptAssertions} or similar.
     */
    private static List<String> factoryPathContainerIds(IFactoryPath factoryPath) {
        try {
            Method getAllContainers = factoryPath.getClass().getMethod("getAllContainers");
            Object result = getAllContainers.invoke(factoryPath);
            if (!(result instanceof Map<?, ?> containers)) {
                throw new AssertionError("getAllContainers returned non-Map: " + result);
            }
            List<String> ids = new java.util.ArrayList<>();
            for (Object container : containers.keySet()) {
                Method getId = container.getClass().getMethod("getId");
                Object id = getId.invoke(container);
                ids.add(id == null ? null : id.toString());
            }
            return ids;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not introspect factory path containers — "
                + "JDT internal API may have changed (impl class: "
                + factoryPath.getClass().getName() + ")", e);
        }
    }

    private static String resolveMavenBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String binaryName = isWindows ? "mvn.cmd" : "mvn";

        try {
            Process p = new ProcessBuilder(binaryName, "-v").redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            p.waitFor();
            if (p.exitValue() == 0) return binaryName;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }

        Path wrapperDists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperDists)) return null;
        try (Stream<Path> distros = Files.list(wrapperDists)) {
            for (Path distro : distros.toList()) {
                try (Stream<Path> hashes = Files.list(distro)) {
                    for (Path hashDir : hashes.toList()) {
                        Path bin = hashDir.resolve("bin").resolve(binaryName);
                        if (Files.isRegularFile(bin)) return bin.toString();
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }
}
