package org.javalens.core.project;

import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the conventional source folders the build does not report. A model read from the
 * build tool holds the source roots the build compiles; a folder that the conventional
 * layout probes of {@link LinkedFolderConfigurator} find in a module's directory, but that
 * no module reports, was linked before the build was asked, so it stays linked and the load
 * names it with a {@link LoadWarning#INCLUDED_OUTSIDE_BUILD}.
 */
final class ConventionalFolders {

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();

    private ConventionalFolders() {
    }

    /**
     * {@code modules}, each followed by the conventional folders its directory holds that the
     * build does not cover: hand-written layouts after its source roots, generated-source
     * directories after its generated roots, each in probe order. A folder inside or equal to
     * a reported root of any module, compared by {@link ProjectPaths#identity}, is not kept:
     * that root already holds its files. Every other folder is kept once, by the first module
     * whose probe finds it, with one {@link LoadWarning#INCLUDED_OUTSIDE_BUILD} appended to
     * {@code warnings}.
     *
     * <p>The {@code src/} fallback is probed only for a module that reports no root under its
     * directory, so a kept folder never contains one of the module's reported roots.
     */
    static List<BuildModule> keepUndeclared(Path projectRoot, List<BuildModule> modules, List<LoadWarning> warnings) {
        List<Path> reportedRoots = new ArrayList<>();
        for (BuildModule module : modules) {
            module.sourceRoots().forEach(root -> reportedRoots.add(ProjectPaths.identity(root)));
            module.generatedSourceRoots().forEach(root -> reportedRoots.add(ProjectPaths.identity(root)));
        }

        Set<Path> kept = new HashSet<>();
        List<BuildModule> result = new ArrayList<>();
        for (BuildModule module : modules) {
            // The probe skips the src/ fallback when this list already holds a path under the
            // module's directory, so it starts with the module's reported roots.
            List<Path> probed = new ArrayList<>(module.sourceRoots());
            probed.addAll(module.generatedSourceRoots());
            int reported = probed.size();
            LAYOUT.addConventionalSourcePaths(module.dir(), probed);
            List<Path> generatedProbed = new ArrayList<>();
            LAYOUT.addGeneratedSourcePaths(module.dir(), generatedProbed);

            List<Path> sourceRoots = new ArrayList<>(module.sourceRoots());
            List<Path> generatedRoots = new ArrayList<>(module.generatedSourceRoots());
            keep(projectRoot, module, probed.subList(reported, probed.size()), sourceRoots, reportedRoots, kept,
                warnings);
            keep(projectRoot, module, generatedProbed, generatedRoots, reportedRoots, kept, warnings);
            result.add(new BuildModule(module.id(), module.dir(), sourceRoots, generatedRoots,
                module.outputDirs(), module.declaredLevel(), module.evaluated()));
        }
        return result;
    }

    /**
     * Append each folder of {@code probed} that lies in no reported root and is not yet in
     * {@code kept} to {@code roots}, record it in {@code kept}, and name it in
     * {@code warnings}.
     */
    private static void keep(Path projectRoot, BuildModule module, List<Path> probed, List<Path> roots,
            List<Path> reportedRoots, Set<Path> kept, List<LoadWarning> warnings) {
        for (Path folder : probed) {
            Path identity = ProjectPaths.identity(folder);
            if (reportedRoots.stream().anyMatch(identity::startsWith) || !kept.add(identity)) {
                continue;
            }
            roots.add(folder);
            warnings.add(new LoadWarning(LoadWarning.INCLUDED_OUTSIDE_BUILD,
                ProjectPaths.relativeToRoot(projectRoot, folder) + " is linked because it holds a conventional "
                    + "source layout, but the build does not compile it as part of module '" + module.id() + "'",
                "Declare it as a source directory in the build, or remove it.",
                module.id()));
        }
    }
}
