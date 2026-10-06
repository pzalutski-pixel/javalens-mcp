package org.javalens.core.buildsystem;

import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("realistic Gradle multi-project: classpath, generated sources, level, APT, search and references in a single load")
    void allFixesExercisedInASingleLoad() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("end-to-end Gradle load")) {
            Path projectRoot = helper.copyFixture("realistic-multi-project-gradle");

            // Stage a generated source file in the directory Gradle reports as :model's
            // annotation-processor output, as its annotationProcessor configuration would.
            Path generatedRoot = projectRoot.resolve("model/build/generated/sources/annotationProcessor/java/main");
            Path generatedDir = Files.createDirectories(generatedRoot.resolve("com/example/model"));
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

            // === A clean Gradle build loads with no warning ===============================
            assertEquals(List.of(), service.getWarnings(), "warnings");

            // === The classpath is every subproject's external jars, no project jar ======
            // :model declares compileOnly lombok, :service slf4j-api and commons-lang3, :web
            // spring-core, which depends on spring-jcl.
            List<String> libraryNames = snapshot.libraries().stream()
                .map(library -> library.getFileName().toString())
                .sorted()
                .toList();
            assertEquals(List.of("commons-lang3-3.13.0.jar", "lombok-1.18.30.jar", "slf4j-api-2.0.9.jar",
                    "spring-core-6.0.13.jar", "spring-jcl-6.0.13.jar"),
                libraryNames, "library file names");

            // === The processor jars are :model's annotationProcessor configuration ======
            List<String> processorNames = service.getBuildModel().processorJars().stream()
                .map(jar -> jar.getFileName().toString())
                .toList();
            assertEquals(List.of("lombok-1.18.30.jar"), processorNames, "processor jar file names");

            // === Source folders: each subproject's sources, :model's generated sources ==
            // With processors wired, JDT's APT adds its own generated-source folder,
            // .apt_generated in the workspace project, after the linked ones.
            Path workspaceProject = Path.of(service.getJavaProject().getProject().getLocation().toOSString());
            assertEquals(List.of(
                    projectRoot.resolve("model/src/main/java"),
                    generatedRoot,
                    projectRoot.resolve("service/src/main/java"),
                    projectRoot.resolve("web/src/main/java"),
                    workspaceProject.resolve(".apt_generated")),
                snapshot.sourceFolders(), "source folders");

            // === The declared sourceCompatibility is applied ===========================
            assertEquals("17", snapshot.compilerSource(),
                "Expected COMPILER_SOURCE=17 from sourceCompatibility = JavaVersion.VERSION_17");
            assertEquals("17", snapshot.compilerCompliance(),
                "Expected COMPILER_COMPLIANCE=17 from sourceCompatibility = JavaVersion.VERSION_17");

            // === APT is enabled and its factory path holds the lombok jar ===============
            // isEnabled alone is insufficient: when every candidate jar is missing, APT is
            // enabled with an empty factory path. This is the check AnnotationProcessingTest
            // applies to the Maven path.
            IJavaProject javaProject = service.getJavaProject();
            assertTrue(AptConfig.isEnabled(javaProject),
                "Expected APT to be enabled because :model declares annotationProcessor 'org.projectlombok:lombok'");
            assertTrue(AptConfig.hasProjectSpecificFactoryPath(javaProject),
                "hasProjectSpecificFactoryPath must be true after wireApt registers the lombok jar");
            assertTrue(factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)).stream()
                    .anyMatch(id -> id != null && id.toLowerCase().contains("lombok")),
                "Factory path must include the lombok processor jar; container ids: "
                    + factoryPathContainerIds(AptConfig.getFactoryPath(javaProject)));

            // === Types resolve from their files right after loadProject ================
            IType user = service.findType("com.example.model.User");
            assertEquals(projectRoot.resolve("model/src/main/java/com/example/model/User.java"),
                location(user), "User");
            assertEquals(projectRoot.resolve("service/src/main/java/com/example/service/UserService.java"),
                location(service.findType("com.example.service.UserService")), "UserService");
            assertEquals(projectRoot.resolve("web/src/main/java/com/example/web/UserController.java"),
                location(service.findType("com.example.web.UserController")), "UserController");
            assertEquals(generatedDir.resolve("UserMetadata.java"),
                location(service.findType("com.example.model.UserMetadata")), "generated UserMetadata");

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
        }
    }

    /** The file {@code type} was read from, or null when the type did not resolve. */
    private static Path location(IType type) {
        if (type == null) {
            return null;
        }
        return Path.of(type.getResource().getLocation().toOSString());
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

}
