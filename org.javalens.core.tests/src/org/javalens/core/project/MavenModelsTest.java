package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins which classpath entries Maven writes stay on the model's classpath. */
class MavenModelsTest {

    @Test
    @DisplayName("Entries that are the reactor's own modules (directories, outputs, installed jars, poms) are left out; other jars stay")
    void reactorEntriesLeaveTheClasspath(@TempDir Path root) throws IOException {
        Path core = Files.createDirectories(root.resolve("core"));
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(core.resolve("pom.xml"), "<project/>");
        Files.writeString(app.resolve("pom.xml"), "<project/>");
        Path library = Files.createDirectories(root.resolve("repo/org/lib/lib/1.0")).resolve("lib-1.0.jar");
        Files.writeString(library, "");
        Path installedCore = Files.createDirectories(root.resolve("repo/g/core/1")).resolve("core-1.jar");
        Files.writeString(installedCore, "");
        Path classpathDir = Files.createDirectories(root.resolve("classpath"));
        // Maven lists an unbuilt sibling as its directory, its classes directory, its pom (a
        // packaging Maven core does not know, such as Tycho's) or its installed jar.
        Files.writeString(classpathDir.resolve("g__app.txt"), String.join(File.pathSeparator,
            core.toString(), core.resolve("target/classes").toString(), core.resolve("pom.xml").toString(),
            installedCore.toString(), library.toString()));

        List<MavenModels.Module> reactor = List.of(
            module(root, "root"), module(core, "core"), module(app, "app"));
        BuildModel model = MavenModels.model(root, Provenance.BUILD_TOOL, reactor, classpathDir, null,
            new ArrayList<>(), List.of());

        assertEquals(List.of(library.toString()), model.classpath());
    }

    private static MavenModels.Module module(Path dir, String artifactId) {
        MavenPom pom = new MavenPom("g", artifactId, "1", List.of(), java.util.Map.of(), java.util.Map.of(),
            null, null, null, null, null, null, null, List.of(), List.of(), List.of(), java.util.Map.of(), null);
        return new MavenModels.Module(dir, dir.resolve("pom.xml"), pom, pom);
    }
}
