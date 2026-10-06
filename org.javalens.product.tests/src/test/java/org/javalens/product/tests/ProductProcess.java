package org.javalens.product.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Launches a copy of the materialized JavaLens product the way the README configures an MCP
 * client ({@code java -jar javalens.jar -data <dir>}, with the JVM running the tests) and
 * drives the standard MCP handshake, load_project and health_check over stdio.
 */
final class ProductProcess {

    /** Bound for each response and for the process to exit after stdin is closed. */
    private static final long RESPONSE_SECONDS = 300;
    private static final long EXIT_SECONDS = 120;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the product wrote: stdout and stderr split into lines, and its exit code. */
    record Output(List<String> stdout, List<String> stderr, int exitCode) {
    }

    private ProductProcess() {
    }

    /**
     * The materialized product for the running OS and architecture. The test fails, never
     * skips, when it is not there.
     */
    static Path installDirectory() {
        String products = System.getProperty("javalens.products.dir");
        assertTrue(products != null && !products.isBlank(), "javalens.products.dir is not set");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> System.getProperty("os.arch");
        };
        Path root = Path.of(products);
        Path install;
        if (os.startsWith("windows")) {
            install = root.resolve("win32/win32").resolve(arch).resolve("javalens");
        } else if (os.startsWith("linux")) {
            install = root.resolve("linux/gtk").resolve(arch).resolve("javalens");
        } else if (os.startsWith("mac")) {
            install = root.resolve("macosx/cocoa").resolve(arch).resolve("JavaLens.app/Contents/Eclipse");
        } else {
            throw new AssertionError("No product is built for os.name " + System.getProperty("os.name"));
        }
        assertTrue(Files.isRegularFile(install.resolve("javalens.jar")),
            "The materialized product is missing: " + install.resolve("javalens.jar"));
        return install;
    }

    /**
     * Copy the product at {@code install} to {@code destination}. A running product writes
     * OSGi caches into its configuration directory, and the release archives are made from
     * the materialized product directory, so tests run a copy and leave that one as built.
     */
    static Path copyOf(Path install, Path destination) throws IOException {
        try (Stream<Path> files = Files.walk(install)) {
            for (Path source : (Iterable<Path>) files::iterator) {
                Path target = destination.resolve(install.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return destination;
    }

    /** True on Windows, where the Maven launcher script is mvn.cmd. */
    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Run the product: initialize (id 1), notifications/initialized, load_project of
     * {@code project} (id 2) and health_check (id 3), each request sent after the previous
     * response arrived; then close stdin and wait for the process to exit.
     *
     * @param vmArgs JVM options placed before {@code -jar}
     * @param logLevel the JAVALENS_LOG_LEVEL value, or null to leave it unset
     */
    static Output run(Path install, Path project, Path workspaces, List<String> vmArgs, String logLevel)
            throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(vmArgs);
        command.add("-jar");
        command.add(install.resolve("javalens.jar").toString());
        command.add("-data");
        command.add(workspaces.toString());

        Path stderrFile = workspaces.resolveSibling("stderr.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(stderrFile.toFile());
        Map<String, String> env = builder.environment();
        // An inherited JAVA_PROJECT_PATH would start a second load. The JVM announces
        // JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and _JAVA_OPTIONS on stderr, which would add
        // lines the server did not write.
        env.remove("JAVA_PROJECT_PATH");
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("JDK_JAVA_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        env.remove("JAVALENS_LOG_LEVEL");
        if (logLevel != null) {
            env.put("JAVALENS_LOG_LEVEL", logLevel);
        }

        Process process = builder.start();
        List<String> stdout = new ArrayList<>();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (stdout) {
                        stdout.add(line);
                    }
                    lines.add(line);
                }
            } catch (IOException e) {
                // The stream closes with the process; what was read is kept.
            }
        }, "product-stdout");
        reader.setDaemon(true);
        reader.start();

        boolean exited;
        try (Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            send(in, request(1, "initialize", JSON.createObjectNode()
                .put("protocolVersion", "2024-11-05")
                .<ObjectNode>set("capabilities", JSON.createObjectNode())
                .set("clientInfo", JSON.createObjectNode().put("name", "product-tests").put("version", "1"))));
            awaitResponse(process, reader, lines, 1);
            send(in, JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
            send(in, toolCall(2, "load_project", JSON.createObjectNode().put("projectPath", project.toString())));
            awaitResponse(process, reader, lines, 2);
            send(in, toolCall(3, "health_check", JSON.createObjectNode()));
            awaitResponse(process, reader, lines, 3);
        } finally {
            // stdin is closed by now; the server ends its message loop and exits. Only this
            // process is ever destroyed.
            exited = process.waitFor(EXIT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
        }
        assertTrue(exited, "The product did not exit within " + EXIT_SECONDS + " s after stdin was closed");
        int exitCode = process.exitValue();
        reader.join(TimeUnit.SECONDS.toMillis(30));

        Charset stderrCharset = Charset.forName(System.getProperty("native.encoding"));
        List<String> stderr = Files.readAllLines(stderrFile, stderrCharset);
        synchronized (stdout) {
            return new Output(List.copyOf(stdout), stderr, exitCode);
        }
    }

    private static ObjectNode request(int id, String method, ObjectNode params) {
        ObjectNode node = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        node.set("params", params);
        return node;
    }

    private static ObjectNode toolCall(int id, String tool, ObjectNode arguments) {
        ObjectNode params = JSON.createObjectNode().put("name", tool);
        params.set("arguments", arguments);
        return request(id, "tools/call", params);
    }

    private static void send(Writer in, ObjectNode message) throws IOException {
        in.write(JSON.writeValueAsString(message));
        in.write("\n");
        in.flush();
    }

    /**
     * Wait for the response with {@code id}; fail when the bound passes, or when the process
     * has ended and its stdout is drained without it.
     */
    private static void awaitResponse(Process process, Thread reader, BlockingQueue<String> lines, int id)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESPONSE_SECONDS);
        while (true) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) {
                if (!process.isAlive() && !reader.isAlive() && lines.isEmpty()) {
                    fail("The product exited with code " + process.exitValue()
                        + " before sending the response with id " + id);
                }
                if (System.nanoTime() > deadline) {
                    fail("No response with id " + id + " within " + RESPONSE_SECONDS + " s");
                }
                continue;
            }
            try {
                if (JSON.readTree(line).path("id").asInt(-1) == id) {
                    return;
                }
            } catch (IOException notJson) {
                // Kept in the stdout lines, which the test checks in full.
            }
        }
    }
}
