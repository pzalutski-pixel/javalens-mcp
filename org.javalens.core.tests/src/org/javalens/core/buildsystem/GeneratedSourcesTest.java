package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.GradleBinary;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that generated-source directories are linked as source folders, so references to
 * generated symbols (Lombok getters, MapStruct mappers, JPA metamodels, ...) resolve.
 *
 * <p>Maven: each directory under {@code target/generated-sources/} and
 * {@code target/generated-test-sources/} is probed. The tests pre-create a generated file
 * there ({@code target/} is gitignored, so the fixture doesn't ship it) and assert both the
 * source folder and the resulting type resolution.
 *
 * <p>Gradle: the directories Gradle reports as each source set's generated sources
 * ({@code build/generated/sources/annotationProcessor/java/<sourceSet>}) are linked with no
 * warning; a conventional generated-source directory Gradle does not report stays linked
 * with {@code INCLUDED_OUTSIDE_BUILD}. These tests run Gradle on a copy of the fixture.
 */
class GeneratedSourcesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven: target/generated-sources/* directories are added as source folders")
    void mavenGeneratedSourcesAddedAsSourceFolder() throws Exception {
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        // Stand in for what an annotation processor would write at build time.
        Path generatedDir = projectRoot.resolve("target/generated-sources/annotations/com/example");
        Files.createDirectories(generatedDir);
        Files.writeString(generatedDir.resolve("Generated.java"), """
            package com.example;
            public final class Generated {
                public static final String HELLO = "hello";
                private Generated() {}
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/annotations")),
            "Expected target/generated-sources/annotations among source folders. " +
            "Got: " + snapshot.sourceFolders());

        IType generated = service.findType("com.example.Generated");
        assertNotNull(generated, "Expected to resolve com.example.Generated through JDT");
    }

    @Test
    @DisplayName("Maven: target/generated-test-sources/* directories are added as source folders")
    void mavenGeneratedTestSourcesAddedAsSourceFolder() throws Exception {
        // addGeneratedSourcePaths probes BOTH target/generated-sources/* AND
        // target/generated-test-sources/* — these are distinct code paths for two distinct
        // Maven outputs. Test-source processors (e.g. JPA Metamodel for test entities) are
        // a real-world case; without this branch those generated test files are unresolved
        // during analysis of test code.
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        Path generatedTestDir = projectRoot.resolve("target/generated-test-sources/test-annotations/com/example");
        Files.createDirectories(generatedTestDir);
        Files.writeString(generatedTestDir.resolve("GeneratedTest.java"), """
            package com.example;
            public final class GeneratedTest {
                public static final String FIXTURE = "fixture";
                private GeneratedTest() {}
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-test-sources/test-annotations")),
            "Expected target/generated-test-sources/test-annotations among source folders. "
                + "Got: " + snapshot.sourceFolders());

        IType generated = service.findType("com.example.GeneratedTest");
        assertNotNull(generated, "Expected to resolve com.example.GeneratedTest through JDT");
    }

    @Test
    @DisplayName("Maven: multiple processor subdirectories under target/generated-sources/ all become source folders")
    void mavenMultipleProcessorSubdirsAllAddedAsSourceFolders() throws Exception {
        // addImmediateSubdirectories iterates Files.list — every immediate child directory
        // becomes a source folder. Real projects often have multiple processors writing to
        // separate subdirs (annotations from Lombok/MapStruct, jpamodelgen from Hibernate,
        // ...). A single-dir test wouldn't catch a regression that picked only the first.
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        Path annotations = projectRoot.resolve("target/generated-sources/annotations/com/example");
        Path jpa = projectRoot.resolve("target/generated-sources/jpamodelgen/com/example");
        Files.createDirectories(annotations);
        Files.createDirectories(jpa);
        Files.writeString(annotations.resolve("FromAnnotations.java"), """
            package com.example;
            public final class FromAnnotations { private FromAnnotations() {} }
            """);
        Files.writeString(jpa.resolve("FromJpa.java"), """
            package com.example;
            public final class FromJpa { private FromJpa() {} }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/annotations")),
            "Expected target/generated-sources/annotations among source folders; got: "
                + snapshot.sourceFolders());
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/jpamodelgen")),
            "Expected target/generated-sources/jpamodelgen among source folders; got: "
                + snapshot.sourceFolders());

        assertNotNull(service.findType("com.example.FromAnnotations"),
            "Expected FromAnnotations to resolve from the annotations/ subdir");
        assertNotNull(service.findType("com.example.FromJpa"),
            "Expected FromJpa to resolve from the jpamodelgen/ subdir");
    }

    @Test
    @DisplayName("Gradle: the main source set's annotation-processor output, build/generated/sources/annotationProcessor/java/main, is linked with no warning")
    void gradleGeneratedSourcesAddedAsSourceFolder() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("Gradle generated sources")) {
            Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

            // Stand in for what the annotationProcessor configuration writes at build time,
            // in the directory Gradle reports as the main source set's generated sources.
            Path generatedRoot = projectRoot.resolve("build/generated/sources/annotationProcessor/java/main");
            Path generated = writeGradleSource(generatedRoot, "Generated");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(List.of(projectRoot.resolve("src/main/java"), generatedRoot),
                    snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(), service.getWarnings(), "warnings"),
                () -> assertEquals(generated, location(service.findType("com.example.Generated")),
                    "Generated resolves from its file"));
        }
    }

    @Test
    @DisplayName("Gradle: the test source set's annotation-processor output, build/generated/sources/annotationProcessor/java/test, is linked with no warning")
    void gradleGeneratedTestSourcesAddedAsSourceFolder() throws Exception {
        try (GradleBinary gradle = GradleBinary.require("Gradle generated test sources")) {
            Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

            Path generatedRoot = projectRoot.resolve("build/generated/sources/annotationProcessor/java/test");
            Path generated = writeGradleSource(generatedRoot, "GeneratedTest");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(List.of(projectRoot.resolve("src/main/java"), generatedRoot),
                    snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(), service.getWarnings(), "warnings"),
                () -> assertEquals(generated, location(service.findType("com.example.GeneratedTest")),
                    "GeneratedTest resolves from its file"));
        }
    }

    @Test
    @DisplayName("Gradle: a generated-source directory of a task the build does not report stays linked after the reported one, named by INCLUDED_OUTSIDE_BUILD")
    void gradleMultipleTaskSubdirsAllAddedAsSourceFolders() throws Exception {
        // The project applies no kapt plugin, so Gradle reports only annotationProcessor's
        // directory. The conventional probe still finds kapt's, which is kept.
        try (GradleBinary gradle = GradleBinary.require("Gradle generated sources of several tasks")) {
            Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

            Path aptRoot = projectRoot.resolve("build/generated/sources/annotationProcessor/java/main");
            Path kaptRoot = projectRoot.resolve("build/generated/sources/kapt/java/main");
            Path fromApt = writeGradleSource(aptRoot, "FromApt");
            Path fromKapt = writeGradleSource(kaptRoot, "FromKapt");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(projectRoot);
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            assertAll(
                () -> assertEquals(List.of(projectRoot.resolve("src/main/java"), aptRoot, kaptRoot),
                    snapshot.sourceFolders(), "linked source folders"),
                () -> assertEquals(List.of(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                        "build/generated/sources/kapt/java/main is linked because it holds a conventional source "
                            + "layout, but the build does not compile it as part of module ':'",
                        "Declare it as a source directory in the build, or remove it.",
                        ":")),
                    service.getWarnings(), "warnings"),
                () -> assertEquals(fromApt, location(service.findType("com.example.FromApt")),
                    "FromApt resolves from its file"),
                () -> assertEquals(fromKapt, location(service.findType("com.example.FromKapt")),
                    "FromKapt resolves from its file"));
        }
    }

    /** Write class {@code com.example.<name>} under {@code sourceRoot}; returns the file. */
    private static Path writeGradleSource(Path sourceRoot, String name) throws IOException {
        Path packageDir = Files.createDirectories(sourceRoot.resolve("com/example"));
        Path file = packageDir.resolve(name + ".java");
        Files.writeString(file, "package com.example;\n\npublic final class " + name + " {\n    private "
            + name + "() {}\n}\n");
        return file;
    }

    /** The file {@code type} was read from, or null when the type did not resolve. */
    private static Path location(IType type) {
        if (type == null) {
            return null;
        }
        return Path.of(type.getResource().getLocation().toOSString());
    }
}
