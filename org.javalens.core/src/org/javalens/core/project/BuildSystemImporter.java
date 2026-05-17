package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-build-system contract for extracting a Java project's classpath, declared
 * compiler level, and annotation-processor jars.
 *
 * <p>The orchestrator ({@link ProjectImporter}) dispatches to the implementation
 * mapped to the detected {@link BuildSystem}, so adding support for a new build
 * system means implementing this interface and registering an instance in the
 * orchestrator's importer map — no edits to switch statements.
 *
 * <p>Methods that only apply to some build systems have default implementations
 * returning empty: for example, Bazel has no per-target {@code <annotationProcessorPaths>}
 * equivalent we parse, so it inherits the default empty {@link #detectAnnotationProcessors}
 * and instead overrides {@link #getResolvedClasspathJars} to feed the orchestrator's
 * cross-cutting SPI scan.
 *
 * <p>Source-path discovery is intentionally <i>not</i> part of this contract — each
 * build system's source layout (Maven module walk, Gradle subproject include,
 * Bazel BUILD package walk + fallback) is too different in shape to unify
 * cleanly. The orchestrator handles those per-importer.
 */
public interface BuildSystemImporter {

    /**
     * No-op importer used as the fallback for {@link BuildSystem#UNKNOWN}. Returns
     * null compiler level and empty lists for everything; the orchestrator falls
     * back to JRE-only classpath and JVM-default compiler level for plain projects.
     */
    BuildSystemImporter NONE = new BuildSystemImporter() {
        @Override public String detectCompilerLevel(Path projectPath) { return null; }
        @Override public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) { return List.of(); }
    };

    /**
     * Read the declared Java source level from this build system's project files,
     * or {@code null} if no level is declared. The orchestrator falls back to the
     * runtime JVM's feature version when null, emitting a
     * {@code COMPLIANCE_LEVEL_UNKNOWN} warning if the build system was detected.
     */
    String detectCompilerLevel(Path projectPath);

    /**
     * Annotation processors explicitly declared in this build system's project files
     * (Maven {@code <annotationProcessorPaths>}, Gradle {@code annotationProcessor}
     * configuration). Build systems without such a declaration return empty, and the
     * orchestrator scans {@link #getResolvedClasspathJars} for SPI descriptors as a
     * fallback.
     */
    default List<Path> detectAnnotationProcessors(Path projectPath) {
        return List.of();
    }

    /**
     * Classpath jars to feed JDT's library entries. Append a {@link LoadWarning} to
     * {@code warnings} when a recoverable problem occurs (subprocess fails, build
     * hasn't run, etc.) so the orchestrator can surface degraded loads in
     * {@code load_project}.
     */
    List<String> getDependencies(Path projectPath, List<LoadWarning> warnings);

    /**
     * Resolved classpath jars to scan for SPI-declared annotation processors. Only
     * meaningful for build systems without a per-target processor-path declaration
     * we parse (currently just Bazel — its annotation processors come from
     * {@code java_plugin} rules that we don't introspect). Default returns empty.
     */
    default List<Path> getResolvedClasspathJars(Path projectPath, List<LoadWarning> warnings) {
        return List.of();
    }
}
