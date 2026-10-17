package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Assembles a {@link BuildModel} from an importer's per-concern methods and conventional
 * layout probing of the project's module directories. An importer's
 * {@link BuildSystemImporter#resolve} delegates here for a project with no build system
 * and for the structure Gradle and Bazel fall back to when the tool does not report the
 * model.
 *
 * <p>Module directories are the project root, then the Gradle subprojects named in
 * {@code settings.gradle}, then for Bazel every BUILD package. Each directory is one {@link BuildModule}, identified by its directory as
 * {@link ProjectPaths#relativeToRoot} renders it. The first module at the project root
 * reports the root's existing conventional compiled-output directories as its output; no
 * other module reports output.
 */
final class BuildModelAssembler {

    private static final Logger log = LoggerFactory.getLogger(BuildModelAssembler.class);

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();
    private static final GradleImporter GRADLE = new GradleImporter();
    private static final BazelImporter BAZEL = new BazelImporter();

    private BuildModelAssembler() {
    }

    /**
     * Resolve {@code importer}'s model for {@code projectRoot}. Source layouts are probed
     * before the importer runs its build tool through
     * {@link BuildSystemImporter#getDependencies}, which runs before the level and processor
     * lookups.
     *
     * <p>The model's warnings are module discovery's, in discovery order, followed by the
     * importer's.
     */
    static BuildModel assemble(BuildSystemImporter importer, BuildSystem buildSystem,
            Provenance provenance, Path projectRoot) {
        List<LoadWarning> warnings = new ArrayList<>();
        List<BuildModule> modules = conventionalModules(projectRoot, buildSystem, warnings);
        reportRootOutputDirs(modules, projectRoot);

        List<String> classpath = importer.getDependencies(projectRoot, warnings);
        String level = importer.detectCompilerLevel(projectRoot);

        // Union of the processors the build files declare and any resolved classpath jar
        // carrying the processor SPI descriptor. Only Bazel resolves classpath jars for
        // the scan; its processors come from java_plugin rules that are not read.
        Set<Path> processorJars = new LinkedHashSet<>(importer.detectAnnotationProcessors(projectRoot));
        for (Path jar : importer.getResolvedClasspathJars(projectRoot, warnings)) {
            if (jarDeclaresAnnotationProcessor(jar)) {
                processorJars.add(jar);
            }
        }

        return new BuildModel(buildSystem, provenance, modules, classpath,
            List.copyOf(processorJars), level, new BuildInputs(List.of(), List.of()), warnings);
    }

    /**
     * Discover and probe the module directories. A declared module that is not loaded is
     * appended to {@code warnings} as a {@link LoadWarning#MODULE_NOT_LOADED}.
     */
    private static List<BuildModule> conventionalModules(Path projectRoot, BuildSystem buildSystem,
            List<LoadWarning> warnings) {
        List<BuildModule> modules = new ArrayList<>();
        // Every source root found so far, in link order. The "src/" fallback for a
        // directory is skipped when this list already holds a path under it.
        List<Path> discovered = new ArrayList<>();

        modules.add(probe(projectRoot, projectRoot, discovered));

        for (Path subproject : GRADLE.getSubprojects(projectRoot, warnings)) {
            modules.add(probe(projectRoot, subproject, discovered));
        }

        if (buildSystem == BuildSystem.BAZEL) {
            for (Path targetPackage : BAZEL.getTargetPackages(projectRoot)) {
                modules.add(probe(projectRoot, targetPackage, discovered));
            }
            // Bazel trees without a conventional layout keep .java files next to their
            // BUILD files; each such package directory is its own source root.
            if (discovered.isEmpty()) {
                List<Path> packageDirs = new ArrayList<>();
                BAZEL.addFallbackSourcePaths(projectRoot, packageDirs);
                for (Path dir : packageDirs) {
                    modules.add(new BuildModule(ProjectPaths.relativeToRoot(projectRoot, dir), dir,
                        List.of(dir), List.of(), List.of(), null, false));
                }
            }
        }
        return modules;
    }

    /**
     * Replace the first module whose directory is {@code projectRoot} with a copy whose
     * output is the root's existing conventional output directories, as
     * {@link LinkedFolderConfigurator#addConventionalOutputDirs} finds them. A Bazel root
     * that is itself a BUILD package appears twice in {@code modules}; only the first
     * reports the output, so no directory is reported twice.
     */
    private static void reportRootOutputDirs(List<BuildModule> modules, Path projectRoot) {
        List<Path> outputDirs = new ArrayList<>();
        LAYOUT.addConventionalOutputDirs(projectRoot, outputDirs);
        for (int i = 0; i < modules.size(); i++) {
            BuildModule module = modules.get(i);
            if (module.dir().equals(projectRoot)) {
                modules.set(i, new BuildModule(module.id(), module.dir(), module.sourceRoots(),
                    module.generatedSourceRoots(), outputDirs, module.declaredLevel(), module.evaluated()));
                return;
            }
        }
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
            discovered.subList(sourcesStart, generatedStart),
            discovered.subList(generatedStart, discovered.size()),
            List.of(), null, false);
    }

    /**
     * Returns true iff the jar declares at least one annotation processor via the standard
     * SPI descriptor at {@code META-INF/services/javax.annotation.processing.Processor}.
     * A corrupted / truncated / unreadable jar logs a warning and returns false; this
     * matches "no processor declared" externally but leaves a diagnostic trail so the
     * silent-no-op case is investigable.
     */
    private static boolean jarDeclaresAnnotationProcessor(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/services/javax.annotation.processing.Processor") != null;
        } catch (IOException e) {
            log.warn("Could not read jar {} while scanning for annotation processors: {}",
                jar, e.getMessage());
            return false;
        }
    }
}
