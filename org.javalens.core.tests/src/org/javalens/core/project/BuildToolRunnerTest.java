package org.javalens.core.project;

import org.javalens.core.project.BuildToolRunner.Outcome;
import org.javalens.core.project.BuildToolRunner.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins how {@link BuildToolRunner} runs a build-tool process and reports how it ended.
 *
 * <p>Each process is a small program run by the running JDK's launcher in single-file
 * source mode, so it behaves the same on every OS: the program decides what it writes, to
 * which stream, how long it runs and its exit code. Expected output and exit codes are
 * read off the program text.
 */
class BuildToolRunnerTest {

    /** A time limit no program that exits on its own comes near. */
    private static final Duration AMPLE = Duration.ofSeconds(60);

    @TempDir
    Path dir;

    @Test
    @DisplayName("A command whose executable does not exist is UNAVAILABLE with exit code -1, no output and the start failure")
    void missingExecutableIsUnavailable() {
        Result result = BuildToolRunner.run(List.of(dir.resolve("no-such-tool").toString()), dir, AMPLE);

        assertAll(
            () -> assertEquals(Outcome.UNAVAILABLE, result.outcome(), "outcome"),
            () -> assertEquals(-1, result.exitCode(), "exit code"),
            () -> assertEquals(List.of(), result.retainedLines(), "retained lines"),
            () -> assertEquals("", result.lastLines(), "last lines"),
            // The start failure is the OS's own process-start error text, which differs per
            // OS and locale, so only its presence is pinned.
            () -> assertFalse(result.startFailure().isEmpty(), "start failure is reported"));
    }

    @Test
    @DisplayName("A process that exits is EXITED with its exit code and every line it wrote, in order")
    void exitedProcessReportsExitCodeAndLines() throws IOException {
        Result result = run(program("ThreeLines", """
            System.out.println("one");
            System.out.println("two");
            System.out.println("three");
            System.exit(3);
            """), AMPLE);

        assertAll(
            () -> assertEquals(new Result(Outcome.EXITED, 3, null, List.of("one", "two", "three")), result, "result"),
            () -> assertEquals("one | two | three", result.lastLines(), "last lines"));
    }

    @Test
    @DisplayName("Of 300 lines written, exactly the last 200 are retained and lastLines reports the last five")
    void retainsOnlyTheLastTwoHundredLines() throws IOException {
        Result result = run(program("ThreeHundredLines", """
            for (int i = 1; i <= 300; i++) {
                System.out.println("line " + i);
            }
            System.exit(1);
            """), AMPLE);

        List<String> lastTwoHundred = IntStream.rangeClosed(101, 300).mapToObj(i -> "line " + i).toList();
        assertAll(
            () -> assertEquals(new Result(Outcome.EXITED, 1, null, lastTwoHundred), result, "result"),
            () -> assertEquals("line 296 | line 297 | line 298 | line 299 | line 300", result.lastLines(),
                "last lines"));
    }

    @Test
    @DisplayName("stderr is merged into stdout in the order the process wrote them")
    void stderrIsMergedIntoStdout() throws IOException {
        Result result = run(program("OutThenErr", """
            System.out.println("out");
            System.out.flush();
            System.err.println("err");
            System.err.flush();
            """), AMPLE);

        assertEquals(new Result(Outcome.EXITED, 0, null, List.of("out", "err")), result);
    }

    @Test
    @Timeout(60)
    @DisplayName("A process that writes a line and then stays silent past the time limit is TIMED_OUT, keeps that line, and is no longer alive")
    void silentAfterWritingIsTimedOutAndDestroyed() throws IOException {
        // The limit leaves the launcher ample time to start and print before it fires, and
        // ends long before the program's sleep would.
        Result result = run(program("PidThenSleep", """
            System.out.println(ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(60_000);
            """), Duration.ofSeconds(10));

        // The program's one line is its own process id.
        assertEquals(1, result.retainedLines().size(), () -> "retained lines: " + result.retainedLines());
        long pid = Long.parseLong(result.retainedLines().get(0));
        assertAll(
            () -> assertEquals(new Result(Outcome.TIMED_OUT, -1, null, List.of(Long.toString(pid))), result,
                "result"),
            () -> assertEquals(false, ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "process alive after the run"));
    }

    @Test
    @Timeout(60)
    @DisplayName("A process that writes nothing and runs past the time limit is TIMED_OUT with no output")
    void silentProcessIsTimedOut() throws IOException {
        Result result = run(program("SilentSleep", """
            Thread.sleep(60_000);
            """), Duration.ofSeconds(2));

        assertEquals(new Result(Outcome.TIMED_OUT, -1, null, List.of()), result);
    }

    @Test
    @Timeout(60)
    @DisplayName("A run whose calling thread is interrupted is INTERRUPTED with no output, and the interrupt is restored on the calling thread")
    void interruptedCallerIsReportedAndInterruptRestored() throws IOException {
        // Written before the interrupt is set, since an interrupt closes interruptible file
        // channels. The program writes nothing, so nothing can be retained.
        Path program = program("SilentSleepUntilInterrupted", """
            Thread.sleep(60_000);
            """);

        Thread.currentThread().interrupt();
        Result result;
        boolean interruptedAfterRun;
        try {
            result = run(program, AMPLE);
        } finally {
            // Read and clear the flag so it does not leak into later tests.
            interruptedAfterRun = Thread.interrupted();
        }

        assertAll(
            () -> assertEquals(new Result(Outcome.INTERRUPTED, -1, null, List.of()), result, "result"),
            () -> assertEquals(true, interruptedAfterRun, "interrupt restored on the calling thread"));
    }

    @Test
    @DisplayName("A line longer than 4096 characters is retained as its first 4096 characters followed by ' [line truncated]'")
    void overlongLineIsTruncated() throws IOException {
        Result result = run(program("LongLineThenEnd", """
            System.out.println("x".repeat(10_000));
            System.out.println("end");
            """), AMPLE);

        assertEquals(new Result(Outcome.EXITED, 0, null, List.of("x".repeat(4096) + " [line truncated]", "end")),
            result);
    }

    @Test
    @DisplayName("Lines ended by \\r\\n, \\r or \\n, and a last line with no terminator, are each retained once without terminator characters")
    void everyLineTerminatorEndsALine() throws IOException {
        // The pause between "split\r" and its "\n" lets the two arrive in separate reads,
        // so a \r\n pair split across reads still ends one line.
        Result result = run(program("MixedTerminators", """
            System.out.print("crlf\\r\\ncr\\rlf\\n\\r\\nsplit\\r");
            System.out.flush();
            Thread.sleep(200);
            System.out.print("\\nlast");
            System.out.flush();
            """), AMPLE);

        assertEquals(new Result(Outcome.EXITED, 0, null, List.of("crlf", "cr", "lf", "", "split", "last")), result);
    }

    @Test
    @DisplayName("lastLines reports the last five non-blank retained lines, each trimmed, joined with ' | '")
    void lastLinesSkipsBlankLinesAndTrims() {
        Result result = new Result(Outcome.EXITED, 1, null,
            List.of("  alpha  ", "", "beta", "   ", "gamma", "delta", "\tepsilon", "zeta  ", ""));

        assertEquals("beta | gamma | delta | epsilon | zeta", result.lastLines());
    }

    @Test
    @DisplayName("lastLines is empty when every retained line is blank")
    void lastLinesEmptyWhenEveryLineIsBlank() {
        Result result = new Result(Outcome.EXITED, 1, null, List.of("", "   ", "\t"));

        assertEquals("", result.lastLines());
    }

    // ========== Helpers ==========

    /** Run {@code program} with the running JDK's launcher in single-file source mode. */
    private Result run(Path program, Duration timeout) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return BuildToolRunner.run(List.of(java.toString(), program.toString()), dir, timeout);
    }

    /** Write a class named {@code className} whose {@code main} runs {@code body}. */
    private Path program(String className, String body) throws IOException {
        Path file = dir.resolve(className + ".java");
        Files.writeString(file, "public class " + className + " {\n"
            + "    public static void main(String[] args) throws Exception {\n"
            + body.indent(8)
            + "    }\n"
            + "}\n");
        return file;
    }
}
