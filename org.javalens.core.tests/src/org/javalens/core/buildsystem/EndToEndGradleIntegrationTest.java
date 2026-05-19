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
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle counterpart of {@link EndToEndIntegrationTest}: a realistic multi-project Gradle
 * build that exercises every fix in a single load. Mirrors the Maven test's structure so
 * regressions on the Gradle side surface alongside Maven ones.
 *
 * <p>Layout: {@code model} (Lombok APT, declares Java 17), {@code service} (depends on
 * {@code :model}, slf4j, commons-lang3), {@code web} (depends on {@code :service},
 * spring-core).
 */
class EndToEndGradleIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("realistic Gradle multi-project: every fix exercised in a single load")
    void allFixesExercisedInASingleLoad() throws Exception {
        String gradle = resolveGradleBinary();
        TestEnvironment.requireOrSkip(gradle, "Gradle binary (end-to-end Gradle load)");

        String previousOverride = System.getProperty("javalens.gradle.binary");
        System.setProperty("javalens.gradle.binary", gradle);
        try {
            Path projectRoot = helper.copyFixture("realistic-multi-project-gradle");

            // Bug B (Gradle side): stage a generated source file under :model the way an
            // annotationProcessor task would.
            Path generatedDir = projectRoot.resolve(
                "model/build/generated/sources/annotationProcessor/main/java/com/example/model");
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

            // === Bug X: clean Gradle build, no warnings ================================
            List<LoadWarning> warnings = service.getWarnings();
            assertTrue(warnings.isEmpty(),
                "Expected zero warnings on a clean Gradle multi-project build. Got: " + warnings);

            // === Bug D: every subproject's external dep is on the classpath ============
            assertTrue(snapshot.hasLibraryMatching(".*slf4j-api.*\\.jar"),
                "Expected slf4j-api (declared in :service). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*commons-lang3.*\\.jar"),
                "Expected commons-lang3 (declared in :service). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*spring-core.*\\.jar"),
                "Expected spring-core (declared in :web). Libraries: " + snapshot.libraries());
            assertTrue(snapshot.hasLibraryMatching(".*lombok.*\\.jar"),
                "Expected lombok on :model's annotationProcessor configuration. Libraries: " + snapshot.libraries());

            // === Bug B (Gradle): build/generated/sources/* discovered as source folder ==
            assertTrue(
                snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                    .endsWith("build/generated/sources/annotationProcessor/main/java")),
                "Expected build/generated/sources/annotationProcessor/main/java among source folders. " +
                "Got: " + snapshot.sourceFolders());

            // === Bug G (Gradle): declared sourceCompatibility applied ==================
            assertEquals("17", snapshot.compilerSource(),
                "Expected COMPILER_SOURCE=17 from sourceCompatibility = JavaVersion.VERSION_17");
            assertEquals("17", snapshot.compilerCompliance(),
                "Expected COMPILER_COMPLIANCE=17 from sourceCompatibility = JavaVersion.VERSION_17");

            // === Bug H (Gradle): APT enabled + factory path actually contains lombok ===
            // isEnabled alone is insufficient — the silent-degradation path (every candidate
            // jar fails Files.isRegularFile → APT enabled, factory path empty) would pass an
            // isEnabled-only check. Replicating the strict check that
            // AnnotationProcessingTest applies to the Maven path.
            IJavaProject javaProject = service.getJavaProject();
            assertTrue(AptConfig.isEnabled(javaProject),
                "Expected APT to be enabled because :model declares annotationProcessor 'org.projectlombok:lombok'");
            assertTrue(AptConfig.hasProjectSpecificFactoryPath(javaProject),
                "hasProjectSpecificFactoryPath must be true after wireApt registers the lombok jar");
            assertTrue(factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)).stream()
                    .anyMatch(id -> id != null && id.toLowerCase().contains("lombok")),
                "Factory path must include the lombok processor jar; container ids: "
                    + factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)));
            assertFalse(service.getWarnings().stream()
                    .anyMatch(w -> LoadWarning.APT_PROCESSOR_JARS_MISSING.equals(w.code())),
                "APT_PROCESSOR_JARS_MISSING must not fire on a clean Gradle build with the "
                    + "lombok jar present in Gradle's cache; warnings: " + service.getWarnings());

            // === Bug F: search works synchronously after loadProject ===================
            IType user = service.findType("com.example.model.User");
            assertNotNull(user, "Expected to resolve com.example.model.User immediately after load");
            IType userService = service.findType("com.example.service.UserService");
            assertNotNull(userService, "Expected to resolve com.example.service.UserService");
            IType controller = service.findType("com.example.web.UserController");
            assertNotNull(controller, "Expected to resolve com.example.web.UserController");

            // === Generated type resolves =================================================
            IType generated = service.findType("com.example.model.UserMetadata");
            assertNotNull(generated, "Expected to resolve generated UserMetadata");

            // === Cross-subproject find_references =======================================
            // The fixture has 3 type references to User per caller file: the import
            // declaration plus two method parameter type usages. UserService.java +
            // UserController.java = 6 total. Pinning the exact count catches the case
            // where a search regression leaks stale matches (e.g., index from a previous
            // load) — an anyMatch-only check would still pass with extras present.
            List<SearchMatch> userRefs = service.getSearchService()
                .findReferences(user, IJavaSearchConstants.REFERENCES, 100).matches();
            List<String> userRefFiles = userRefs.stream()
                .map(m -> m.getResource() != null ? m.getResource().getFullPath().toString() : "")
                .toList();
            assertEquals(6, userRefs.size(),
                "Expected exactly 6 User references (3 per caller file × 2 files: import "
                    + "+ two method-param type usages each). Got " + userRefs.size()
                    + " matches in files: " + userRefFiles);
            long inUserService = userRefFiles.stream()
                .filter(f -> f.contains("UserService")).count();
            long inUserController = userRefFiles.stream()
                .filter(f -> f.contains("UserController")).count();
            assertEquals(3, inUserService,
                "Expected 3 User refs in UserService.java; got " + inUserService + ": "
                    + userRefFiles);
            assertEquals(3, inUserController,
                "Expected 3 User refs in UserController.java; got " + inUserController + ": "
                    + userRefFiles);
        } finally {
            if (previousOverride == null) System.clearProperty("javalens.gradle.binary");
            else System.setProperty("javalens.gradle.binary", previousOverride);
        }
    }

    /**
     * Pull the registered container ids out of an {@link IFactoryPath} via reflection.
     * Duplicated from {@code AnnotationProcessingTest.factoryPathContainerIds} — consolidate
     * to a shared test fixture (e.g. {@code AptAssertions}) when a third caller materializes
     * or when the per-file audit reaches the fixtures pass.
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

    private static String resolveGradleBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String binaryName = isWindows ? "gradle.bat" : "gradle";

        String envHome = System.getenv("JAVALENS_TEST_GRADLE_HOME");
        if (envHome != null && !envHome.isBlank()) {
            Path bin = Path.of(envHome).resolve("bin").resolve(binaryName);
            if (Files.isRegularFile(bin)) return bin.toString();
        }

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

        Path tools = Path.of(System.getProperty("user.home"), "javalens-tools");
        if (Files.isDirectory(tools)) {
            try (Stream<Path> entries = Files.list(tools)) {
                for (Path entry : entries.toList()) {
                    Path bin = entry.resolve("bin").resolve(binaryName);
                    if (Files.isRegularFile(bin)) return bin.toString();
                }
            } catch (IOException ignored) {}
        }

        Path wrapperDists = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        if (Files.isDirectory(wrapperDists)) {
            try (Stream<Path> distros = Files.walk(wrapperDists, 4)) {
                return distros.filter(p -> p.getFileName() != null
                            && binaryName.equals(p.getFileName().toString()))
                        .filter(p -> p.getParent() != null
                            && "bin".equals(p.getParent().getFileName().toString()))
                        .map(Path::toString)
                        .findFirst()
                        .orElse(null);
            } catch (IOException ignored) {}
        }

        return null;
    }
}
