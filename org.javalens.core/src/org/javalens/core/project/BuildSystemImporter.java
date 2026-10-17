package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.Provenance;

import java.nio.file.Path;

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
 */
public interface BuildSystemImporter {

    /**
     * Importer for {@link BuildSystem#UNKNOWN}: the project root laid out by convention, with
     * no classpath beyond the JRE and no declared level, so the orchestrator applies the
     * running JVM's.
     */
    BuildSystemImporter NONE = projectRoot ->
        BuildModelAssembler.assemble(BuildSystem.UNKNOWN, Provenance.CONVENTION, projectRoot);

    /**
     * Resolve the project's build model. The orchestrator calls this exactly once per
     * load and derives source folders, classpath, compiler level and annotation
     * processing from the result alone.
     */
    BuildModel resolve(Path projectRoot);
}
