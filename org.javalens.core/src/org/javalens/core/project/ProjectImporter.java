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
import org.javalens.core.project.model.BuildInputs;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Orchestrator for importing external Java projects into the Eclipse workspace and
 * configuring their JDT classpath, compiler options, and annotation-processor wiring.
 *
 * <p>A load resolves the project's {@link BuildModel} once, through {@link #resolveModel},
 * and derives everything from it: source file count, packages, linked source folders,
 * classpath, compiler level and APT.
 *
 * <p>The work is split across collaborator classes, each owning one concern:
 * <ul>
 *   <li>{@link BuildSystem} — enum naming the supported build systems.</li>
 *   <li>{@link BuildSystemImporter}: per-build-system contract that resolves the
 *       {@link BuildModel}. Implemented by {@link MavenImporter}, {@link GradleImporter},
 *       {@link BazelImporter}; lookup-dispatched here via {@link #importerFor}.</li>
 *   <li>{@link LinkedFolderConfigurator} — Eclipse linked-folder creation and
 *       layout-specific source-path probing (build-system-agnostic).</li>
 * </ul>
 *
 * <p>Linked folders keep all Eclipse metadata ({@code .project}, {@code .classpath})
 * inside the workspace so the user's actual project directory is never polluted.
 *
 * <p>Adding support for a new build system: implement {@link BuildSystemImporter},
 * register an instance in the default constructor's importer map, and extend
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

    /**
     * Dispatch table: detected {@link BuildSystem} → its {@link BuildSystemImporter}.
     * {@link BuildSystem#UNKNOWN} falls back to {@link BuildSystemImporter#NONE} via
     * {@link #importerFor}. Adding a new build system: add one entry in the default
     * constructor, extend {@link #detectBuildSystem}.
     */
    private final Map<BuildSystem, BuildSystemImporter> importers;

    public ProjectImporter() {
        this(Map.of(
            BuildSystem.MAVEN, new MavenImporter(),
            BuildSystem.GRADLE, new GradleImporter(),
            BuildSystem.BAZEL, new BazelImporter()));
    }

    /** Test seam: dispatch to {@code importers}; unmapped build systems get {@link BuildSystemImporter#NONE}. */
    ProjectImporter(Map<BuildSystem, BuildSystemImporter> importers) {
        this.importers = Map.copyOf(importers);
    }

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
     * Resolve the build model of the project at {@code projectPath}: detect its build
     * system and ask that system's importer once. A load passes the result to
     * {@link #countSourceFiles(BuildModel)}, {@link #findPackages(BuildModel)} and
     * {@link #configureJavaProject(IProject, java.nio.file.Path, BuildModel,
     * org.javalens.core.workspace.WorkspaceManager)}.
     *
     * <p>Never throws. When the importer fails, the result is a {@link Provenance#CONVENTION}
     * model of one module at the project root, carrying a
     * {@link LoadWarning#MODULE_LAYOUT_ASSUMED}, so the load still completes.
     */
    public BuildModel resolveModel(java.nio.file.Path projectPath) {
        BuildSystem buildSystem = detectBuildSystem(projectPath);
        try {
            return importerFor(buildSystem).resolve(projectPath);
        } catch (RuntimeException e) {
            log.warn("Resolving the {} build model of {} failed; assuming conventional layouts at the project root",
                buildSystem, projectPath, e);
            return assumedLayoutModel(buildSystem, projectPath, e);
        }
    }

    /**
     * The model of a project whose build model could not be resolved: one module, ".", at
     * the root, whose source roots and output directories come from probing the root
     * directory alone for conventional, generated-source and compiled-output layouts, and
     * one {@link LoadWarning#MODULE_LAYOUT_ASSUMED} naming {@code failure}. Module
     * discovery, which may be what failed, is not run again.
     */
    private BuildModel assumedLayoutModel(BuildSystem buildSystem, java.nio.file.Path projectRoot,
            RuntimeException failure) {
        List<java.nio.file.Path> sourceRoots = new ArrayList<>();
        linkedFolderConfigurator.addConventionalSourcePaths(projectRoot, sourceRoots);
        List<java.nio.file.Path> generatedRoots = new ArrayList<>();
        linkedFolderConfigurator.addGeneratedSourcePaths(projectRoot, generatedRoots);
        List<java.nio.file.Path> outputDirs = new ArrayList<>();
        linkedFolderConfigurator.addConventionalOutputDirs(projectRoot, outputDirs);

        LoadWarning warning = new LoadWarning(
            LoadWarning.MODULE_LAYOUT_ASSUMED,
            "Resolving the " + buildSystem.name() + " build model failed ("
                + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                + "); source roots were assumed from conventional layouts at the project root",
            "Check the project's build files, then reload the project.",
            ".");
        return new BuildModel(buildSystem, Provenance.CONVENTION,
            List.of(new BuildModule(".", projectRoot, sourceRoots, generatedRoots, outputDirs, null, false)),
            List.of(), List.of(), null, new BuildInputs(List.of(), List.of()), List.of(warning));
    }

    /**
     * Resolve the project's build model and configure the project from it. A load that
     * already holds the model calls
     * {@link #configureJavaProject(IProject, java.nio.file.Path, BuildModel,
     * org.javalens.core.workspace.WorkspaceManager)} instead.
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.javalens.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        return configureJavaProject(project, projectPath, resolveModel(projectPath), workspaceManager);
    }

    /**
     * Configure an IProject as a Java project with proper classpath.
     * Creates linked folders for source directories to keep Eclipse metadata
     * in the workspace, not polluting the user's project directory.
     *
     * @param project The workspace project (must be created and open)
     * @param projectPath The filesystem path to the external project
     * @param model The project's resolved build model; the only source of source
     *              folders, classpath, compiler level and processor jars
     * @param workspaceManager WorkspaceManager for creating linked folders
     * @return Configured IJavaProject
     * @throws CoreException if configuration fails
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            BuildModel model, org.javalens.core.workspace.WorkspaceManager workspaceManager)
            throws CoreException {
        // Reset accumulated state from any previous load.
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

        // The importer's warnings follow the JRE check and precede the compiled-output,
        // compliance and APT warnings added below.
        warnings.addAll(model.warnings());

        // 2. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 3. Create linked folders and add source entries, one per distinct model source
        // root in model order. Linked-folder names carry the index, so the order keeps them
        // stable.
        linkedFolderConfigurator.addLinkedSourceFolders(
            entries, project, projectPath, sourceRoots(model), workspaceManager,
            model.modules().size() > 1);

        // 4. Add dependency JARs from the model, then the compiled output it keeps
        addDependencyEntries(entries, projectPath, model);

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
        applyCompilerOptions(javaProject, model);

        // Bug H fix: enable annotation processing and register the processor jars declared
        // by the project. Without this, code that references annotation-processor-generated
        // members (Lombok @Data getters, MapStruct mappers, JPA metamodels) shows those
        // members as unresolved during analysis.
        applyAnnotationProcessing(javaProject, model);

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Enable JDT's APT framework on the project and register the model's
     * annotation-processor jars on its factory path via {@link #wireApt}.
     *
     * <p>Note: an earlier draft also fired {@code GENERATED_SOURCES_NOT_FOUND} when
     * processors were declared but no generated-source directory existed. That produced
     * false positives for Lombok, which modifies AST in-place rather than emitting .java —
     * a Lombok-only project always has the processor declared and never has
     * {@code target/generated-sources/}. Without inspecting each processor's behavior we
     * cannot reliably distinguish "build hasn't run" from "this processor doesn't emit",
     * so the warning was removed.
     */
    private void applyAnnotationProcessing(IJavaProject javaProject, BuildModel model) {
        java.util.Set<java.nio.file.Path> processorJars = new java.util.LinkedHashSet<>(model.processorJars());
        if (processorJars.isEmpty()) return;
        wireApt(javaProject, processorJars);
    }

    /**
     * Register the given jars on the project's JDT APT factory path and enable processing.
     * Failures are logged but don't propagate — APT not wiring up is a degraded mode, not
     * a fatal load error. When every candidate jar fails the regular-file probe (e.g. a
     * stale path no longer present on disk), a {@link LoadWarning#APT_PROCESSOR_JARS_MISSING}
     * is recorded so the {@code load_project} response signals the silent-degradation
     * state ("APT enabled, factory path empty") to MCP clients.
     */
    private void wireApt(IJavaProject javaProject, java.util.Set<java.nio.file.Path> processorJars) {
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
     * Apply the model's Java analysis level to the {@link IJavaProject}. Sets
     * {@code COMPILER_SOURCE}, {@code COMPILER_COMPLIANCE}, and
     * {@code COMPILER_CODEGEN_TARGET_PLATFORM} so the JDT compiler parses and validates
     * code at the same level the build system uses.
     */
    private void applyCompilerOptions(IJavaProject javaProject, BuildModel model) {
        String level = model.analysisLevel();
        BuildSystem buildSystem = model.buildSystem();
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
     * Every source root of the model in link order: per module, its source roots, then
     * its generated source roots. A root that appears more than once, in any spelling of
     * its path, is kept at its first position only, so it is linked, counted and listed
     * once.
     */
    private static List<java.nio.file.Path> sourceRoots(BuildModel model) {
        Set<java.nio.file.Path> seen = new HashSet<>();
        List<java.nio.file.Path> roots = new ArrayList<>();
        for (BuildModule module : model.modules()) {
            List<java.nio.file.Path> moduleRoots = new ArrayList<>(module.sourceRoots());
            moduleRoots.addAll(module.generatedSourceRoots());
            for (java.nio.file.Path root : moduleRoots) {
                if (seen.add(identity(root))) {
                    roots.add(root);
                }
            }
        }
        return roots;
    }

    /** The normalized absolute form of {@code path}, which identifies it however it is spelled. */
    private static java.nio.file.Path identity(java.nio.file.Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Add the model's existing classpath entries in model order, then the compiled output
     * that {@link #addCompiledOutputEntries} keeps. Every library is added once: an entry
     * that repeats an earlier one, in any spelling of its path, is skipped, since JDT
     * rejects a classpath holding the same library twice.
     */
    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
            BuildModel model) {
        Set<java.nio.file.Path> libraries = new HashSet<>();
        for (String jar : model.classpath()) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath) && libraries.add(identity(jarPath))) {
                IPath eclipsePath = new Path(jar);
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }

        log.info("Added {} dependency entries from {}", libraries.size(), model.buildSystem());

        addCompiledOutputEntries(entries, projectPath, model, libraries);
    }

    /**
     * The compiled-output rule: each existing output of each module, in module order, goes
     * on the classpath only when {@link CompiledOutput#sourcelessClassCount} finds classes
     * in it with no source in any of the model's source roots, or cannot read it, and is
     * then named by a {@link LoadWarning#COMPILED_OUTPUT_INCLUDED}. Output whose every class
     * has a linked source would only add compiled duplicates of linked types, so it stays
     * off. A declared output that does not exist was not built and is skipped. An output
     * already in {@code libraries} (the model's classpath holds it, or an earlier module
     * declares it) is not added again and gets no warning.
     *
     * @param libraries identities of the library entries added so far; extended here
     */
    private void addCompiledOutputEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
            BuildModel model, Set<java.nio.file.Path> libraries) {
        List<java.nio.file.Path> sourceRoots = sourceRoots(model);
        for (BuildModule module : model.modules()) {
            for (java.nio.file.Path output : module.outputDirs()) {
                if (!Files.exists(output)) {
                    continue;
                }
                if (libraries.contains(identity(output))) {
                    log.info("Compiled output {} of module '{}' is already on the classpath", output, module.id());
                    continue;
                }
                OptionalInt sourceless = CompiledOutput.sourcelessClassCount(output, sourceRoots);
                if (sourceless.isPresent() && sourceless.getAsInt() == 0) {
                    log.info("Compiled output {} of module '{}' holds only classes with linked sources; "
                        + "leaving it off the classpath", output, module.id());
                    continue;
                }
                entries.add(JavaCore.newLibraryEntry(new Path(output.toString()), null, null));
                libraries.add(identity(output));
                String content = sourceless.isPresent()
                    ? "holds " + sourceless.getAsInt() + (sourceless.getAsInt() == 1 ? " class" : " classes")
                        + " with no linked source"
                    : "could not be read";
                warnings.add(new LoadWarning(
                    LoadWarning.COMPILED_OUTPUT_INCLUDED,
                    "Compiled output " + ProjectPaths.relativeToRoot(projectPath, output) + " " + content
                        + ", so it is on the classpath; results for module '" + module.id() + "' may include "
                        + "compiled duplicates of linked sources and reflect the last build",
                    "Rebuild the project after editing its sources so its compiled classes stay current.",
                    module.id()));
            }
        }
    }

    /**
     * Resolve the project's build model and count the Java source files under its
     * source roots. A load that already holds the model calls
     * {@link #countSourceFiles(BuildModel)} instead.
     */
    public int countSourceFiles(java.nio.file.Path projectPath) {
        return countSourceFiles(resolveModel(projectPath));
    }

    /**
     * Count the Java source files under the model's source roots, generated roots included.
     */
    public int countSourceFiles(BuildModel model) {
        int count = 0;
        for (java.nio.file.Path srcPath : sourceRoots(model)) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException e) {
                log.warn("Failed to count files in {}", srcPath, e);
            }
        }
        return count;
    }

    /**
     * Resolve the project's build model and list the packages under its source roots.
     * A load that already holds the model calls {@link #findPackages(BuildModel)} instead.
     */
    public List<String> findPackages(java.nio.file.Path projectPath) {
        return findPackages(resolveModel(projectPath));
    }

    /**
     * List the packages that hold Java files under the model's source roots, in
     * first-seen order without duplicates.
     */
    public List<String> findPackages(BuildModel model) {
        List<String> packages = new ArrayList<>();
        for (java.nio.file.Path srcPath : sourceRoots(model)) {
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
