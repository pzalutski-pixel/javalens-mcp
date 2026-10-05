package org.javalens.core.project.model;

import org.javalens.core.project.BuildSystem;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a load needs to know about a project's build, resolved once per load by the
 * build system's importer. The orchestrator derives source folders, classpath, compiler
 * level and annotation processing from this model alone.
 *
 * @param buildSystem build system the model was resolved for
 * @param provenance where the structure came from
 * @param modules modules in build order; their source roots become linked source folders
 * @param classpath dependency jars and class directories, absolute paths in classpath order
 * @param processorJars annotation-processor jars for the APT factory path
 * @param analysisLevel Java level to analyze at, or {@code null} when nothing is declared
 * @param inputs build files to watch for changes that require a reload
 * @param warnings problems met while resolving the model
 */
public record BuildModel(
        BuildSystem buildSystem,
        Provenance provenance,
        List<BuildModule> modules,
        List<String> classpath,
        List<Path> processorJars,
        String analysisLevel,
        BuildInputs inputs,
        List<LoadWarning> warnings) {

    public BuildModel {
        modules = List.copyOf(modules);
        classpath = List.copyOf(classpath);
        processorJars = List.copyOf(processorJars);
        warnings = List.copyOf(warnings);
    }
}
