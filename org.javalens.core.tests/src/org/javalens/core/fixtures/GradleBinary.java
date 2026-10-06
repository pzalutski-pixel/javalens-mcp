package org.javalens.core.fixtures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The Gradle binary a test points the Gradle importer at, through the
 * {@value #PROPERTY} system property the importer reads. An instance holds the override
 * until it is closed, then restores the property's previous value, so tests use it in a
 * try-with-resources block.
 *
 * <p>{@link #require} uses the binary {@link #locate} finds and is gated with
 * {@link TestEnvironment#requireOrSkip}: a machine without Gradle skips the test locally
 * and fails it when {@code JAVALENS_TESTS_REQUIRE_TOOLS=true}. {@link #use} points the
 * importer at any binary, such as a fake one a test writes.
 */
public final class GradleBinary implements AutoCloseable {

    /** The system property the Gradle importer reads its binary from. */
    public static final String PROPERTY = "javalens.gradle.binary";

    private final String previous;

    private GradleBinary(String binary) {
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, binary);
    }

    /**
     * Point the Gradle importer at the binary {@link #locate} finds until the returned
     * instance is closed. When none is found the test is skipped, or failed when tools are
     * required; {@code purpose} names the test's need in that message.
     */
    public static GradleBinary require(String purpose) {
        String binary = locate();
        TestEnvironment.requireOrSkip(binary, "Gradle binary (" + purpose + ")");
        return new GradleBinary(binary);
    }

    /** Point the Gradle importer at {@code binary} until the returned instance is closed. */
    public static GradleBinary use(String binary) {
        return new GradleBinary(binary);
    }

    /**
     * A Gradle binary tests can invoke, or null when none is found. Looks in order at the
     * {@code JAVALENS_TEST_GRADLE_HOME} environment variable's {@code bin} directory, the
     * {@code gradle} (Windows: {@code gradle.bat}) on PATH, {@code ~/javalens-tools/<dir>/bin},
     * and the Gradle Wrapper distribution cache
     * {@code ~/.gradle/wrapper/dists/<dist>/<hash>/<gradle>/bin}. The lookup runs once per
     * test run.
     */
    public static String locate() {
        return Located.BINARY;
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

    /** Holds the lookup's result, computed when {@link #locate} first runs. */
    private static final class Located {
        static final String BINARY = find();
    }

    private static String find() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String binaryName = isWindows ? "gradle.bat" : "gradle";

        String envHome = System.getenv("JAVALENS_TEST_GRADLE_HOME");
        if (envHome != null && !envHome.isBlank()) {
            Path bin = Path.of(envHome).resolve("bin").resolve(binaryName);
            if (Files.isRegularFile(bin)) return bin.toString();
        }

        if (runsOnPath(binaryName)) return binaryName;

        Path tools = Path.of(System.getProperty("user.home"), "javalens-tools");
        if (Files.isDirectory(tools)) {
            try (Stream<Path> entries = Files.list(tools)) {
                for (Path entry : entries.toList()) {
                    Path bin = entry.resolve("bin").resolve(binaryName);
                    if (Files.isRegularFile(bin)) return bin.toString();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not list " + tools, e);
            }
        }

        Path wrapperDists = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        if (Files.isDirectory(wrapperDists)) {
            try (Stream<Path> distros = Files.walk(wrapperDists, 4)) {
                return distros.filter(p -> p.getFileName() != null
                            && binaryName.equals(p.getFileName().toString()))
                        .filter(p -> p.getParent() != null
                            && "bin".equals(p.getParent().getFileName().toString()))
                        .map(Path::toString)
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                throw new IllegalStateException("Could not walk " + wrapperDists, e);
            }
        }

        return null;
    }

    /** Whether {@code binaryName} starts from PATH and exits 0 on {@code -v}. */
    private static boolean runsOnPath(String binaryName) {
        Process process;
        try {
            process = new ProcessBuilder(binaryName, "-v").redirectErrorStream(true).start();
        } catch (IOException e) {
            // Not on PATH: the process cannot start.
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // Drain the version banner so the process can exit.
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read '" + binaryName + " -v'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running '" + binaryName + " -v'", e);
        }
    }
}
