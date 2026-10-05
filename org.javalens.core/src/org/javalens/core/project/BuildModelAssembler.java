package org.javalens.core.project;

import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Assembles a {@link BuildModel} from an importer's per-concern methods and conventional
 * layout probing of the project's module directories. Each importer's
 * {@link BuildSystemImporter#resolve} delegates here until it reads its build tool's
 * model.
 *
 * <p>Module directories are found as the orchestrator found them before the model
 * existed, whatever the build system: the Maven module walk from the root, then the
 * Gradle subprojects named in {@code settings.gradle}, then for Bazel every BUILD
 * package. Each directory is one {@link BuildModule}, identified by its root-relative
 * path.
 */
final class BuildModelAssembler {

    private static final Logger log = LoggerFactory.getLogger(BuildModelAssembler.class);

    private static final LinkedFolderConfigurator LAYOUT = new LinkedFolderConfigurator();
    private static final MavenImporter MAVEN = new MavenImporter();
    private static final GradleImporter GRADLE = new GradleImporter();
    private static final BazelImporter BAZEL = new BazelImporter();

    private BuildModelAssembler() {
    }

    /**
     * Resolve {@code importer}'s model for {@code projectRoot}. Source layouts are probed
     * before the importer runs its build tool, and {@link BuildSystemImporter#getDependencies}
     * runs before the level and processor lookups, because {@link GradleImporter} answers
     * those from what its Gradle run wrote.
     */
    static BuildModel assemble(BuildSystemImporter importer, BuildSystem buildSystem,
            Provenance provenance, Path projectRoot) {
        List<BuildModule> modules = conventionalModules(projectRoot, buildSystem);

        List<LoadWarning> warnings = new ArrayList<>();
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

    private static List<BuildModule> conventionalModules(Path projectRoot, BuildSystem buildSystem) {
        List<BuildModule> modules = new ArrayList<>();
        // Every source root found so far, in link order. The "src/" fallback for a
        // directory is skipped when this list already holds a path under it.
        List<Path> discovered = new ArrayList<>();

        // A module declared by the root pom can itself be an aggregator with its own
        // <modules> and no sources; walkModules recurses into it. Without a root
        // pom.xml the walk visits the root alone.
        MAVEN.walkModules(projectRoot, dir -> modules.add(probe(projectRoot, dir, discovered)));

        for (Path subproject : GRADLE.getSubprojects(projectRoot)) {
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
                    modules.add(new BuildModule(moduleId(projectRoot, dir), dir,
                        List.of(dir), List.of(), List.of(), null, false));
                }
            }
        }
        return modules;
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
        return new BuildModule(moduleId(projectRoot, moduleDir), moduleDir,
            discovered.subList(sourcesStart, generatedStart),
            discovered.subList(generatedStart, discovered.size()),
            List.of(), null, false);
    }

    /**
     * Root-relative module directory with '/' separators, "." for the root itself. A
     * directory that does not lie under the root (another drive) keeps its full path.
     */
    private static String moduleId(Path projectRoot, Path moduleDir) {
        if (!moduleDir.startsWith(projectRoot)) {
            return moduleDir.toString();
        }
        String relative = projectRoot.relativize(moduleDir).toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "." : relative;
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
