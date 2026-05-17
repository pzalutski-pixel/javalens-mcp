package org.javalens.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.javalens.core.project.model.LoadWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle/Bazel) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 *
 * Uses linked folders to keep all Eclipse metadata in the workspace,
 * not polluting the user's actual project directory.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    /**
     * Warnings accumulated during the most recent {@link #configureJavaProject} call.
     * Reset at the start of each invocation. {@link JdtServiceImpl#getWarnings()} reads
     * this list to surface degraded-load scenarios in the {@code load_project} response.
     */
    private final List<LoadWarning> warnings = new ArrayList<>();

    /**
     * Per-directory source-path discovery + linked-folder creation. Split out of this class
     * in 1.4.0 (E-10 C1): the layout probing and Eclipse linked-folder wiring don't depend
     * on the build system, so the orchestrator delegates to them after harvesting source
     * paths from build-system-specific aggregators.
     */
    private final LinkedFolderConfigurator linkedFolderConfigurator = new LinkedFolderConfigurator();

    /**
     * Bazel build-system support (1.4.0 E-10 C2): source-path discovery for
     * BUILD-anchored layouts, classpath assembly from bazel-bin/bazel-out (with
     * symlink-aware dedup), and javacopts-derived compiler-level extraction.
     */
    private final BazelImporter bazelImporter = new BazelImporter();

    /**
     * Gradle build-system support (1.4.0 E-10 C3): subproject discovery, classpath
     * assembly via an injected init script, and compiler-level / annotation-processor
     * extraction from the aux files that script writes. Caches compliance and processor
     * data internally so the orchestrator's later calls in the configure flow can read
     * after the aux files have been cleaned up.
     */
    private final GradleImporter gradleImporter = new GradleImporter();

    /**
     * Maven build-system support (1.4.0 E-10 C4): multi-module detection, reactor walking,
     * dependency assembly via {@code mvn dependency:build-classpath}, compiler-level
     * extraction from {@code pom.xml}, and annotation-processor discovery from
     * {@code <annotationProcessorPaths>} blocks across the reactor.
     */
    private final MavenImporter mavenImporter = new MavenImporter();

    /**
     * Per-build-system dispatch table. Lookup yields the right {@link BuildSystemImporter}
     * for the detected {@link BuildSystem}; {@link BuildSystem#UNKNOWN} falls back to
     * {@link BuildSystemImporter#NONE}. Adding a new build system means implementing
     * {@code BuildSystemImporter}, instantiating it as a field above, and adding one entry
     * here — no switch-statement edits in the orchestrator methods below.
     */
    private final Map<BuildSystem, BuildSystemImporter> importers = Map.of(
        BuildSystem.MAVEN, mavenImporter,
        BuildSystem.GRADLE, gradleImporter,
        BuildSystem.BAZEL, bazelImporter
    );

    private BuildSystemImporter importerFor(BuildSystem buildSystem) {
        return importers.getOrDefault(buildSystem, BuildSystemImporter.NONE);
    }

    /**
     * Returns the warnings from the most recent project import.
     */
    public List<LoadWarning> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Configure an IProject as a Java project with proper classpath.
     * Creates linked folders for source directories to keep Eclipse metadata
     * in the workspace, not polluting the user's project directory.
     *
     * @param project The workspace project (must be created and open)
     * @param projectPath The filesystem path to the external project
     * @param workspaceManager WorkspaceManager for creating linked folders
     * @return Configured IJavaProject
     * @throws CoreException if configuration fails
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        // Reset accumulated state from any previous load. GradleImporter resets its
        // own caches internally at the start of getDependencies(); the orchestrator
        // does not need to clear them here.
        warnings.clear();

        IJavaProject javaProject = JavaCore.create(project);

        // Build classpath entries
        List<IClasspathEntry> entries = new ArrayList<>();

        // 1. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 2. Create linked folders and add source entries. Source-path aggregation is
        // build-system-aware (Maven modules, Gradle subprojects, Bazel packages); the
        // per-directory layout probing and linked-folder creation live in
        // LinkedFolderConfigurator.
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        linkedFolderConfigurator.addLinkedSourceFolders(
            entries, project, projectPath, sourcePaths, workspaceManager,
            mavenImporter.isMultiModule(projectPath));

        // 3. Add dependency JARs from build system
        addDependencyEntries(entries, projectPath);

        // 4. Add output location
        IPath outputPath = project.getFullPath().append("bin");

        // Set the classpath
        javaProject.setRawClasspath(
            entries.toArray(new IClasspathEntry[0]),
            outputPath,
            new NullProgressMonitor()
        );

        // Bug G fix: apply the project's declared compiler source level. Without this, JDT
        // falls back to defaults that may be older than the source code, causing legitimate
        // language features (e.g. Java 21 record patterns) to be reported as syntax errors.
        BuildSystem buildSystem = detectBuildSystem(projectPath);
        applyCompilerOptions(javaProject, projectPath, buildSystem);

        // Bug H fix: enable annotation processing and register the processor jars declared
        // by the project. Without this, code that references annotation-processor-generated
        // members (Lombok @Data getters, MapStruct mappers, JPA metamodels) shows those
        // members as unresolved during analysis.
        applyAnnotationProcessing(javaProject, projectPath, buildSystem);

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Enable JDT's APT framework on the project and register annotation-processor jars on
     * its factory path. We collect from two complementary sources:
     *
     * <ol>
     *   <li><b>Build-system-specific declarations</b> — {@code <annotationProcessorPaths>}
     *       (Maven), the {@code annotationProcessor} configuration (Gradle).</li>
     *   <li><b>Generic classpath scan</b> — any jar on the resolved classpath that contains
     *       {@code META-INF/services/javax.annotation.processing.Processor} is treated as
     *       a processor. This catches Bazel projects (where processors come from
     *       {@code java_plugin} rules) and any system that places a processor jar on the
     *       compile classpath without a separate processor-path declaration.</li>
     * </ol>
     */
    private void applyAnnotationProcessing(IJavaProject javaProject, java.nio.file.Path projectPath, BuildSystem buildSystem) {
        BuildSystemImporter importer = importerFor(buildSystem);
        java.util.LinkedHashSet<java.nio.file.Path> processorJars = new java.util.LinkedHashSet<>();
        processorJars.addAll(importer.detectAnnotationProcessors(projectPath));

        // Note: an earlier draft also fired GENERATED_SOURCES_NOT_FOUND when processors
        // were declared but no generated-source directory existed. That produced false
        // positives for Lombok, which modifies AST in-place rather than emitting .java —
        // a Lombok-only project always has the processor declared and never has
        // target/generated-sources/. Without inspecting each processor's behavior we
        // cannot reliably distinguish "build hasn't run" from "this processor doesn't
        // emit", so the warning was removed.
        // Cross-cutting scan: pick up any jar with a processor SPI descriptor so Bazel +
        // any classpath that quietly carries a processor (compileOnly, transitive, etc.)
        // gets APT wired up. Maven/Gradle declare processors via build-file blocks already
        // harvested above; only Bazel overrides getResolvedClasspathJars (the default
        // returns empty), so this loop is a no-op for non-Bazel projects.
        for (java.nio.file.Path jar : importer.getResolvedClasspathJars(projectPath, warnings)) {
            if (jarDeclaresAnnotationProcessor(jar)) {
                processorJars.add(jar);
            }
        }

        if (processorJars.isEmpty()) return;

        try {
            AptConfig.setEnabled(javaProject, true);
            IFactoryPath factoryPath = AptConfig.getDefaultFactoryPath(javaProject);
            int registered = 0;
            for (java.nio.file.Path jar : processorJars) {
                if (Files.isRegularFile(jar)) {
                    factoryPath.addExternalJar(jar.toFile());
                    registered++;
                }
            }
            AptConfig.setFactoryPath(javaProject, factoryPath);
            log.info("Enabled APT with {} processor jar(s)", registered);
        } catch (CoreException e) {
            log.warn("Failed to configure APT: {}", e.getMessage());
        }
    }

    /**
     * Returns true iff the jar declares at least one annotation processor via the standard
     * SPI descriptor at {@code META-INF/services/javax.annotation.processing.Processor}.
     */
    private boolean jarDeclaresAnnotationProcessor(java.nio.file.Path jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/services/javax.annotation.processing.Processor") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Read the project's declared Java source level from build metadata and apply it to
     * the {@link IJavaProject}. Sets {@code COMPILER_SOURCE}, {@code COMPILER_COMPLIANCE},
     * and {@code COMPILER_CODEGEN_TARGET_PLATFORM} so the JDT compiler parses and validates
     * code at the same level the build system uses.
     */
    private void applyCompilerOptions(IJavaProject javaProject, java.nio.file.Path projectPath, BuildSystem buildSystem) {
        String level = importerFor(buildSystem).detectCompilerLevel(projectPath);
        // Final fallback for any build system that didn't surface a level: use the running
        // JVM's feature version. This keeps Plain Java projects (no build file) and
        // partially-declared Maven/Gradle/Bazel projects parsing modern syntax instead of
        // silently inheriting an older JDT default. When a real build system was detected
        // but no level surfaced, emit COMPLIANCE_LEVEL_UNKNOWN so the agent knows we
        // guessed (the build file likely declares a level we didn't find). Plain Java has
        // no place to declare one, so the fallback is expected — no warning there.
        if (level == null) {
            level = String.valueOf(Runtime.version().feature());
            if (buildSystem != BuildSystem.UNKNOWN) {
                warnings.add(new LoadWarning(
                    LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
                    "Could not determine declared Java source level for " + buildSystem +
                        " project; defaulting to runtime JVM major version " + level,
                    "Declare maven.compiler.source/release in pom.xml, sourceCompatibility " +
                        "in build.gradle, or javacopts -source/--release in BUILD.bazel so " +
                        "language-level features parse against the intended grammar."));
            } else {
                log.info("No compiler level declared; defaulting to runtime JVM major version {}", level);
            }
        }
        javaProject.setOption(JavaCore.COMPILER_SOURCE, level);
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, level);
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, level);
        // Enable unused-import as a warning so the JDT reconcile surfaces it as an
        // IProblem. The get_quick_fixes tool's documented "UnusedImport → remove_import"
        // fix path depends on this — without the option set, JDT defaults to "ignore"
        // and the fix is silently never offered.
        javaProject.setOption(JavaCore.COMPILER_PB_UNUSED_IMPORT, JavaCore.WARNING);
        log.info("Applied Java source level {} from build metadata", level);
    }

    /**
     * Detect build system from project structure.
     */
    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        // Bazel: check root-level workspace markers (not BUILD files, which are per-package)
        if (Files.exists(projectPath.resolve("MODULE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE"))) {
            return BuildSystem.BAZEL;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * Get all source directories, including from submodules if multi-module project.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();

        // Walk the Maven module tree recursively. A module declared by the root pom can
        // itself be an aggregator (<packaging>pom</packaging> with its own <modules> and
        // no src/main/java); a flat depth-1 walk would visit it, find nothing, and never
        // descend, leaving every nested leaf module's sources unindexed (issue #8).
        // MavenImporter.walkModules handles the recursion + cycle guard; the visitor here
        // probes each module root for standard source layouts.
        mavenImporter.walkModules(projectPath,
            module -> linkedFolderConfigurator.addSourcePathsFromDirectory(module, sourcePaths));

        // If multi-project Gradle, also check each subproject. Without this, subproject
        // src/main/java directories and their build/generated/sources/* are absent from
        // the classpath and types declared in subprojects show as unresolved.
        for (java.nio.file.Path subproject : gradleImporter.getSubprojects(projectPath)) {
            linkedFolderConfigurator.addSourcePathsFromDirectory(subproject, sourcePaths);
        }

        // For Bazel multi-target builds, walk for every BUILD/BUILD.bazel package and
        // probe it for standard layouts (src/main/java, etc.). Without this, only targets
        // co-located with their .java files are discovered (handled by the fallback below).
        if (detectBuildSystem(projectPath) == BuildSystem.BAZEL) {
            for (java.nio.file.Path targetPkg : bazelImporter.getTargetPackages(projectPath)) {
                linkedFolderConfigurator.addSourcePathsFromDirectory(targetPkg, sourcePaths);
            }
        }

        // For Bazel projects without standard source layout, scan for directories that
        // hold both BUILD files and .java sources directly.
        if (sourcePaths.isEmpty() && detectBuildSystem(projectPath) == BuildSystem.BAZEL) {
            bazelImporter.addFallbackSourcePaths(projectPath, sourcePaths);
        }

        return sourcePaths;
    }

    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath) {
        BuildSystem buildSystem = detectBuildSystem(projectPath);

        List<String> jars = importerFor(buildSystem).getDependencies(projectPath, warnings);

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                IPath eclipsePath = new Path(jar);
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }

        // Add compiled classes directories (Maven)
        addIfExists(entries, projectPath, "target/classes");
        addIfExists(entries, projectPath, "target/test-classes");
        // Add compiled classes directories (Gradle)
        addIfExists(entries, projectPath, "build/classes/java/main");
        addIfExists(entries, projectPath, "build/classes/java/test");

        log.info("Added {} dependency entries from {}", jars.size(), buildSystem);
    }

    private void addIfExists(List<IClasspathEntry> entries, java.nio.file.Path projectPath, String relativePath) {
        java.nio.file.Path fullPath = projectPath.resolve(relativePath);
        if (Files.exists(fullPath) && Files.isDirectory(fullPath)) {
            IPath eclipsePath = new Path(fullPath.toString());
            entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
        }
    }

    /**
     * Count Java source files in the project.
     * Supports multi-module projects.
     */
    public int countSourceFiles(java.nio.file.Path projectPath) {
        int count = 0;
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException e) {
                log.warn("Failed to count files in {}", srcPath, e);
            }
        }
        return count;
    }

    /**
     * Find all packages in the project.
     * Supports multi-module projects.
     */
    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                stream.filter(Files::isDirectory)
                      .filter(this::containsJavaFiles)
                      .map(p -> srcPath.relativize(p).toString())
                      .map(s -> s.replace(File.separator, "."))
                      .filter(s -> !s.isEmpty())
                      .filter(s -> !packages.contains(s))  // Avoid duplicates
                      .forEach(packages::add);
            } catch (IOException e) {
                log.warn("Failed to find packages in {}", srcPath, e);
            }
        }

        return packages;
    }

    private boolean containsJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }
}
