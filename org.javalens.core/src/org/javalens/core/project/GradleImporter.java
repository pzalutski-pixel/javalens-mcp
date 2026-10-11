package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Gradle {@link BuildSystemImporter}: the project model is the one Gradle reports.
 *
 * <p>{@link #resolve} runs Gradle once, through {@link BuildToolRunner}, with a generated
 * init script and the {@value #MODEL_TASK} task the script registers. The script, the files
 * it writes and Gradle's project cache all live in one scratch directory created for the
 * run and deleted once the model is read, so nothing is written into the project tree.
 * {@link GradleModelReader} builds the model from those files, with provenance
 * {@link Provenance#BUILD_TOOL}.
 *
 * <p>When the run does not produce the model, the model is the structure read statically
 * from {@code settings.gradle} as code ({@link #getSubprojects}) and laid out by convention, with
 * no classpath, processors or level, provenance {@link Provenance#STATIC_READ}, and one
 * build-tool warning saying why. Gradle is not run again.
 */
public class GradleImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(GradleImporter.class);

    /**
     * The task the init script registers in every build's root project. It depends on every
     * project's model task and on each included build's {@value}, so running it in the root
     * build writes the whole composite's model.
     */
    private static final String MODEL_TASK = "javalensModel";

    /** Replaced in {@link #INIT_SCRIPT} by the URL-encoded directory the script writes into. */
    private static final String MODEL_DIR_PLACEHOLDER = "@MODEL_DIR@";

    /** The prefix of the scratch directory each run creates in the system temporary directory. */
    private static final String SCRATCH_PREFIX = "javalens-gradle-model-";

    /**
     * Init script that reports the project model. Every build of the invocation applies it,
     * the root build and each included build. When a build's projects are loaded, before any
     * build script is evaluated, it writes the build's structure; each project's
     * {@code javalensProjectModel} task writes the project's details. {@link GradleModelReader}
     * documents the files and their records.
     */
    private static final String INIT_SCRIPT = """
        import org.gradle.api.artifacts.component.ProjectComponentIdentifier
        import org.gradle.api.file.SourceDirectorySet

        def javalensOut = new File(URLDecoder.decode('@MODEL_DIR@', 'UTF-8'))

        // One record per line: its fields URL-encoded, separated by single spaces.
        def javalensWrite = { String kind, List records ->
            new File(javalensOut, kind + '-' + UUID.randomUUID() + '.txt').withWriter('UTF-8') { writer ->
                records.each { record ->
                    writer.write(record.collect { URLEncoder.encode(String.valueOf(it), 'UTF-8') }.join(' '))
                    writer.newLine()
                }
            }
        }

        def javalensSettingsFile = null
        gradle.settingsEvaluated { settings ->
            javalensSettingsFile = settings.buildscript.sourceFile
        }

        gradle.projectsLoaded { g ->
            def structure = [['build', g.rootProject.projectDir.canonicalPath, g.parent == null]]
            if (javalensSettingsFile != null) {
                structure << ['settings', javalensSettingsFile.canonicalPath]
            }
            g.rootProject.allprojects.each { p ->
                structure << ['project', p.path, p.projectDir.canonicalPath, p.buildFile.canonicalPath]
            }
            g.includedBuilds.each { b ->
                structure << ['included', b.name, b.projectDir.canonicalPath]
            }
            javalensWrite('build', structure)

            g.rootProject.allprojects { p ->
                p.tasks.register('javalensProjectModel') { task ->
                    task.doLast {
                        def details = [['project', p.rootDir.canonicalPath, p.path]]
                        if (p.plugins.hasPlugin('java-base')) {
                            details << ['level', p.java.sourceCompatibility]
                            p.sourceSets.each { sourceSet ->
                                sourceSet.java.srcDirs.each { details << ['source', it.canonicalPath] }
                                def kotlin = sourceSet.extensions.findByName('kotlin')
                                if (kotlin instanceof SourceDirectorySet) {
                                    kotlin.srcDirs.each { details << ['source', it.canonicalPath] }
                                }
                                sourceSet.output.generatedSourcesDirs.files.each { details << ['generated', it.canonicalPath] }
                                sourceSet.output.classesDirs.files.each { details << ['classes', it.canonicalPath] }
                            }
                            p.configurations.findByName('annotationProcessor')?.resolve()?.each {
                                details << ['processor', it.absolutePath]
                            }
                            // External artifacts only: a project dependency, on this build or an
                            // included one, is a project whose sources are linked.
                            ['compileClasspath', 'testCompileClasspath', 'runtimeClasspath', 'testRuntimeClasspath'].each { name ->
                                def configuration = p.configurations.findByName(name)
                                if (configuration != null && configuration.canBeResolved) {
                                    configuration.incoming.artifactView { view ->
                                        view.componentFilter { id -> !(id instanceof ProjectComponentIdentifier) }
                                    }.files.each { details << ['classpath', it.absolutePath] }
                                }
                            }
                        }
                        javalensWrite('project', details)
                    }
                }
            }

            g.rootProject.tasks.register('javalensModel') { task ->
                task.dependsOn(g.rootProject.allprojects.collect { it.absoluteProjectPath('javalensProjectModel') })
                task.dependsOn(g.includedBuilds.collect { it.task(':javalensModel') })
            }
        }
        """;

    /**
     * How long the Gradle run may take before it is stopped. The Gradle Wrapper sometimes
     * downloads a distribution on first run, hence a generous limit; later runs hit the
     * cache.
     */
    private static final Duration GRADLE_TIMEOUT = Duration.ofMinutes(10);

    private final Duration timeout;

    public GradleImporter() {
        this(GRADLE_TIMEOUT);
    }

    /** Test seam: stop the Gradle run after {@code timeout}. */
    GradleImporter(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * The model Gradle reports, or, when the Gradle run does not produce it, the structure
     * read statically from {@code settings.gradle} followed by one build-tool warning.
     */
    @Override
    public BuildModel resolve(Path projectRoot) {
        List<LoadWarning> failure = new ArrayList<>();
        Optional<BuildModel> reported = reportedModel(projectRoot, failure);
        return reported.isPresent() ? reported.get() : staticModel(projectRoot, failure);
    }

    /**
     * Read {@code settings.gradle} (or {@code settings.gradle.kts}) as code, through
     * {@link BuildLexer}, for {@code include} statements and resolve each to a subproject
     * directory. Comments and strings declare nothing.
     *
     * <p>Recognizes {@code include 'a'}, {@code include "a"}, {@code include 'a', 'b'} and
     * {@code include('a')}, with string-literal arguments. Colon-prefixed paths
     * (e.g. {@code include ':app:core'}) are normalized: leading colons stripped, internal
     * colons converted to file separators. A literal
     * {@code project(':a').projectDir = file('dir')} places that project at {@code dir},
     * relative to the settings file's directory, wherever it appears in the script.
     *
     * <p>An include that is not a valid path, or whose directory does not exist, is
     * skipped and appended to {@code warnings} as a {@link LoadWarning#MODULE_NOT_LOADED}.
     */
    public List<Path> getSubprojects(Path projectPath, List<LoadWarning> warnings) {
        Path settings = projectPath.resolve("settings.gradle");
        boolean kotlin = false;
        if (!Files.exists(settings)) {
            settings = projectPath.resolve("settings.gradle.kts");
            kotlin = true;
        }
        if (!Files.exists(settings)) return List.of();

        String content;
        try {
            content = Files.readString(settings);
        } catch (IOException e) {
            log.debug("Failed to read settings.gradle for subprojects: {}", e.getMessage());
            return List.of();
        }
        List<BuildLexer.Token> tokens = kotlin ? BuildLexer.kotlin(content) : BuildLexer.groovy(content);
        Map<String, String> projectDirs = projectDirs(tokens);

        List<Path> subprojects = new ArrayList<>();
        for (String declared : includes(tokens)) {
            String projectDir = projectDirs.get(projectPath(declared));
            Optional<Path> dir = projectDir == null
                ? DeclaredPaths.gradleProjectDir(projectPath, settings, declared, warnings)
                : DeclaredPaths.gradleProjectDir(projectPath, settings, declared, projectDir, warnings);
            dir.ifPresent(subprojects::add);
        }
        return subprojects;
    }

    /**
     * The non-empty string arguments of every {@code include} call, in order. An
     * {@code include} that is a member access ({@code x.include}) is not the settings
     * method.
     */
    private static List<String> includes(List<BuildLexer.Token> tokens) {
        List<String> declared = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isIdentifier("include") || (i > 0 && tokens.get(i - 1).isSymbol('.'))) {
                continue;
            }
            int j = i + 1;
            if (j < tokens.size() && tokens.get(j).isSymbol('(')) j++;
            while (j < tokens.size() && tokens.get(j).kind() == BuildLexer.Kind.STRING) {
                String name = tokens.get(j).text().trim();
                if (!name.isEmpty()) declared.add(name);
                j++;
                if (j < tokens.size() && tokens.get(j).isSymbol(',')) {
                    j++;
                } else {
                    break;
                }
            }
        }
        return declared;
    }

    /**
     * The directory of each literal {@code project('<path>').projectDir = file('<dir>')}
     * assignment, keyed by the project's path without leading colons. A later assignment
     * to the same project wins, as it does when Gradle runs the script.
     */
    private static Map<String, String> projectDirs(List<BuildLexer.Token> tokens) {
        Map<String, String> dirs = new HashMap<>();
        for (int i = 0; i + 10 < tokens.size(); i++) {
            if (tokens.get(i).isIdentifier("project")
                    && tokens.get(i + 1).isSymbol('(')
                    && tokens.get(i + 2).kind() == BuildLexer.Kind.STRING
                    && tokens.get(i + 3).isSymbol(')')
                    && tokens.get(i + 4).isSymbol('.')
                    && tokens.get(i + 5).isIdentifier("projectDir")
                    && tokens.get(i + 6).isSymbol('=')
                    && tokens.get(i + 7).isIdentifier("file")
                    && tokens.get(i + 8).isSymbol('(')
                    && tokens.get(i + 9).kind() == BuildLexer.Kind.STRING
                    && tokens.get(i + 10).isSymbol(')')) {
                dirs.put(projectPath(tokens.get(i + 2).text().trim()), tokens.get(i + 9).text());
            }
        }
        return dirs;
    }

    /** A Gradle project path without its leading colons: {@code :app:core} is {@code app:core}. */
    private static String projectPath(String declared) {
        return declared.replaceAll("^:+", "");
    }

    /**
     * Run Gradle in a scratch directory of its own and read the model it reports, deleting
     * the scratch directory afterwards. Empty, with the reason appended to
     * {@code warnings}, when the run does not produce the model.
     */
    private Optional<BuildModel> reportedModel(Path projectRoot, List<LoadWarning> warnings) {
        Path scratch;
        try {
            scratch = Files.createTempDirectory(SCRATCH_PREFIX);
        } catch (IOException e) {
            warnings.add(preparationFailure(e));
            return Optional.empty();
        }
        try {
            return runGradle(projectRoot, scratch, warnings);
        } finally {
            deleteScratch(scratch);
        }
    }

    /**
     * Write the init script into {@code scratch}, run Gradle with it, its model directory
     * and its project cache in {@code scratch}, and read the model it wrote.
     */
    private Optional<BuildModel> runGradle(Path projectRoot, Path scratch, List<LoadWarning> warnings) {
        Path modelDir = scratch.resolve("model");
        Path initScript = scratch.resolve("javalens-model.gradle");
        try {
            Files.createDirectory(modelDir);
            Files.writeString(initScript, INIT_SCRIPT.replace(MODEL_DIR_PLACEHOLDER,
                URLEncoder.encode(modelDir.toString(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            warnings.add(preparationFailure(e));
            return Optional.empty();
        }

        String gradleBinary = resolveGradleBinary(projectRoot);
        List<String> command = List.of(
            gradleBinary,
            "--init-script", initScript.toString(),
            MODEL_TASK,
            "-q",
            "--console=plain",
            "--no-configuration-cache",
            // Gradle keeps its per-build state here instead of in each build's .gradle
            // directory, so the run writes nothing into the project tree.
            "--project-cache-dir", scratch.resolve("project-cache").toString());

        log.info("Running Gradle to read the project model of {}", projectRoot);
        BuildToolRunner.Result result = BuildToolRunner.run(command, projectRoot, timeout);
        Optional<LoadWarning> failure = runFailure(result, gradleBinary, projectRoot);
        if (failure.isPresent()) {
            warnings.add(failure.get());
            return Optional.empty();
        }

        try {
            BuildModel model = GradleModelReader.read(projectRoot, modelDir);
            log.info("Gradle reported {} projects, {} classpath entries, {} processor jars, level {}",
                model.modules().size(), model.classpath().size(), model.processorJars().size(),
                model.analysisLevel());
            return Optional.of(model);
        } catch (IOException e) {
            log.warn("Gradle's project model could not be read", e);
            warnings.add(new LoadWarning(
                LoadWarning.BUILD_TOOL_FAILED,
                "Gradle exited with code 0, but the project model could not be read: " + e.getMessage()
                    + lastOutput(result),
                "Run './gradlew help' manually in " + projectRoot + " to see the full error."));
            return Optional.empty();
        }
    }

    /**
     * The warning for a run that did not exit 0: one that could not start, ran past
     * {@link #timeout}, was interrupted, or exited non-zero. Empty for a run that exited 0.
     */
    private Optional<LoadWarning> runFailure(BuildToolRunner.Result result, String gradleBinary, Path projectRoot) {
        return switch (result.outcome()) {
            case UNAVAILABLE -> {
                log.warn("Cannot start Gradle: {}", result.startFailure());
                yield Optional.of(new LoadWarning(
                    LoadWarning.BUILD_TOOL_UNAVAILABLE,
                    "Could not start '" + gradleBinary + "': " + result.startFailure(),
                    "Verify the Gradle Wrapper is intact, or install Gradle and ensure 'gradle' is on PATH."));
            }
            case TIMED_OUT -> {
                log.warn("Gradle did not finish within {}", timeout);
                yield Optional.of(new LoadWarning(
                    LoadWarning.BUILD_TOOL_TIMEOUT,
                    "Gradle did not finish building the project model within " + describe(timeout),
                    "Check that the project's build.gradle resolves correctly. Try running './gradlew help' manually in "
                        + projectRoot));
            }
            case INTERRUPTED -> {
                log.warn("Gradle was interrupted");
                yield Optional.of(new LoadWarning(
                    LoadWarning.BUILD_TOOL_FAILED,
                    "Gradle was interrupted before it finished building the project model",
                    "Reload the project to run the build tool again."));
            }
            case EXITED -> {
                if (result.exitCode() == 0) {
                    yield Optional.empty();
                }
                log.warn("Gradle exited with code {}", result.exitCode());
                yield Optional.of(new LoadWarning(
                    LoadWarning.BUILD_TOOL_FAILED,
                    "Gradle exited with code " + result.exitCode() + " while building the project model"
                        + lastOutput(result),
                    "Run './gradlew help' manually in " + projectRoot + " to see the full error."));
            }
        };
    }

    /** The warning for a scratch directory or init script that could not be written. */
    private static LoadWarning preparationFailure(IOException e) {
        log.warn("Could not prepare the Gradle run", e);
        return new LoadWarning(
            LoadWarning.BUILD_TOOL_FAILED,
            "Could not prepare the Gradle run that reads the project model: "
                + e.getClass().getSimpleName() + ": " + e.getMessage(),
            "Check that the system temporary directory " + System.getProperty("java.io.tmpdir")
                + " is writable, then reload the project.");
    }

    /** {@code ". Last output: "} and the run's last lines, or empty when it wrote none. */
    private static String lastOutput(BuildToolRunner.Result result) {
        String snippet = result.lastLines();
        return snippet.isEmpty() ? "" : ". Last output: " + snippet;
    }

    /** {@code limit} in minutes when it is a whole number of minutes, otherwise in seconds. */
    private static String describe(Duration limit) {
        if (!limit.isZero() && limit.equals(Duration.ofMinutes(limit.toMinutes()))) {
            return limit.toMinutes() + " minutes";
        }
        return limit.toSeconds() + " seconds";
    }

    /**
     * The structure read statically from {@code settings.gradle}, laid out by convention by
     * {@link BuildModelAssembler}, followed by {@code failure}. This importer implements none
     * of the per-concern methods the assembler composes, so the model has no classpath,
     * processors or level, and Gradle is not run again.
     */
    private BuildModel staticModel(Path projectRoot, List<LoadWarning> failure) {
        BuildModel read = BuildModelAssembler.assemble(this, BuildSystem.GRADLE, Provenance.STATIC_READ, projectRoot);
        List<LoadWarning> warnings = new ArrayList<>(read.warnings());
        warnings.addAll(failure);
        return new BuildModel(read.buildSystem(), read.provenance(), read.modules(), read.classpath(),
            read.processorJars(), read.analysisLevel(), read.inputs(), warnings);
    }

    /** Delete {@code scratch} and everything in it; a failure is logged at warn. */
    private static void deleteScratch(Path scratch) {
        try (Stream<Path> walk = Files.walk(scratch)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete the Gradle scratch directory {}: {}", scratch, e.getMessage());
        }
    }

    /**
     * Locate a Gradle binary to invoke. Prefers the project's Gradle Wrapper
     * ({@code ./gradlew}), then {@code gradle} on PATH. Test runs can override via the
     * {@code javalens.gradle.binary} system property.
     */
    private String resolveGradleBinary(Path projectPath) {
        String override = System.getProperty("javalens.gradle.binary");
        if (override != null && !override.isBlank()) return override;

        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        Path wrapper = projectPath.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) return wrapper.toAbsolutePath().toString();

        return isWindows() ? "gradle.bat" : "gradle";
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
