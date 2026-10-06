package org.javalens.core.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a build tool (Maven, Gradle) as a subprocess and reports how it ended. Every
 * build-tool process JavaLens starts is started here.
 *
 * <p>stderr is merged into stdout. A separate thread drains that output from the moment
 * the process starts, so the time limit applies whether the tool is writing or silent.
 * Only the last {@value #RETAINED_LINES} lines are kept, and of each line at most its first
 * {@value #MAX_LINE_LENGTH} characters, so memory stays bounded whatever the tool writes. A
 * process that does not exit within the limit, or whose caller is interrupted while
 * waiting, is destroyed forcibly together with every process it started.
 */
final class BuildToolRunner {

    private static final Logger log = LoggerFactory.getLogger(BuildToolRunner.class);

    /** How many lines of output a run keeps, counted back from the last one. */
    static final int RETAINED_LINES = 200;

    /**
     * How many characters of one output line a run keeps. The rest of a longer line is
     * discarded as it is read, and the kept part is followed by {@link #TRUNCATION_MARKER}.
     */
    static final int MAX_LINE_LENGTH = 4096;

    /** Follows the kept part of a line longer than {@value #MAX_LINE_LENGTH} characters. */
    static final String TRUNCATION_MARKER = " [line truncated]";

    /** How many non-blank lines {@link Result#lastLines()} reports. */
    private static final int LAST_LINES = 5;

    /**
     * How long to wait for a process's output to end once the process has exited, and for
     * a destroyed process to exit. A process the tool started can hold the output open
     * after the tool itself has exited, so the wait is bounded.
     */
    private static final Duration END_WAIT = Duration.ofSeconds(5);

    private BuildToolRunner() {
    }

    /** How a run ended. */
    enum Outcome {
        /** The process could not be started. */
        UNAVAILABLE,
        /** The process did not exit within the time limit and was destroyed. */
        TIMED_OUT,
        /**
         * The calling thread was interrupted while waiting for the process to exit. The
         * process was destroyed as for a timeout, and the interrupt is restored on the
         * calling thread.
         */
        INTERRUPTED,
        /** The process exited on its own. */
        EXITED
    }

    /**
     * How a run ended and the last lines the process wrote.
     *
     * @param outcome how the run ended
     * @param exitCode the process's exit code when {@link Outcome#EXITED}, otherwise -1
     * @param startFailure why the process could not be started when
     *     {@link Outcome#UNAVAILABLE}, otherwise null
     * @param retainedLines the last lines of the merged output, at most
     *     {@value BuildToolRunner#RETAINED_LINES}, oldest first, without terminators; a
     *     line longer than {@value BuildToolRunner#MAX_LINE_LENGTH} characters is its first
     *     {@value BuildToolRunner#MAX_LINE_LENGTH} followed by
     *     {@value BuildToolRunner#TRUNCATION_MARKER}
     */
    record Result(Outcome outcome, int exitCode, String startFailure, List<String> retainedLines) {

        Result {
            retainedLines = List.copyOf(retainedLines);
        }

        /**
         * The last {@value BuildToolRunner#LAST_LINES} non-blank retained lines, each
         * trimmed, joined with {@code " | "}; empty when no retained line is non-blank.
         */
        String lastLines() {
            List<String> nonBlank = retainedLines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
            return String.join(" | ", nonBlank.subList(Math.max(0, nonBlank.size() - LAST_LINES), nonBlank.size()));
        }
    }

    /**
     * Run {@code command} in {@code workingDirectory} and wait at most {@code timeout} for
     * it to exit. A process still running at the limit is destroyed together with its
     * descendants and reported as {@link Outcome#TIMED_OUT}. When the calling thread is
     * interrupted while waiting, the process is destroyed the same way, the run is
     * reported as {@link Outcome#INTERRUPTED}, and the interrupt is restored on the
     * calling thread.
     */
    static Result run(List<String> command, Path workingDirectory, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            return new Result(Outcome.UNAVAILABLE, -1, e.getMessage(), List.of());
        }

        OutputTail output = new OutputTail();
        Thread drain = Thread.ofPlatform()
            .name("javalens-build-tool-output-" + process.pid())
            .daemon()
            .start(() -> output.drain(process));

        boolean exited;
        try {
            exited = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            destroyTree(process, drain);
            Thread.currentThread().interrupt();
            return new Result(Outcome.INTERRUPTED, -1, null, output.lines());
        }
        if (!exited) {
            destroyTree(process, drain);
            return new Result(Outcome.TIMED_OUT, -1, null, output.lines());
        }

        awaitOutputEnd(drain);
        return new Result(Outcome.EXITED, process.exitValue(), null, output.lines());
    }

    /**
     * Destroy {@code process} and every process it started, forcibly, then wait up to
     * {@link #END_WAIT} for it to exit and for its output to end. The descendants are
     * listed before the process is destroyed, because a process whose parent has exited is
     * no longer listed as its descendant.
     */
    private static void destroyTree(Process process, Thread drain) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            if (!process.waitFor(END_WAIT.toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn("Build tool process {} did not exit within {} of being destroyed", process.pid(), END_WAIT);
            }
            awaitOutputEnd(drain);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Wait up to {@link #END_WAIT} for the drain thread to read the output to its end. */
    private static void awaitOutputEnd(Thread drain) {
        try {
            if (!drain.join(END_WAIT)) {
                log.warn("Build tool output was still open {} after the process ended; "
                    + "reporting the lines read until then", END_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The last {@value #RETAINED_LINES} lines of a process's output, filled by the drain
     * thread. Lines are split here rather than by {@link java.io.BufferedReader#readLine()},
     * which holds a whole line in memory, so at most {@value #MAX_LINE_LENGTH} characters
     * of the current line are ever held.
     */
    private static final class OutputTail {

        private final Deque<String> lines = new ArrayDeque<>(RETAINED_LINES);

        /** The current line's kept characters; touched only by the drain thread. */
        private final StringBuilder currentLine = new StringBuilder();

        /** Whether the current line ran past its kept characters; drain thread only. */
        private boolean currentLineTruncated;

        /**
         * Read the process's merged output to its end, keeping the last lines. As with
         * {@link java.io.BufferedReader#readLine()}, a line ends at {@code \n}, {@code \r}
         * or {@code \r\n}, and a last line with no terminator counts too.
         */
        void drain(Process process) {
            try (Reader reader = process.inputReader()) {
                char[] chunk = new char[8192];
                boolean afterCarriageReturn = false;
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    for (int i = 0; i < read; i++) {
                        char c = chunk[i];
                        // The \n of a \r\n pair, possibly arriving in the next read, ends
                        // nothing: the \r already ended the line.
                        boolean endsCarriageReturnPair = c == '\n' && afterCarriageReturn;
                        afterCarriageReturn = c == '\r';
                        if (endsCarriageReturnPair) {
                            continue;
                        }
                        if (c == '\n' || c == '\r') {
                            endLine();
                        } else if (currentLine.length() < MAX_LINE_LENGTH) {
                            currentLine.append(c);
                        } else {
                            currentLineTruncated = true;
                        }
                    }
                }
            } catch (IOException e) {
                // Destroying the process can close its output under the reader. The lines
                // read until then, and the part of the current line, are kept.
                log.debug("Build tool output ended with an error: {}", e.getMessage());
            }
            if (!currentLine.isEmpty()) {
                endLine();
            }
        }

        /** Retain the current line, marked when it was cut, and start the next one. */
        private void endLine() {
            add(currentLineTruncated ? currentLine + TRUNCATION_MARKER : currentLine.toString());
            currentLine.setLength(0);
            currentLineTruncated = false;
        }

        private synchronized void add(String line) {
            if (lines.size() == RETAINED_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized List<String> lines() {
            return List.copyOf(lines);
        }
    }
}
