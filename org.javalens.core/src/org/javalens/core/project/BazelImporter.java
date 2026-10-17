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
 * Bazel {@link BuildSystemImporter}: the project model is the one Bazel reports.
 *
 * <p>{@link #resolve} asks Bazel, through {@link BuildToolRunner}, in the project root:
 * {@code bazel query} for the main repository's Java targets and their sources and for the
 * build files the build reads, {@code bazel aquery} for each target's javac action (its
 * arguments, as the toolchain, {@code .bazelrc} and the target set them), and
 * {@code bazel info execution_root}, against which the action's paths resolve. The answers
 * are written into a scratch directory deleted once the model is read, and every command runs
 * with {@code --lockfile_mode=off}, so nothing is written into the project tree.
 * {@link BazelModels} builds the model, with provenance {@link Provenance#BUILD_TOOL}.
 * Arguments in {@code JAVALENS_BAZEL_ARGS} are build flags, passed to {@code aquery} and
 * {@code info}; {@code query}, which is configuration-independent, takes none.
 *
 * <p>When Bazel does not answer, the model is read from the BUILD files
 * ({@link BazelModels#staticModel}), provenance {@link Provenance#STATIC_READ}, with the
 * build-tool warning saying why and a {@link LoadWarning#BUILD_MODEL_STATIC}.
 */
public class BazelImporter implements BuildSystemImporter {

    private static final Logger log = LoggerFactory.getLogger(BazelImporter.class);

    /** The prefix of the scratch directory each load creates in the system temporary directory. */
    private static final String SCRATCH_PREFIX = "javalens-bazel-model-";

    /**
     * How long each Bazel command may take before it is stopped. A first command may start
     * the Bazel server and fetch the toolchains the analysis needs, hence a generous limit.
     */
    private static final Duration BAZEL_TIMEOUT = Duration.ofMinutes(10);

    private final Duration timeout;

    public BazelImporter() {
        this(BAZEL_TIMEOUT);
    }

    /** Test seam: stop each Bazel command after {@code timeout}. */
    BazelImporter(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public BuildModel resolve(Path projectRoot) {
        List<LoadWarning> failure = new ArrayList<>();
        Optional<BuildModel> reported = reportedModel(projectRoot, failure);
        if (reported.isPresent()) {
            return reported.get();
        }
        failure.add(new LoadWarning(LoadWarning.BUILD_MODEL_STATIC,
            "Bazel did not report the project model, so the packages and Java levels were read from the BUILD files; "
                + "a static read cannot see .bazelrc flags, the toolchain's default level, macros or generated "
                + "sources, so each package is laid out by convention and the classpath is every jar in Bazel's "
                + "output trees",
            "Fix the build-tool problem reported above, then reload the project."));
        return BazelModels.staticModel(projectRoot, failure);
    }

    /**
     * Ask Bazel in a scratch directory of its own and build the model from its answers,
     * deleting the scratch directory afterwards. Empty, with the reason appended to
     * {@code failure}, when Bazel does not answer.
     */
    private Optional<BuildModel> reportedModel(Path projectRoot, List<LoadWarning> failure) {
        Path scratch;
        try {
            scratch = Files.createTempDirectory(SCRATCH_PREFIX);
        } catch (IOException e) {
            failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Could not prepare the Bazel commands that read the project model: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(),
                "Check that the system temporary directory " + System.getProperty("java.io.tmpdir")
                    + " is writable, then reload the project."));
            return Optional.empty();
        }
        try {
            return askBazel(projectRoot, scratch, failure);
        } finally {
            deleteScratch(scratch);
        }
    }

    private Optional<BuildModel> askBazel(Path projectRoot, Path scratch, List<LoadWarning> failure) {
        Path targets = scratch.resolve("targets.xml");
        Path buildFiles = scratch.resolve("buildfiles.txt");
        Path actions = scratch.resolve("actions.json");
        log.info("Running Bazel to read the project model of {}", projectRoot);
        if (!run(projectRoot, failure, "query", "kind(\"java_.*\", //...)", "--output=xml", "--output_file=" + targets)
                || !run(projectRoot, failure, "query", "buildfiles(//...)", "--output=label",
                    "--output_file=" + buildFiles)
                || !run(projectRoot, failure, "aquery", "mnemonic(\"Javac\", //...)", "--output=jsonproto",
                    "--output_file=" + actions)) {
            return Optional.empty();
        }
        List<String> info = new ArrayList<>();
        if (!run(projectRoot, failure, info, "info", "execution_root")) {
            return Optional.empty();
        }
        Optional<Path> executionRoot = executionRoot(info);
        if (executionRoot.isEmpty()) {
            failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Bazel exited with code 0, but reported no execution root. Last output: " + String.join(" | ", info),
                "Run 'bazel info execution_root' in " + projectRoot + " to see what Bazel reports."));
            return Optional.empty();
        }
        try {
            BuildModel model = BazelModels.reportedModel(projectRoot, targets, buildFiles, actions, executionRoot.get());
            log.info("Bazel reported {} packages, {} classpath entries, {} processor jars, level {}",
                model.modules().size(), model.classpath().size(), model.processorJars().size(), model.analysisLevel());
            return Optional.of(model);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Bazel's project model could not be read", e);
            failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                "Bazel exited with code 0, but the project model could not be read: " + e.getMessage(),
                "Run 'bazel query \"kind(\\\"java_.*\\\", //...)\"' in " + projectRoot + " to see what Bazel reports."));
            return Optional.empty();
        }
    }

    private boolean run(Path projectRoot, List<LoadWarning> failure, String... command) {
        return run(projectRoot, failure, new ArrayList<>(), command);
    }

    /**
     * Run one Bazel command; false, with the reason appended to {@code failure}, when it does
     * not exit 0. Its output lines are added to {@code output}.
     */
    private boolean run(Path projectRoot, List<LoadWarning> failure, List<String> output, String... command) {
        String bazel = bazelBinary();
        List<String> full = new ArrayList<>();
        full.add(bazel);
        full.addAll(List.of(command));
        full.add("--lockfile_mode=off");
        // query takes no build flags, and is configuration-independent; the configured
        // commands, aquery and info, take them.
        if (!command[0].equals("query")) {
            full.addAll(BuildToolArgs.of(BuildToolArgs.BAZEL));
        }
        BuildToolRunner.Result result = BuildToolRunner.run(full, projectRoot, timeout);
        output.addAll(result.retainedLines());
        String described = "bazel " + command[0];
        switch (result.outcome()) {
            case UNAVAILABLE -> {
                log.warn("Cannot start Bazel: {}", result.startFailure());
                failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_UNAVAILABLE,
                    "Could not start '" + bazel + "': " + result.startFailure(),
                    "Install Bazel (or Bazelisk as 'bazel') and ensure it is on PATH for the process that launches "
                        + "JavaLens."));
                return false;
            }
            case TIMED_OUT -> {
                log.warn("{} did not finish within {}", described, timeout);
                failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_TIMEOUT,
                    "'" + described + "' did not finish within " + timeout.toMinutes() + " minutes",
                    "Run '" + described + "' in " + projectRoot + " to see why it does not finish."));
                return false;
            }
            case INTERRUPTED -> {
                failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "'" + described + "' was interrupted before it finished",
                    "Reload the project to run the build tool again."));
                return false;
            }
            case EXITED -> {
                if (result.exitCode() == 0) return true;
                String snippet = result.lastLines();
                failure.add(new LoadWarning(LoadWarning.BUILD_TOOL_FAILED,
                    "'" + described + "' exited with code " + result.exitCode()
                        + (snippet.isEmpty() ? "" : ". Last output: " + snippet),
                    "Run '" + described + "' in " + projectRoot + " to see the full error."));
                return false;
            }
        }
        return false;
    }

    /** The last output line that is an absolute path, which {@code bazel info execution_root} prints. */
    private static Optional<Path> executionRoot(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("INFO") || line.startsWith("WARNING")) continue;
            try {
                Path path = Path.of(line);
                if (path.isAbsolute()) return Optional.of(path);
            } catch (InvalidPathException e) {
                // Not the path line.
            }
        }
        return Optional.empty();
    }

    /** Delete {@code scratch} and everything in it; a failure is logged at warn. */
    private static void deleteScratch(Path scratch) {
        try (Stream<Path> walk = Files.walk(scratch)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete the Bazel scratch directory {}: {}", scratch, e.getMessage());
        }
    }

    /**
     * The Bazel binary to run: the {@code javalens.bazel.binary} system property when set (a
     * test seam), otherwise {@code bazel} on PATH.
     */
    private static String bazelBinary() {
        String override = System.getProperty("javalens.bazel.binary");
        return override != null && !override.isBlank() ? override : "bazel";
    }
}
