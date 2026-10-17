package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a {@link BuildModel} by conventional layout probing, for a project with no build
 * system and for the structure Gradle falls back to when it does not report the model.
 *
 * <p>Modules are the project root, then the Gradle subprojects named in
 * {@code settings.gradle}. Each directory is one {@link BuildModule}, identified by its
 * directory as {@link ProjectPaths#relativeToRoot} renders it. The root module reports the
 * root's existing conventional compiled-output directories as its output; no other module
 * reports output. The model has no classpath, processors or level.
 */
final class BuildModelAssembler {

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();
    private static final GradleImporter GRADLE = new GradleImporter();

    private BuildModelAssembler() {
    }

    /** The conventional model of {@code projectRoot}; its warnings are module discovery's. */
    static BuildModel assemble(BuildSystem buildSystem, Provenance provenance, Path projectRoot) {
        List<LoadWarning> warnings = new ArrayList<>();
        List<BuildModule> modules = new ArrayList<>();
        // Every source root found so far, in link order. The "src/" fallback for a
        // directory is skipped when this list already holds a path under it.
        List<Path> discovered = new ArrayList<>();

        List<Path> outputDirs = new ArrayList<>();
        LAYOUT.addConventionalOutputDirs(projectRoot, outputDirs);
        BuildModule root = probe(projectRoot, projectRoot, discovered);
        modules.add(new BuildModule(root.id(), root.dir(), root.sourceRoots(), root.generatedSourceRoots(),
            outputDirs, null, false));
        for (Path subproject : GRADLE.getSubprojects(projectRoot, warnings)) {
            modules.add(probe(projectRoot, subproject, discovered));
        }
        return new BuildModel(buildSystem, provenance, modules, List.of(), List.of(), null,
            new BuildInputs(List.of(), List.of()), warnings);
    }

    /**
     * Probe {@code moduleDir} for hand-written layouts, then generated-source
     * directories, appending both to {@code discovered} in that order.
     */
    private static BuildModule probe(Path projectRoot, Path moduleDir, List<Path> discovered) {
        int sourcesStart = discovered.size();
        LAYOUT.addConventionalSourcePaths(moduleDir, discovered);
        int generatedStart = discovered.size();
        LAYOUT.addGeneratedSourcePaths(moduleDir, discovered);
        return new BuildModule(ProjectPaths.relativeToRoot(projectRoot, moduleDir), moduleDir,
            List.copyOf(discovered.subList(sourcesStart, generatedStart)),
            List.copyOf(discovered.subList(generatedStart, discovered.size())),
            List.of(), null, false);
    }
}
