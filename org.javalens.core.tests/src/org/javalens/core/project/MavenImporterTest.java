package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MavenImporter} branches not exercised by the existing
 * MultiModuleMavenTest / NestedAggregatorMavenTest / SilentSubprocessFailureTest /
 * EndToEndIntegrationTest integration suite.
 *
 * <p>Targets the pure-function pieces (multi-module detection, compiler-level
 * extraction priority order, module walking with cycle detection) plus the
 * failed-subprocess path via the {@code javalens.maven.binary} override.
 */
class MavenImporterTest {

    private final MavenImporter importer = new MavenImporter();
    private String savedBinaryProperty;

    @BeforeEach
    void setUp() {
        savedBinaryProperty = System.getProperty("javalens.maven.binary");
    }

    @AfterEach
    void tearDown() {
        if (savedBinaryProperty == null) {
            System.clearProperty("javalens.maven.binary");
        } else {
            System.setProperty("javalens.maven.binary", savedBinaryProperty);
        }
    }

    // ========== isMultiModule ==========

    @Test
    @DisplayName("isMultiModule returns false when no pom.xml exists")
    void isMultiModule_noPom_returnsFalse(@TempDir Path projectRoot) {
        assertFalse(importer.isMultiModule(projectRoot),
            "No pom.xml → must be false; got: " + importer.isMultiModule(projectRoot));
    }

    @Test
    @DisplayName("isMultiModule returns true when pom has <modules>")
    void isMultiModule_modulesBlock_returnsTrue(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><modules><module>a</module></modules></project>");
        assertTrue(importer.isMultiModule(projectRoot));
    }

    @Test
    @DisplayName("isMultiModule returns true when pom has <packaging>pom</packaging> (aggregator)")
    void isMultiModule_packagingPom_returnsTrue(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><packaging>pom</packaging></project>");
        assertTrue(importer.isMultiModule(projectRoot));
    }

    @Test
    @DisplayName("isMultiModule returns false for ordinary single-module pom")
    void isMultiModule_singleModule_returnsFalse(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><artifactId>simple</artifactId></project>");
        assertFalse(importer.isMultiModule(projectRoot));
    }

    // ========== detectCompilerLevel ==========

    @Test
    @DisplayName("detectCompilerLevel returns null when no pom.xml")
    void detectCompilerLevel_noPom_returnsNull(@TempDir Path projectRoot) {
        assertNull(importer.detectCompilerLevel(projectRoot));
    }

    @Test
    @DisplayName("detectCompilerLevel reads <properties> maven.compiler.release shortcut")
    void detectCompilerLevel_propertiesRelease(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        assertEquals("21", importer.detectCompilerLevel(projectRoot));
    }

    @Test
    @DisplayName("detectCompilerLevel prefers maven.compiler.release over .source/.target")
    void detectCompilerLevel_priority_releaseFirst(@TempDir Path projectRoot) throws IOException {
        // Source-code priority: release > source > target. Pin that when all three
        // are present, release wins.
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><properties>"
                + "<maven.compiler.release>21</maven.compiler.release>"
                + "<maven.compiler.source>17</maven.compiler.source>"
                + "<maven.compiler.target>11</maven.compiler.target>"
                + "</properties></project>");
        assertEquals("21", importer.detectCompilerLevel(projectRoot),
            "<maven.compiler.release> must win over .source/.target");
    }

    @Test
    @DisplayName("detectCompilerLevel falls back to <plugin>maven-compiler-plugin <configuration>")
    void detectCompilerLevel_pluginConfiguration(@TempDir Path projectRoot) throws IOException {
        // No <properties> shortcuts → falls through to plugin configuration parsing.
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><build><plugins><plugin>"
                + "<artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>17</release></configuration>"
                + "</plugin></plugins></build></project>");
        assertEquals("17", importer.detectCompilerLevel(projectRoot),
            "Plugin <configuration><release> must be picked up when no <properties> shortcut exists");
    }

    @Test
    @DisplayName("detectCompilerLevel resolves ${property} placeholders against <properties>")
    void detectCompilerLevel_resolvesPropertyPlaceholder(@TempDir Path projectRoot) throws IOException {
        // Real projects commonly write `<release>${java.version}</release>` with the
        // value declared in <properties>. The resolver must substitute the reference.
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project>"
                + "<properties><java.version>17</java.version></properties>"
                + "<build><plugins><plugin>"
                + "<artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><source>${java.version}</source></configuration>"
                + "</plugin></plugins></build></project>");
        assertEquals("17", importer.detectCompilerLevel(projectRoot),
            "${java.version} must resolve against <properties> definition; got: "
                + importer.detectCompilerLevel(projectRoot));
    }

    @Test
    @DisplayName("detectCompilerLevel returns null when no level is declared anywhere")
    void detectCompilerLevel_noLevel_returnsNull(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><artifactId>simple</artifactId></project>");
        assertNull(importer.detectCompilerLevel(projectRoot));
    }

    // ========== walkModules ==========

    @Test
    @DisplayName("walkModules visits the root + every declared module recursively")
    void walkModules_visitsAllRecursively(@TempDir Path root) throws IOException {
        // Aggregator with modules a, b. Each module has its own pom.xml.
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>a</module><module>b</module></modules></project>");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("b/pom.xml"), "<project/>");

        List<Path> visited = new ArrayList<>();
        importer.walkModules(root, visited::add);

        assertEquals(3, visited.size(),
            "Aggregator + 2 modules = 3 visits; got: " + visited);
        assertTrue(visited.stream().anyMatch(p -> p.endsWith("a")));
        assertTrue(visited.stream().anyMatch(p -> p.endsWith("b")));
    }

    @Test
    @DisplayName("walkModules detects cycles via canonical-path visited set (../sibling pattern)")
    void walkModules_cycleSafe(@TempDir Path root) throws IOException {
        // Module 'a' declares '../a' (relative loop). Visited tracks canonical
        // paths; the second walk into a/ must short-circuit.
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules><module>a</module></modules></project>");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/pom.xml"),
            "<project><modules><module>../a</module></modules></project>");

        List<Path> visited = new ArrayList<>();
        importer.walkModules(root, visited::add);

        // Root + a/ = 2 visits. The `../a` self-reference inside a/pom.xml must
        // NOT cause infinite recursion or an extra visit.
        assertEquals(2, visited.size(),
            "Cycle (../a inside a/pom.xml) must not produce additional visits; got: " + visited);
    }

    @Test
    @DisplayName("walkModules skips <module> entries that don't resolve to directories")
    void walkModules_skipsMissingDirectories(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
            "<project><modules>"
                + "<module>missing</module>"
                + "<module>a</module>"
                + "</modules></project>");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/pom.xml"), "<project/>");

        List<Path> visited = new ArrayList<>();
        importer.walkModules(root, visited::add);

        // Root + a/ — the `missing` module is silently dropped because it has
        // no directory.
        assertEquals(2, visited.size(),
            "Non-existent <module> entry must be skipped; got: " + visited);
    }

    // ========== getDependencies failure path ==========

    @Test
    @DisplayName("getDependencies with non-existent maven binary emits MAVEN_SUBPROCESS_FAILED")
    void getDependencies_invalidMavenBinary_emitsWarning(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        System.setProperty("javalens.maven.binary",
            projectRoot.resolve("never-existed-mvn").toString());

        List<LoadWarning> warnings = new ArrayList<>();
        List<String> deps = importer.getDependencies(projectRoot, warnings);

        assertTrue(deps.isEmpty(),
            "Failed subprocess → empty dependency list; got: " + deps);
        assertTrue(warnings.stream().anyMatch(w ->
            LoadWarning.MAVEN_SUBPROCESS_FAILED.equals(w.code())),
            "Bad mvn binary must produce MAVEN_SUBPROCESS_FAILED; got: " + warnings);
    }

    // ========== detectAnnotationProcessors ==========

    @Test
    @DisplayName("detectAnnotationProcessors returns empty when no <annotationProcessorPaths> blocks exist")
    void detectAnnotationProcessors_noBlocks_returnsEmpty(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
            "<project><artifactId>simple</artifactId></project>");
        List<Path> processors = importer.detectAnnotationProcessors(projectRoot);
        assertTrue(processors.isEmpty(),
            "No <annotationProcessorPaths> → no processors; got: " + processors);
    }
}
