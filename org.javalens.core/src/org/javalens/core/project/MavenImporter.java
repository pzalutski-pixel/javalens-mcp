package org.javalens.core.project;

import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.project.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Maven {@link BuildSystemImporter}: the project model is the one Maven reports.
 *
 * <p>{@link #resolve} runs Maven once, through {@link BuildToolRunner}, in the project root:
 * {@code help:effective-pom} writes the effective pom of every reactor project, and
 * {@code dependency:build-classpath} writes each project's classpath to a file named by its
 * coordinates, both into a scratch directory created for the run and deleted once the model
 * is read, so nothing is written into the project tree. {@link MavenModels} builds the model,
 * with provenance {@link Provenance#BUILD_TOOL}. When a module declares annotation processor
 * paths, a second run asks Maven for its local repository, where the processor jars are.
 * Arguments in {@code JAVALENS_MAVEN_ARGS} are passed to both runs.
 *
 * <p>Maven runs with {@code --fail-at-end}: when it reports the effective poms but fails on
 * some modules, the model is still Maven's, and each module Maven wrote no classpath for is
 * named by a {@link LoadWarning#BUILD_TOOL_FAILED}.
 *
 * <p>When Maven does not produce the model, the model is read from the {@code pom.xml} files
 * as XML ({@link MavenModels#walkStatic}), with no classpath, provenance
 * {@link Provenance#STATIC_READ}, the build-tool warning saying why, and a
 * {@link LoadWarning#BUILD_MODEL_STATIC}. Maven is not run again.
 */
public class MavenImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(MavenImporter.class);

    private static final String HELP_PLUGIN = "org.apache.maven.plugins:maven-help-plugin:3.5.1";
    private static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1";

    /** The prefix of the scratch directory each run creates in the system temporary directory. */
    private static final String SCRATCH_PREFIX = "javalens-maven-model-";

    /**
     * How long a Maven run may take before it is stopped. A first run may download the help
     * and dependency plugins, hence a generous limit; later runs hit the local repository.
     */
    private static final Duration MAVEN_TIMEOUT = Duration.ofMinutes(10);

    private final Duration timeout;

    public MavenImporter() {
        this(MAVEN_TIMEOUT);
    }

    /** Test seam: stop each Maven run after {@code timeout}. */
    MavenImporter(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public BuildModel resolve(Path projectRoot) {
        List<LoadWarning> failure = new ArrayList<>();
        Optional<BuildModel> reported = reportedModel(projectRoot, failure);
        return reported.isPresent() ? reported.get() : staticModel(projectRoot, failure);
    }

    /**
     * Run Maven in a scratch directory of its own and build the model from what it wrote,
     * deleting the scratch directory afterwards. Empty, with the reason appended to
     * {@code failure}, when Maven does not produce the model.
     */
    private Optional<BuildModel> reportedModel(Path projectRoot, List<LoadWarning> failure) {
        Path scratch;
        try {
            scratch = Files.createTempDirectory(SCRATCH_PREFIX);
        } catch (IOException e) {
            failure.add(preparationFailure(e));
            return Optional.empty();
        }
        try {
            return runMaven(projectRoot, scratch, failure);
        } finally {
            deleteScratch(scratch);
        }
    }

    private Optional<BuildModel> runMaven(Path projectRoot, Path scratch, List<LoadWarning> failure) {
        Path effectivePom = scratch.resolve("effective-pom.xml");
        Path classpathDir = scratch.resolve("classpath");
        String mvn = mavenBinary();
        // --fail-at-end: a module whose dependencies do not resolve (one that depends on a
        // module of the build that is not installed, typically) fails alone; the effective
        // pom, written at the root project, and every other module's classpath still come.
        List<String> command = new ArrayList<>(List.of(
            mvn, "-B", "-q", "--fail-at-end",
            HELP_PLUGIN + ":effective-pom", "-Doutput=" + effectivePom,
            DEPENDENCY_PLUGIN + ":build-classpath",
            // Maven expands the project references for each reactor project, so each writes
            // its own file.
            "-Dmdep.outputFile=" + classpathDir.resolve("${project.groupId}__${project.artifactId}.txt")));
        command.addAll(BuildToolArgs.of(BuildToolArgs.MAVEN));

        log.info("Running Maven to read the project model of {}", projectRoot);
        BuildToolRunner.Result result = BuildToolRunner.run(command, projectRoot, timeout);
        Optional<LoadWarning> runFailure = runFailure(result, mvn, projectRoot, "reading the project model");
        boolean exitedNonZero = result.outcome() == BuildToolRunner.Outcome.EXITED && result.exitCode() != 0;
        if (runFailure.isPresent() && !(exitedNonZero && Files.isRegularFile(effectivePom))) {
            failure.add(runFailure.get());
            return Optional.empty();
        }

        List<LoadWarning> warnings = new ArrayList<>();
        List<MavenModels.Module> reactor;
        try {
            reactor = MavenModels.walkEffective(projectRoot, MavenModels.readEffective(effectivePom), warnings);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Maven's project model could not be read", e);
            failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Maven exited with code 0, but the project model could not be read: " + e.getMessage(),
                "Run 'mvn help:effective-pom' in " + projectRoot + " to see the model Maven reports."));
            return Optional.empty();
        }

        Path localRepository = null;
        if (MavenModels.declaresProcessors(reactor)) {
            localRepository = localRepository(projectRoot, scratch, warnings).orElse(null);
        }
        // Maven reported the structure but failed on some modules: each module it wrote no
        // classpath for is named, with Maven's last output.
        List<LoadWarning> unresolved = new ArrayList<>();
        if (exitedNonZero) {
            for (MavenModels.Module module : reactor) {
                if (!Files.isRegularFile(MavenModels.classpathFile(classpathDir, module))) {
                    String snippet = result.lastLines();
                    unresolved.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                        "Maven exited with code " + result.exitCode() + " and did not resolve the dependencies of "
                            + "module '" + module.pom().id() + "', so they are not on the classpath"
                            + (snippet.isEmpty() ? "" : ". Last output: " + snippet),
                        "If the module depends on another module of this build, install the build once "
                            + "('mvn install -DskipTests') so Maven can resolve it, then reload the project.",
                        module.pom().id()));
                }
            }
            if (unresolved.isEmpty()) {
                unresolved.add(runFailure.get());
            }
        } else if (reactor.stream().noneMatch(module ->
                Files.isRegularFile(MavenModels.classpathFile(classpathDir, module)))) {
            unresolved.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Maven exited successfully but wrote no classpath file, so the classpath holds no dependencies",
                "Run 'mvn dependency:build-classpath' in " + projectRoot + " to confirm the plugin runs."));
        }
        BuildModel model = MavenModels.model(projectRoot, Provenance.BUILD_TOOL, reactor, classpathDir,
            localRepository, warnings, unresolved);
        log.info("Maven reported {} modules, {} classpath entries, {} processor jars, level {}",
            model.modules().size(), model.classpath().size(), model.processorJars().size(), model.analysisLevel());
        return Optional.of(model);
    }

    /**
     * The local repository Maven resolves artifacts in, from a second run of
     * {@code help:evaluate}. Empty, with a {@link LoadWarning#BUILD_TOOL_FAILED} saying the
     * annotation processors are not registered, when the run does not report it.
     */
    private Optional<Path> localRepository(Path projectRoot, Path scratch, List<LoadWarning> warnings) {
        Path output = scratch.resolve("local-repository.txt");
        String mvn = mavenBinary();
        List<String> command = new ArrayList<>(List.of(
            mvn, "-B", "-q", "-N", HELP_PLUGIN + ":evaluate",
            "-Dexpression=settings.localRepository", "-Doutput=" + output));
        command.addAll(BuildToolArgs.of(BuildToolArgs.MAVEN));
        BuildToolRunner.Result result = BuildToolRunner.run(command, projectRoot, timeout);
        Optional<LoadWarning> runFailure = runFailure(result, mvn, projectRoot, "reading its local repository");
        String reason = runFailure.map(LoadWarning::message).orElse(null);
        if (reason == null) {
            try {
                String value = Files.readString(output).trim();
                if (!value.isEmpty()) {
                    return Optional.of(Path.of(value));
                }
                reason = "Maven reported an empty local repository";
            } catch (IOException | InvalidPathException e) {
                reason = "Maven's local repository could not be read: " + e.getMessage();
            }
        }
        warnings.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
            reason + "; the declared annotation processors are not registered",
            "Run 'mvn help:evaluate -Dexpression=settings.localRepository' in " + projectRoot
                + " to see the error, then reload the project."));
        return Optional.empty();
    }

    /**
     * The model read from the {@code pom.xml} files as XML, with no classpath, followed by
     * {@code failure} and a {@link LoadWarning#BUILD_MODEL_STATIC}. Processor jars are
     * resolved in the default local repository.
     */
    private BuildModel staticModel(Path projectRoot, List<LoadWarning> failure) {
        List<LoadWarning> warnings = new ArrayList<>();
        List<MavenModels.Module> reactor = MavenModels.walkStatic(projectRoot, warnings);
        List<LoadWarning> trailing = new ArrayList<>(failure);
        trailing.add(new LoadWarning(LoadWarning.BUILD_MODEL_STATIC,
            "Maven did not report the project model, so the modules, source directories, Java levels and "
                + "annotation processors were read from the pom.xml files; a static read resolves no dependencies "
                + "(the classpath holds none), does not evaluate profile activation or parents outside the project "
                + "tree, and looks for processor jars in the default local repository "
                + MavenModels.defaultLocalRepository(),
            "Fix the build-tool problem reported above, then reload the project."));
        return MavenModels.model(projectRoot, Provenance.STATIC_READ, reactor, null,
            MavenModels.defaultLocalRepository(), warnings, trailing);
    }

    /**
     * The warning for a run that did not exit 0: one that could not start, ran past
     * {@link #timeout}, was interrupted, or exited non-zero. Empty for a run that exited 0.
     */
    private Optional<LoadWarning> runFailure(BuildToolRunner.Result result, String mvn, Path projectRoot,
            String purpose) {
        return switch (result.outcome()) {
            case UNAVAILABLE -> {
                log.warn("Cannot start Maven: {}", result.startFailure());
                yield Optional.of(new LoadWarning(LoadWarning.BUILD_TOOL_UNAVAILABLE,
                    "Could not start '" + mvn + "': " + result.startFailure(),
                    "Install Maven and ensure '" + mvn + "' is on PATH for the process that launches JavaLens."));
            }
            case TIMED_OUT -> {
                log.warn("Maven did not finish within {}", timeout);
                yield Optional.of(new LoadWarning(LoadWarning.BUILD_TOOL_TIMEOUT,
                    "Maven did not finish " + purpose + " within " + describe(timeout),
                    "Check that the project's pom.xml resolves correctly. Try running 'mvn validate' in "
                        + projectRoot));
            }
            case INTERRUPTED -> {
                log.warn("Maven was interrupted");
                yield Optional.of(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "Maven was interrupted before it finished " + purpose,
                    "Reload the project to run the build tool again."));
            }
            case EXITED -> {
                if (result.exitCode() == 0) {
                    yield Optional.empty();
                }
                log.warn("Maven exited with code {}", result.exitCode());
                String snippet = result.lastLines();
                yield Optional.of(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "Maven exited with code " + result.exitCode() + " while " + purpose
                        + (snippet.isEmpty() ? "" : ". Last output: " + snippet),
                    "Run 'mvn validate' in " + projectRoot + " to see the full error."));
            }
        };
    }

    /** The warning for a scratch directory that could not be created. */
    private static LoadWarning preparationFailure(IOException e) {
        log.warn("Could not prepare the Maven run", e);
        return new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
            "Could not prepare the Maven run that reads the project model: "
                + e.getClass().getSimpleName() + ": " + e.getMessage(),
            "Check that the system temporary directory " + System.getProperty("java.io.tmpdir")
                + " is writable, then reload the project.");
    }

    /** {@code limit} in minutes when it is a whole number of minutes, otherwise in seconds. */
    private static String describe(Duration limit) {
        if (!limit.isZero() && limit.equals(Duration.ofMinutes(limit.toMinutes()))) {
            return limit.toMinutes() + " minutes";
        }
        return limit.toSeconds() + " seconds";
    }

    /** Delete {@code scratch} and everything in it; a failure is logged at warn. */
    private static void deleteScratch(Path scratch) {
        try (Stream<Path> walk = Files.walk(scratch)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete the Maven scratch directory {}: {}", scratch, e.getMessage());
        }
    }

    /**
     * The Maven binary to run: the {@code javalens.maven.binary} system property when set (a
     * test and pinned-Maven seam), otherwise {@code mvn} on PATH ({@code mvn.cmd} on Windows).
     */
    private static String mavenBinary() {
        String override = System.getProperty("javalens.maven.binary");
        if (override != null && !override.isBlank()) return override;
        return System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
    }
}
