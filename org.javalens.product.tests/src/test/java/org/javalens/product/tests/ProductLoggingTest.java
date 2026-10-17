package org.javalens.product.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shipped product's log output, observed by launching the materialized product as an
 * MCP client does. JavaLens logs through SLF4J's simple provider to stderr; stdout carries
 * only JSON-RPC. JAVALENS_LOG_LEVEL (TRACE, DEBUG, INFO, WARN, ERROR, compared without case
 * after trimming; INFO when unset or blank) sets the level of every stderr log line,
 * java.util.logging included; any other value is announced on stderr and logs at INFO. An
 * explicit -Dorg.slf4j.simpleLogger.defaultLogLevel takes precedence over the variable.
 *
 * <p>Each launch loads a one-class Maven project whose Maven binary cannot start, so the
 * load logs a known INFO line and two known WARN lines.
 */
class ProductLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> NO_PROVIDER_LINES = List.of(
        "SLF4J(W): No SLF4J providers were found.",
        "SLF4J(W): Defaulting to no-operation (NOP) logger implementation",
        "SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.");

    /** The second of the two lines java.util.logging writes when SPI Fly registers the provider. */
    private static final String SPI_FLY_MESSAGE = Level.INFO.getLocalizedName()
        + ": Registered provider org.slf4j.simple.SimpleServiceProvider of service "
        + "org.slf4j.spi.SLF4JServiceProvider in bundle slf4j.simple";
    /** The end of the first line, which starts with the time of the record. */
    private static final String SPI_FLY_SOURCE_SUFFIX = " org.apache.aries.spifly.BaseActivator log";

    @TempDir
    Path temp;

    private Path install;
    private Path project;
    private Path workspaces;
    private String mavenBinaryArg;
    private String loadingProjectLine;
    private String mavenWarningLine;
    private String loadWarningLine;

    @BeforeEach
    void setUp() throws Exception {
        // Real paths, so the server's view of the project path equals the test's.
        Path root = temp.toRealPath();
        install = ProductProcess.copyOf(ProductProcess.installDirectory(), root.resolve("product"));
        project = root.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId>
                <artifactId>log-check</artifactId>
                <version>1.0.0</version>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
            """);
        Files.writeString(project.resolve("src/main/java/demo/Hello.java"), """
            package demo;

            public class Hello {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """);
        workspaces = Files.createDirectories(root.resolve("workspaces"));
        Path absentMaven = root.resolve("no-maven").resolve(ProductProcess.isWindows() ? "mvn.cmd" : "mvn");
        mavenBinaryArg = "-Djavalens.maven.binary=" + absentMaven;

        loadingProjectLine = "[main] INFO org.javalens.mcp.tools.LoadProjectTool - Loading project: " + project;
        mavenWarningLine = "[main] WARN org.javalens.core.project.MavenImporter - Cannot start Maven: "
            + startFailure(absentMaven, project);
        loadWarningLine = "[main] WARN org.javalens.mcp.tools.LoadProjectTool - Project loaded with 2 warning(s)";
    }

    @Test
    @DisplayName("unset JAVALENS_LOG_LEVEL logs at INFO through the simple provider")
    void defaultLevelIsInfo() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces, List.of(mavenBinaryArg), null);

        assertJsonRpcOnly(out);
        assertInfoLevel(out.stderr());
    }

    @Test
    @DisplayName("JAVALENS_LOG_LEVEL=WARN leaves only the WARN lines on stderr")
    void warnLevel() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces, List.of(mavenBinaryArg), "WARN");

        assertJsonRpcOnly(out);
        assertEquals(List.of(mavenWarningLine, loadWarningLine), out.stderr());
    }

    @Test
    @DisplayName("JAVALENS_LOG_LEVEL=warn is the same level as WARN")
    void lowercaseWarnLevel() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces, List.of(mavenBinaryArg), "warn");

        assertJsonRpcOnly(out);
        assertEquals(List.of(mavenWarningLine, loadWarningLine), out.stderr());
    }

    @Test
    @DisplayName("JAVALENS_LOG_LEVEL=ERROR leaves stderr empty when nothing fails")
    void errorLevel() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces, List.of(mavenBinaryArg), "ERROR");

        assertJsonRpcOnly(out);
        assertEquals(List.of(), out.stderr());
    }

    @Test
    @DisplayName("an undocumented JAVALENS_LOG_LEVEL is announced once and logs at INFO")
    void invalidLevelFallsBackToInfo() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces, List.of(mavenBinaryArg), "verbose");

        assertJsonRpcOnly(out);
        String notice = "[javalens] JAVALENS_LOG_LEVEL \"verbose\" is not one of TRACE, DEBUG, INFO, WARN, ERROR; "
            + "logging at INFO";
        assertEquals(notice, out.stderr().get(0));
        assertEquals(1, out.stderr().stream().filter(line -> line.contains("JAVALENS_LOG_LEVEL")).count(),
            "notice lines in " + out.stderr());
        assertInfoLevel(out.stderr().subList(1, out.stderr().size()));
    }

    @Test
    @DisplayName("-Dorg.slf4j.simpleLogger.defaultLogLevel takes precedence over JAVALENS_LOG_LEVEL")
    void systemPropertyWins() throws Exception {
        ProductProcess.Output out = ProductProcess.run(install, project, workspaces,
            List.of(mavenBinaryArg, "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"), "DEBUG");

        assertJsonRpcOnly(out);
        assertEquals(List.of(mavenWarningLine, loadWarningLine), out.stderr());
    }

    /**
     * INFO level: the provider is bound, the known INFO line precedes the two WARN lines,
     * those two are the only WARN or ERROR lines, SPI Fly's java.util.logging record is
     * written, and the standard handshake logs no unknown notification.
     *
     * <p>Full equality of stderr is impossible at INFO because some lines differ on every
     * launch: SPI Fly's first java.util.logging line starts with the record's time;
     * WorkspaceManager's "Workspace initialized at: ..." and "Created project in workspace:
     * ..." carry the random session id; JdtServiceImpl's "Project loaded successfully at
     * ..." carries the load's instant; ToolRegistry's "Tool ... completed in ...ms" carries
     * durations. The exact parts still pin: no SLF4J report line at all (so the simple
     * provider is bound); the known INFO line and the two WARN lines, each compared in
     * full, in that order; the WARN and ERROR lines equal exactly those two, so no other
     * warning or error is logged; SPI Fly's message line compared in full and the source
     * that ends the line before it; and no "Unknown notification" line.
     */
    private void assertInfoLevel(List<String> stderr) {
        for (String line : NO_PROVIDER_LINES) {
            assertFalse(stderr.contains(line), "unexpected '" + line + "' in " + stderr);
        }
        assertTrue(stderr.stream().noneMatch(line -> line.startsWith("SLF4J(")), "SLF4J report in " + stderr);

        assertInOrder(stderr, loadingProjectLine, mavenWarningLine, loadWarningLine);
        assertEquals(List.of(mavenWarningLine, loadWarningLine),
            stderr.stream().filter(line -> line.startsWith("[main] WARN ") || line.startsWith("[main] ERROR ")).toList());

        // The first SPI Fly line starts with the record's time, so only its end is checked.
        int message = stderr.indexOf(SPI_FLY_MESSAGE);
        assertTrue(message > 0, "'" + SPI_FLY_MESSAGE + "' missing from " + stderr);
        assertTrue(stderr.get(message - 1).endsWith(SPI_FLY_SOURCE_SUFFIX),
            "line before the SPI Fly message: " + stderr.get(message - 1));

        assertTrue(stderr.stream().noneMatch(line -> line.contains("Unknown notification")),
            "unknown notification logged in " + stderr);
    }

    private static void assertInOrder(List<String> lines, String... expected) {
        Iterator<String> remaining = lines.iterator();
        List<String> found = new ArrayList<>();
        for (String want : expected) {
            boolean seen = false;
            while (remaining.hasNext()) {
                if (remaining.next().equals(want)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                fail("'" + want + "' missing after " + found + " in " + lines);
            }
            found.add(want);
        }
    }

    /**
     * stdout holds exactly the three responses, ids 1, 2 and 3, and nothing else; the load
     * succeeded with exactly two warnings, BUILD_TOOL_UNAVAILABLE and the static read's
     * BUILD_MODEL_STATIC.
     */
    private static void assertJsonRpcOnly(ProductProcess.Output out) throws IOException {
        assertEquals(0, out.exitCode(), "exit code");
        assertEquals(3, out.stdout().size(), "stdout lines: " + out.stdout());
        for (int i = 0; i < 3; i++) {
            JsonNode response = JSON.readTree(out.stdout().get(i));
            List<String> fields = new ArrayList<>();
            response.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("jsonrpc", "id", "result"), fields, "fields of " + out.stdout().get(i));
            assertEquals("2.0", response.get("jsonrpc").asText());
            assertTrue(response.get("id").isInt(), "id of " + out.stdout().get(i));
            assertEquals(i + 1, response.get("id").asInt());
        }
        JsonNode load = JSON.readTree(JSON.readTree(out.stdout().get(1))
            .path("result").path("content").path(0).path("text").asText());
        assertTrue(load.path("success").asBoolean(), "load_project: " + load);
        List<String> warningCodes = new ArrayList<>();
        load.path("data").path("warnings").forEach(warning -> warningCodes.add(warning.path("code").asText()));
        assertEquals(List.of("BUILD_TOOL_UNAVAILABLE", "BUILD_MODEL_STATIC"), warningCodes, "load_project: " + load);
    }

    /**
     * The message the JVM gives when {@code binary} cannot start in {@code directory}; the
     * server logs it verbatim. Its wording belongs to the JDK and the operating system.
     */
    private static String startFailure(Path binary, Path directory) {
        try {
            Process started = new ProcessBuilder(binary.toString(), "dependency:build-classpath")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
            started.destroyForcibly();
            throw new AssertionError(binary + " started although it does not exist");
        } catch (IOException expected) {
            return expected.getMessage();
        }
    }
}
