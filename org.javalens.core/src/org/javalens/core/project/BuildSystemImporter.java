package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-build-system contract for resolving a Java project's {@link BuildModel}: its
 * modules and source roots, classpath, declared compiler level, and annotation-processor
 * jars.
 *
 * <p>The orchestrator ({@link ProjectImporter}) dispatches to the implementation
 * mapped to the detected {@link BuildSystem}, calls {@link #resolve} once per load,
 * and derives everything it configures from the returned model. Adding support for a
 * new build system means implementing this interface and registering an instance in
 * the orchestrator's importer map: no edits to switch statements.
 *
 * <p>The per-concern methods below are what each importer's {@link #resolve} composes.
 * Methods that only apply to some build systems have default implementations
 * returning empty: for example, Bazel has no per-target {@code <annotationProcessorPaths>}
 * equivalent we parse, so it inherits the default empty {@link #detectAnnotationProcessors}
 * and instead overrides {@link #getResolvedClasspathJars} to feed the cross-cutting SPI
 * scan.
 */
public interface BuildSystemImporter {

    /**
     * No-op importer used as the fallback for {@link BuildSystem#UNKNOWN}. Returns
     * null compiler level and empty lists for everything; its model probes the root
     * for conventional source layouts, and the orchestrator falls back to a JRE-only
     * classpath and JVM-default compiler level for plain projects.
     */
    BuildSystemImporter NONE = new BuildSystemImporter() {
        @Override public String detectCompilerLevel(Path projectPath) { return null; }
        @Override public List<String> getDependencies(Path projectPath, List<LoadWarning> warnings) { return List.of(); }
        @Override public BuildModel resolve(Path projectRoot) {
            return BuildModelAssembler.assemble(this, BuildSystem.UNKNOWN, Provenance.CONVENTION, projectRoot);
        }
    };

    /**
     * Resolve the project's build model. The orchestrator calls this exactly once per
     * load and derives source folders, classpath, compiler level and annotation
     * processing from the result alone.
     *
     * <p>Every registered importer overrides this. The default keeps implementations
     * written against the per-concern methods alone source compatible, and throws
     * because such an implementation cannot say which build system or provenance its
     * answers have.
     */
    default BuildModel resolve(Path projectRoot) {
        throw new UnsupportedOperationException(
            getClass().getName() + " does not resolve a build model");
    }

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
     * configuration). Build systems without such a declaration return empty, and
     * {@link #resolve} scans {@link #getResolvedClasspathJars} for SPI descriptors as a
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
