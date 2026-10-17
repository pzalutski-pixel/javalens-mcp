package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link BuildSystemImporter#NONE}, the importer of a project with no recognized build
 * file: the root laid out by convention, with no classpath, processors, level, inputs or
 * warnings, so the orchestrator applies the running JVM's level without a warning.
 */
class BuildSystemImporterTest {

    @Test
    @DisplayName("NONE resolves the root by convention, with no classpath, processors, level, inputs or warnings")
    void noneResolvesTheRootByConvention(@TempDir Path root) throws IOException {
        Path sources = Files.createDirectories(root.resolve("src/main/java"));
        Path classes = Files.createDirectories(root.resolve("target/classes"));

        assertEquals(new BuildModel(BuildSystem.UNKNOWN, Provenance.CONVENTION,
                List.of(new BuildModule(".", root, List.of(sources), List.of(), List.of(classes), null, false)),
                List.of(), List.of(), null, new BuildInputs(List.of(), List.of()), List.of()),
            BuildSystemImporter.NONE.resolve(root));
    }
}
