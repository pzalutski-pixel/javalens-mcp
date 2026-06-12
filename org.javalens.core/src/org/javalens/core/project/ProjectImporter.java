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
 * Orchestrator for importing external Java projects into the Eclipse workspace and
 * configuring their JDT classpath, compiler options, and annotation-processor wiring.
 *
 * <p>The work is split across collaborator classes, each owning one concern:
 * <ul>
 *   <li>{@link BuildSystem} — enum naming the supported build systems.</li>
 *   <li>{@link BuildSystemImporter} — per-build-system contract for classpath /
 *       compiler-level / annotation-processor extraction. Implemented by
 *       {@link MavenImporter}, {@link GradleImporter}, {@link BazelImporter};
 *       lookup-dispatched here via {@link #importerFor}.</li>
 *   <li>{@link LinkedFolderConfigurator} — Eclipse linked-folder creation and
 *       layout-specific source-path probing (build-system-agnostic).</li>
 * </ul>
 *
 * <p>Linked folders keep all Eclipse metadata ({@code .project}, {@code .classpath})
 * inside the workspace so the user's actual project directory is never polluted.
 *
 * <p>Adding support for a new build system: implement {@link BuildSystemImporter},
 * instantiate it as a field, register it in {@link #importers}, and extend
 * {@link #detectBuildSystem} to recognize the build file marker. The four orchestrator
 * methods ({@link #configureJavaProject}, {@link #applyAnnotationProcessing},
 * {@link #applyCompilerOptions}, {@link #addDependencyEntries}) require no edits.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    /**
     * Warnings accumulated during the most recent {@link #configureJavaProject} call.
     * Reset at the start of each invocation. {@link JdtServiceImpl#getWarnings()} reads
     * this list to surface degraded-load scenarios in the {@code load_project} response.
     */
    private final List<LoadWarning> warnings = new ArrayList<>();

    private final LinkedFolderConfigurator linkedFolderConfigurator = new LinkedFolderConfigurator();
    private final BazelImporter bazelImporter = new BazelImporter();
    private final GradleImporter gradleImporter = new GradleImporter();
    private final MavenImporter mavenImporter = new MavenImporter();

    /**
     * Dispatch table: detected {@link BuildSystem} → its {@link BuildSystemImporter}.
     * {@link BuildSystem#UNKNOWN} falls back to {@link BuildSystemImporter#NONE} via
     * {@link #importerFor}. Adding a new build system: instantiate an importer field
     * above, add one entry here, extend {@link #detectBuildSystem}.
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

        // 1. Ensure an IVMInstall for the running JVM is registered and set as the JDT
        // default, so the bare JRE_CONTAINER path below resolves to JDK system modules.
        // JDT's auto-detection (JavaRuntime.detectEclipseRuntime → java.home) covers this
        // in most environments but not all — issue #18 surfaces npm-launched runtimes
        // where the fallback doesn't fire, leaving the JRE container unbacked and
        // every source file producing BUILDPATH cascades. Registering explicitly here
        // makes the JRE presence independent of the fallback's success.
        if (org.javalens.core.project.JreInstallEnsurer.ensureRunningJvmRegistered() == null) {
            warnings.add(new LoadWarning(
                LoadWarning.JRE_REGISTRATION_FAILED,
                "Could not register the running JVM as the project's JRE — java.home was "
                    + "unset or pointed at a non-existent directory.",
                "Verify JAVA_HOME and the JVM used to launch JavaLens are set correctly. "
                    + "Without a registered JRE every source file referencing java.lang.* "
                    + "will report BUILDPATH errors."));
        }

        // 2. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 3. Create linked folders and add source entries. Source-path aggregation is
        // build-system-aware (Maven modules, Gradle subprojects, Bazel packages); the
        // per-directory layout probing and linked-folder creation live in
        // LinkedFolderConfigurator.
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        linkedFolderConfigurator.addLinkedSourceFolders(
            entries, project, projectPath, sourcePaths, workspaceManager,
            mavenImporter.isMultiModule(projectPath));

        // 4. Add dependency JARs from build system
        addDependencyEntries(entries, projectPath);

        // 5. Add output location
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
     * its factory path. Splits into {@link #collectProcessorJars} (gathering) and
     * {@link #wireApt} (registration), each with a single concern.
     */
    private void applyAnnotationProcessing(IJavaProject javaProject, java.nio.file.Path projectPath, BuildSystem buildSystem) {
        java.util.Set<java.nio.file.Path> processorJars = collectProcessorJars(projectPath, buildSystem);
        if (processorJars.isEmpty()) return;
        wireApt(javaProject, processorJars, projectPath);
    }

    /**
     * Union of annotation processors from two complementary sources:
     * <ol>
     *   <li><b>Build-system-specific declarations</b> — {@code <annotationProcessorPaths>}
     *       (Maven), the {@code annotationProcessor} configuration (Gradle).</li>
     *   <li><b>Generic classpath scan</b> — any jar on the resolved classpath that contains
     *       {@code META-INF/services/javax.annotation.processing.Processor}. Catches Bazel
     *       projects (processors come from {@code java_plugin} rules we don't introspect)
     *       and any system that drops a processor jar on the compile classpath without a
     *       separate processor-path declaration. Maven/Gradle inherit the empty default for
     *       {@link BuildSystemImporter#getResolvedClasspathJars} since their declarations
     *       are already harvested above.
     * </ol>
     *
     * <p>Note: an earlier draft also fired {@code GENERATED_SOURCES_NOT_FOUND} when
     * processors were declared but no generated-source directory existed. That produced
     * false positives for Lombok, which modifies AST in-place rather than emitting .java —
     * a Lombok-only project always has the processor declared and never has
     * {@code target/generated-sources/}. Without inspecting each processor's behavior we
     * cannot reliably distinguish "build hasn't run" from "this processor doesn't emit",
     * so the warning was removed.
     */
    private java.util.Set<java.nio.file.Path> collectProcessorJars(java.nio.file.Path projectPath, BuildSystem buildSystem) {
        BuildSystemImporter importer = importerFor(buildSystem);
        java.util.LinkedHashSet<java.nio.file.Path> processorJars = new java.util.LinkedHashSet<>();
        processorJars.addAll(importer.detectAnnotationProcessors(projectPath));
        for (java.nio.file.Path jar : importer.getResolvedClasspathJars(projectPath, warnings)) {
            if (jarDeclaresAnnotationProcessor(jar)) {
                processorJars.add(jar);
            }
        }
        return processorJars;
    }

    /**
     * Register the given jars on the project's JDT APT factory path and enable processing.
     * Failures are logged but don't propagate — APT not wiring up is a degraded mode, not
     * a fatal load error. When every candidate jar fails the regular-file probe (e.g. a
     * stale path no longer present on disk), a {@link LoadWarning#APT_PROCESSOR_JARS_MISSING}
     * is recorded so the {@code load_project} response signals the silent-degradation
     * state ("APT enabled, factory path empty") to MCP clients.
     */
    private void wireApt(IJavaProject javaProject, java.util.Set<java.nio.file.Path> processorJars,
            java.nio.file.Path projectPath) {
        try {
            AptConfig.setEnabled(javaProject, true);
            IFactoryPath factoryPath = AptConfig.getDefaultFactoryPath(javaProject);
            int registered = 0;
            List<java.nio.file.Path> missing = new ArrayList<>();
            for (java.nio.file.Path jar : processorJars) {
                if (Files.isRegularFile(jar)) {
                    factoryPath.addExternalJar(jar.toFile());
                    registered++;
                } else {
                    missing.add(jar);
                }
            }
            AptConfig.setFactoryPath(javaProject, factoryPath);
            log.info("Enabled APT with {} processor jar(s)", registered);
            if (registered == 0 && !processorJars.isEmpty()) {
                warnings.add(new LoadWarning(
                    LoadWarning.APT_PROCESSOR_JARS_MISSING,
                    "Annotation processors were declared but none of the " + processorJars.size()
                        + " candidate jar(s) were found on disk; APT is enabled with an empty "
                        + "factory path. Missing: " + missing,
                    "Run your build's dependency-resolution step (e.g. 'mvn dependency:resolve', "
                        + "'gradle resolveDependencies') so the declared processor jars are present "
                        + "in the local cache, then reload the project."));
            }
        } catch (CoreException e) {
            log.warn("Failed to configure APT: {}", e.getMessage());
        }
    }

    /**
     * Returns true iff the jar declares at least one annotation processor via the standard
     * SPI descriptor at {@code META-INF/services/javax.annotation.processing.Processor}.
     * A corrupted / truncated / unreadable jar logs a warning and returns false; this
     * matches "no processor declared" externally but leaves a diagnostic trail so the
     * silent-no-op case is investigable.
     */
    private boolean jarDeclaresAnnotationProcessor(java.nio.file.Path jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/services/javax.annotation.processing.Processor") != null;
        } catch (IOException e) {
            log.warn("Could not read jar {} while scanning for annotation processors: {}",
                jar, e.getMessage());
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
        // JDT only supports compliance 1.8 through its latest version. Legacy
        // enterprise builds still declare 1.5/1.6/1.7 - applied verbatim, an
        // unsupported value silently DISABLES reconcile problem detection:
        // get_diagnostics reports a clean project regardless of real errors
        // (issue #30). Clamp into the supported range and say so.
        String clamped = clampToSupportedRange(level);
        if (!clamped.equals(level)) {
            warnings.add(new LoadWarning(
                LoadWarning.COMPLIANCE_LEVEL_UNSUPPORTED,
                "Declared Java source level " + level + " is outside JDT's supported range; "
                    + "analyzing at " + clamped + " instead",
                "JDT supports compliance 1.8 through " + JavaCore.latestSupportedJavaVersion()
                    + ". Diagnostics reflect level " + clamped + "; code valid only under "
                    + "the older level may show additional errors."));
            level = clamped;
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
     * Clamp a declared compiler level into JDT's supported range: floor 1.8
     * (older levels were removed from the compiler), ceiling
     * {@link JavaCore#latestSupportedJavaVersion()}. Unparseable values fall
     * back to the floor - a wrong-but-working level beats silent no-analysis.
     */
    static String clampToSupportedRange(String level) {
        int feature;
        try {
            String normalized = level.trim();
            if (normalized.startsWith("1.")) {
                normalized = normalized.substring(2);
            }
            feature = (int) Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return "1.8";
        }
        if (feature < 8) {
            return "1.8";
        }
        int latest = Integer.parseInt(JavaCore.latestSupportedJavaVersion());
        if (feature > latest) {
            return JavaCore.latestSupportedJavaVersion();
        }
        return level;
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
