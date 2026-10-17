package org.javalens.core.fixtures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The Bazel binary a test points the Bazel importer at, through the {@value #PROPERTY}
 * system property the importer reads. An instance holds the override until it is closed,
 * then restores the property's previous value, so tests use it in a try-with-resources block.
 *
 * <p>{@link #require} uses the binary {@link #locate} finds and is gated with
 * {@link TestEnvironment#requireOrSkip}: a machine without Bazel skips the test locally and
 * fails it when {@code JAVALENS_TESTS_REQUIRE_TOOLS=true}. {@link #use} points the importer
 * at any binary, such as one that does not exist, so a test reads the BUILD files statically.
 *
 * <p>{@link #isolate} gives a project its own Bazel output root through a {@code startup}
 * line in its {@code .bazelrc}, which every Bazel command run there, the importer's included,
 * reads; {@link #close} shuts that server down so the output root can be deleted.
 */
public final class BazelBinary implements AutoCloseable {

    /** The system property the Bazel importer reads its binary from. */
    public static final String PROPERTY = "javalens.bazel.binary";

    private final String binary;
    private final String previous;
    private final List<Path> isolated = new ArrayList<>();

    private BazelBinary(String binary) {
        this.binary = binary;
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, binary);
    }

    /**
     * Point the Bazel importer at the binary {@link #locate} finds until the returned instance
     * is closed. When none is found the test is skipped, or failed when tools are required;
     * {@code purpose} names the test's need in that message.
     */
    public static BazelBinary require(String purpose) {
        String binary = locate();
        TestEnvironment.requireOrSkip(binary, "Bazel binary (" + purpose + ")");
        return new BazelBinary(binary);
    }

    /** Point the Bazel importer at {@code binary} until the returned instance is closed. */
    public static BazelBinary use(String binary) {
        return new BazelBinary(binary);
    }

    /**
     * Append {@code startup --output_user_root=<outputRoot>} to {@code project}'s
     * {@code .bazelrc}, so every Bazel command run there uses an output root of its own.
     * Bazel needs a path with no spaces.
     */
    public void isolate(Path project, Path outputRoot) throws IOException {
        Files.createDirectories(outputRoot);
        String line = "startup --output_user_root=" + outputRoot.toAbsolutePath().toString().replace('\\', '/') + "\n";
        Files.writeString(project.resolve(".bazelrc"), line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        isolated.add(project);
    }

    /** Run this Bazel in {@code dir} with {@code args}; fails when it does not exit 0. */
    public void run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 8192) output.append(line).append('\n');
            }
        }
        // A first command may download the JDK toolchain, hence a generous limit.
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("bazel " + String.join(" ", args) + " timed out\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("bazel " + String.join(" ", args) + " exited with code "
                + process.exitValue() + "\n" + output);
        }
    }

    /**
     * A Bazel binary tests can invoke, or null when none is found: {@code bazel} or
     * {@code bazelisk} on PATH, then {@code ~/javalens-tools/bazel[.exe]} or
     * {@code bazelisk[.exe]}. The lookup runs once per test run.
     */
    public static String locate() {
        return Located.BINARY;
    }

    /** Shut down the Bazel server of each isolated project, then restore the property. */
    @Override
    public void close() {
        for (Path project : isolated) {
            try {
                run(project, "shutdown");
            } catch (IOException | IllegalStateException e) {
                // The server may already be gone; the output root is then free to delete.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }

    private static final class Located {
        static final String BINARY = find();
    }

    private static String find() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        for (String name : windows ? List.of("bazel.exe", "bazelisk.exe") : List.of("bazel", "bazelisk")) {
            try {
                Process process = new ProcessBuilder(name, "version").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    while (reader.readLine() != null) {
                        // drain
                    }
                }
                if (process.waitFor() == 0) return name;
            } catch (IOException e) {
                // Not on PATH under this name.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Path tools = Path.of(System.getProperty("user.home"), "javalens-tools");
        if (!Files.isDirectory(tools)) return null;
        try (Stream<Path> entries = Files.list(tools)) {
            return entries
                .filter(entry -> List.of("bazel.exe", "bazelisk.exe", "bazel", "bazelisk")
                    .contains(entry.getFileName().toString().toLowerCase()))
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + tools, e);
        }
    }
}
