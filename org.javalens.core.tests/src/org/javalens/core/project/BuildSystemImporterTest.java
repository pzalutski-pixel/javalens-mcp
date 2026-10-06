package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the BuildSystemImporter.NONE no-op singleton and the default method
 * implementations. NONE is the fallback for BuildSystem.UNKNOWN; the
 * orchestrator dispatches to it for plain Java projects with no recognized
 * build file. A regression that made NONE non-empty would falsely report
 * compiler level / dependencies / processors for plain projects.
 */
class BuildSystemImporterTest {

    @Test
    @DisplayName("NONE.detectCompilerLevel returns null (signals 'use JVM default')")
    void none_compilerLevel_isNull() {
        // The orchestrator falls back to JVM's runtime feature when this is null,
        // and emits NO warning (no build file to declare a level in). A non-null
        // return would force the orchestrator into the "declared compliance level"
        // path, producing wrong defaults.
        assertNull(BuildSystemImporter.NONE.detectCompilerLevel(Path.of("/anywhere")));
    }

    @Test
    @DisplayName("NONE.getDependencies returns empty list (no project deps for plain Java)")
    void none_getDependencies_isEmpty() {
        List<LoadWarning> warnings = new ArrayList<>();
        List<String> deps = BuildSystemImporter.NONE.getDependencies(Path.of("/anywhere"), warnings);
        assertNotNull(deps);
        assertTrue(deps.isEmpty());
        assertTrue(warnings.isEmpty(),
            "NONE must not emit any LoadWarning — there's no subprocess to fail");
    }

    @Test
    @DisplayName("Default detectAnnotationProcessors returns empty list (interface default)")
    void defaultDetectAnnotationProcessors_isEmpty() {
        // Inherits the default from the interface (lambda-style default body returns List.of()).
        // Catches a regression where the default got overridden in NONE.
        List<Path> processors = BuildSystemImporter.NONE.detectAnnotationProcessors(Path.of("/x"));
        assertNotNull(processors);
        assertTrue(processors.isEmpty());
    }

    @Test
    @DisplayName("Default getResolvedClasspathJars returns empty list (interface default)")
    void defaultGetResolvedClasspathJars_isEmpty() {
        List<LoadWarning> warnings = new ArrayList<>();
        List<Path> jars = BuildSystemImporter.NONE.getResolvedClasspathJars(Path.of("/x"), warnings);
        assertNotNull(jars);
        assertTrue(jars.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("An implementer that overrides only resolve inherits a null level and empty dependencies, processors and classpath jars")
    void resolveOnlyImpl_inheritsEveryPerConcernDefault() {
        // An importer that reads its build tool's model implements resolve alone.
        BuildSystemImporter resolveOnly = new BuildSystemImporter() {
            @Override public BuildModel resolve(Path projectRoot) {
                throw new UnsupportedOperationException("not called");
            }
        };
        List<LoadWarning> warnings = new ArrayList<>();

        assertNull(resolveOnly.detectCompilerLevel(Path.of("/x")), "compiler level");
        assertEquals(List.of(), resolveOnly.getDependencies(Path.of("/x"), warnings), "dependencies");
        assertEquals(List.of(), resolveOnly.detectAnnotationProcessors(Path.of("/x")), "processors");
        assertEquals(List.of(), resolveOnly.getResolvedClasspathJars(Path.of("/x"), warnings), "classpath jars");
        assertEquals(List.of(), warnings, "warnings");
    }

    @Test
    @DisplayName("A custom implementer that overrides detectCompilerLevel and getDependencies inherits the other empty defaults")
    void customImpl_inheritsEmptyDefaults() {
        // An implementer that overrides some per-concern methods inherits the empty
        // defaults of the others.
        BuildSystemImporter minimal = new BuildSystemImporter() {
            @Override public String detectCompilerLevel(Path projectPath) { return "17"; }
            @Override public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) {
                return List.of("/some/jar.jar");
            }
        };
        assertTrue(minimal.detectAnnotationProcessors(Path.of("/x")).isEmpty(),
            "Custom impl that omits detectAnnotationProcessors must inherit empty default");
        assertTrue(minimal.getResolvedClasspathJars(Path.of("/x"), new ArrayList<>()).isEmpty(),
            "Custom impl that omits getResolvedClasspathJars must inherit empty default");
    }
}
