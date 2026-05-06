package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug B — annotation-processor / build-system generated source directories were never
 * added as source folders, so references to generated symbols (Lombok getters, MapStruct
 * mappers, JPA metamodels, ...) showed as unresolved during analysis.
 *
 * <p>The fix probes Maven {@code target/generated-sources/*} and Gradle
 * {@code build/generated/sources/*} as source folders during project import.
 *
 * <p>This test pre-creates a {@code Generated.java} under
 * {@code target/generated-sources/annotations/} (target/ is gitignored, so the fixture
 * doesn't ship it) and asserts both the source-folder discovery and the resulting type
 * resolution.
 */
class GeneratedSourcesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("target/generated-sources/* directories are added as source folders")
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

        // The discovered source folders must include the generated dir.
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/annotations")),
            "Expected target/generated-sources/annotations among source folders. " +
            "Got: " + snapshot.sourceFolders());

        // And the generated type must resolve through JDT.
        IType generated = service.findType("com.example.Generated");
        assertNotNull(generated, "Expected to resolve com.example.Generated through JDT");
    }
}
