package org.javalens.core.fixtures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Maven binary a test points the Maven importer at, through the {@value #PROPERTY}
 * system property the importer reads. An instance holds the override until it is closed,
 * then restores the property's previous value, so tests use it in a try-with-resources block.
 *
 * <p>{@link #require} uses the {@code mvn} (Windows: {@code mvn.cmd}) on PATH and is gated
 * with {@link TestEnvironment#requireOrSkip}: a machine without Maven skips the test locally
 * and fails it when {@code JAVALENS_TESTS_REQUIRE_TOOLS=true}. {@link #use} points the
 * importer at any binary, such as one that does not exist.
 */
public final class MavenBinary implements AutoCloseable {

    /** The system property the Maven importer reads its binary from. */
    public static final String PROPERTY = "javalens.maven.binary";

    private final String binary;
    private final String previous;

    private MavenBinary(String binary) {
        this.binary = binary;
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, binary);
    }

    /**
     * Point the Maven importer at the Maven on PATH until the returned instance is closed.
     * When there is none the test is skipped, or failed when tools are required;
     * {@code purpose} names the test's need in that message.
     */
    public static MavenBinary require(String purpose) {
        String binary = Located.BINARY;
        TestEnvironment.requireOrSkip(binary, "Maven binary (" + purpose + ")");
        return new MavenBinary(binary);
    }

    /** Point the Maven importer at {@code binary} until the returned instance is closed. */
    public static MavenBinary use(String binary) {
        return new MavenBinary(binary);
    }

    /** Run this Maven in {@code dir} with {@code args}; fails when it does not exit 0. */
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
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("mvn " + String.join(" ", args) + " timed out\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("mvn " + String.join(" ", args) + " exited with code "
                + process.exitValue() + "\n" + output);
        }
    }

    /** Restore the property's value from before this instance set it. */
    @Override
    public void close() {
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }

    /** Holds the lookup's result, computed once per test run. */
    private static final class Located {
        static final String BINARY = find();
    }

    private static String find() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        try {
            Process process = new ProcessBuilder(name, "-v").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // drain
                }
            }
            return process.waitFor() == 0 ? name : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
