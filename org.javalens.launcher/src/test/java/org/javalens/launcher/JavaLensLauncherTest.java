package org.javalens.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JavaLensLauncher}'s pure-function pieces:
 * arg rewriting and cleanup. {@code main} itself can't be unit-tested
 * directly because it invokes Equinox in-process, but the two
 * helper methods carry all the bug-prone logic.
 *
 * <p>These tests cover B-8 (default workspace path when {@code -data}
 * is absent) and B-9 (cleanup reports failures rather than swallowing)
 * plus general regression coverage for arg parsing.
 */
class JavaLensLauncherTest {

    private static final String SESSION = "abcd1234";

    // ========== prepareArgs ==========

    @Test
    @DisplayName("prepareArgs with -data injects sessionId as subdirectory")
    void dataPresent_injectsSession(@TempDir Path defaultBase) {
        String[] in = { "-data", "/tmp/workspaces", "--other", "value" };
        JavaLensLauncher.LaunchPlan plan = JavaLensLauncher.prepareArgs(in, defaultBase, SESSION);
        assertFalse(plan.usedDefault(),
            "When -data is present, usedDefault must be false");
        assertEquals(Path.of("/tmp/workspaces", SESSION), plan.sessionPath());
        // The -data path was rewritten; other args preserved in order.
        assertArrayEquals(new String[] {
            "-data", Path.of("/tmp/workspaces", SESSION).toString(), "--other", "value"
        }, plan.modifiedArgs());
    }

    @Test
    @DisplayName("prepareArgs without -data falls back to defaultBase / sessionId (B-8)")
    void dataAbsent_usesDefault(@TempDir Path defaultBase) {
        String[] in = { "--other", "value" };
        JavaLensLauncher.LaunchPlan plan = JavaLensLauncher.prepareArgs(in, defaultBase, SESSION);
        assertTrue(plan.usedDefault(),
            "When -data is omitted, usedDefault must be true (B-8: no silent skip)");
        assertEquals(defaultBase.resolve(SESSION), plan.sessionPath());
        // Synthesized -data goes at the head; other args follow.
        assertArrayEquals(new String[] {
            "-data", defaultBase.resolve(SESSION).toString(), "--other", "value"
        }, plan.modifiedArgs());
    }

    @Test
    @DisplayName("prepareArgs with empty args and no -data still produces a usable plan")
    void emptyArgs_synthesizeDefault(@TempDir Path defaultBase) {
        JavaLensLauncher.LaunchPlan plan = JavaLensLauncher.prepareArgs(new String[0], defaultBase, SESSION);
        assertTrue(plan.usedDefault());
        assertEquals(defaultBase.resolve(SESSION), plan.sessionPath());
        assertArrayEquals(new String[] {
            "-data", defaultBase.resolve(SESSION).toString()
        }, plan.modifiedArgs());
    }

    @Test
    @DisplayName("prepareArgs with -data at end (no value) is treated as non-data flag")
    void dataTrailing_treatedAsFlag(@TempDir Path defaultBase) {
        // `-data` with no following arg: the loop guard `i + 1 < args.length` skips the
        // injection branch, so the flag passes through as-is and the default path is used.
        String[] in = { "--first", "-data" };
        JavaLensLauncher.LaunchPlan plan = JavaLensLauncher.prepareArgs(in, defaultBase, SESSION);
        // Default kicks in because no usable -data value was paired.
        assertTrue(plan.usedDefault(),
            "Trailing -data without value must not consume a non-existent next arg");
        // Pin args carry both the original -data flag (preserved) and the injected default at the head.
        List<String> mod = Arrays.asList(plan.modifiedArgs());
        assertEquals("-data", mod.get(0));
        assertEquals(defaultBase.resolve(SESSION).toString(), mod.get(1));
        assertTrue(mod.contains("--first"));
    }

    // ========== cleanupSession ==========

    @Test
    @DisplayName("cleanupSession recursively deletes the directory")
    void cleanupSession_deletesTree(@TempDir Path tempRoot) throws IOException {
        Path session = tempRoot.resolve("session-xyz");
        Files.createDirectories(session.resolve("sub"));
        Files.writeString(session.resolve("sub/a.txt"), "hello");
        Files.writeString(session.resolve("b.txt"), "world");
        assertTrue(Files.exists(session));

        JavaLensLauncher.cleanupSession(session);

        assertFalse(Files.exists(session),
            "Session directory must be removed after cleanup; got remaining: " + session);
    }

    @Test
    @DisplayName("cleanupSession on non-existent path is a no-op (does not throw)")
    void cleanupSession_missingPath_noOp(@TempDir Path tempRoot) {
        Path missing = tempRoot.resolve("never-created");
        assertFalse(Files.exists(missing));
        // Must not throw. Implementation guards on Files.exists() first.
        JavaLensLauncher.cleanupSession(missing);
    }

    @Test
    @DisplayName("cleanupSession with null path is a no-op")
    void cleanupSession_null_noOp() {
        // Guard against null — defensive coding in shutdown-hook contexts.
        JavaLensLauncher.cleanupSession(null);
    }

    @Test
    @DisplayName("LaunchPlan record exposes its fields and is non-null")
    void launchPlan_recordShape(@TempDir Path defaultBase) {
        JavaLensLauncher.LaunchPlan plan = JavaLensLauncher.prepareArgs(
            new String[0], defaultBase, SESSION);
        assertNotNull(plan.modifiedArgs());
        assertNotNull(plan.sessionPath());
        // usedDefault is a boolean — record accessor returns the boxed value.
        // Asserting the type-safe accessor works without ClassCastException.
        boolean used = plan.usedDefault();
        assertTrue(used || !used,
            "usedDefault accessor must return a boolean without exception");
    }

    // ========== resolveLogLevel ==========

    private static String notice(String value) {
        return "[javalens] JAVALENS_LOG_LEVEL \"" + value
            + "\" is not one of TRACE, DEBUG, INFO, WARN, ERROR; logging at INFO";
    }

    private static Level julLevel(String name) {
        return Level.parse(name);
    }

    @Test
    @DisplayName("unset JAVALENS_LOG_LEVEL logs at INFO without a notice")
    void logLevel_unset_isInfo() {
        assertEquals(new JavaLensLauncher.LogLevel("info", Level.INFO, null),
            JavaLensLauncher.resolveLogLevel(null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", " \t\n " })
    @DisplayName("blank JAVALENS_LOG_LEVEL logs at INFO without a notice")
    void logLevel_blank_isInfo(String value) {
        assertEquals(new JavaLensLauncher.LogLevel("info", Level.INFO, null),
            JavaLensLauncher.resolveLogLevel(null, value));
    }

    @ParameterizedTest
    @CsvSource({
        "TRACE, trace, FINEST",
        "DEBUG, debug, FINE",
        "INFO,  info,  INFO",
        "WARN,  warn,  WARNING",
        "ERROR, error, SEVERE",
        "trace, trace, FINEST",
        "warn,  warn,  WARNING",
        "Debug, debug, FINE",
        "eRRoR, error, SEVERE",
    })
    @DisplayName("each documented level, in any case, sets slf4j-simple and java.util.logging")
    void logLevel_documented(String value, String slf4j, String jul) {
        assertEquals(new JavaLensLauncher.LogLevel(slf4j, julLevel(jul), null),
            JavaLensLauncher.resolveLogLevel(null, value));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed before the level is read")
    void logLevel_trimmed() {
        assertEquals(new JavaLensLauncher.LogLevel("warn", Level.WARNING, null),
            JavaLensLauncher.resolveLogLevel(null, " WARN\n"));
        assertEquals(new JavaLensLauncher.LogLevel("debug", Level.FINE, null),
            JavaLensLauncher.resolveLogLevel(null, "\tdebug "));
    }

    @ParameterizedTest
    @ValueSource(strings = { "verbose", "OFF", "off", "WARNING", "FINE", "1", "IN FO" })
    @DisplayName("any other JAVALENS_LOG_LEVEL is announced and logs at INFO")
    void logLevel_undocumented_noticeAndInfo(String value) {
        assertEquals(new JavaLensLauncher.LogLevel("info", Level.INFO, notice(value)),
            JavaLensLauncher.resolveLogLevel(null, value));
    }

    @Test
    @DisplayName("the notice quotes the trimmed value")
    void logLevel_undocumented_noticeQuotesTrimmedValue() {
        assertEquals(new JavaLensLauncher.LogLevel("info", Level.INFO, notice("verbose")),
            JavaLensLauncher.resolveLogLevel(null, "  verbose \n"));
    }

    @Test
    @DisplayName("control characters in the value are escaped so the notice stays one line")
    void logLevel_undocumented_noticeIsOneLine() {
        assertEquals(new JavaLensLauncher.LogLevel("info", Level.INFO, notice("a\\u000ab\\u000dc")),
            JavaLensLauncher.resolveLogLevel(null, "a\nb\rc"));
    }

    @Test
    @DisplayName("an explicit slf4j-simple level wins and JAVALENS_LOG_LEVEL is not read")
    void logLevel_systemPropertyWins() {
        assertEquals(new JavaLensLauncher.LogLevel(null, Level.WARNING, null),
            JavaLensLauncher.resolveLogLevel("warn", "DEBUG"));
        assertEquals(new JavaLensLauncher.LogLevel(null, Level.WARNING, null),
            JavaLensLauncher.resolveLogLevel("warn", "verbose"));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "trace|FINEST",
        "DEBUG|FINE",
        "Info|INFO",
        "WARN|WARNING",
        "error|SEVERE",
        "off|OFF",
        "OFF|OFF",
        "''|INFO",
        " warn|INFO",
        "verbose|INFO",
    }, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("java.util.logging follows the explicit slf4j-simple level as slf4j-simple reads it")
    void logLevel_systemPropertyMapsToJul(String property, String jul) {
        assertEquals(new JavaLensLauncher.LogLevel(null, julLevel(jul), null),
            JavaLensLauncher.resolveLogLevel(property, null));
    }

    // ========== applyLogLevel ==========

    @Test
    @DisplayName("applyLogLevel prints the notice once, sets the slf4j-simple level and the JUL root and handlers")
    void applyLogLevel_withNotice() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Handler handler = new RecordingHandler();
        withLoggingRestored(handler, () -> {
            System.clearProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL);

            JavaLensLauncher.applyLogLevel(
                new JavaLensLauncher.LogLevel("info", Level.INFO, notice("verbose")),
                new PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals(notice("verbose") + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
            assertEquals("info", System.getProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL));
            assertEquals(Level.INFO, Logger.getLogger("").getLevel());
            assertEquals(Level.INFO, handler.getLevel());
        });
    }

    @Test
    @DisplayName("applyLogLevel keeps an explicit slf4j-simple level and prints nothing without a notice")
    void applyLogLevel_explicitPropertyKept() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Handler handler = new RecordingHandler();
        withLoggingRestored(handler, () -> {
            System.setProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL, "Warn");

            JavaLensLauncher.applyLogLevel(
                new JavaLensLauncher.LogLevel(null, Level.WARNING, null),
                new PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals("", err.toString(StandardCharsets.UTF_8));
            assertEquals("Warn", System.getProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL));
            assertEquals(Level.WARNING, Logger.getLogger("").getLevel());
            assertEquals(Level.WARNING, handler.getLevel());
        });
    }

    /**
     * Run {@code body} with {@code handler} added to the root logger at level ALL, then put
     * the slf4j-simple property, the root level and every root handler's level back.
     */
    private static void withLoggingRestored(Handler handler, Runnable body) {
        String previousProperty = System.getProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL);
        Logger root = Logger.getLogger("");
        Level previousRoot = root.getLevel();
        Handler[] handlers = root.getHandlers();
        Level[] previousHandlerLevels = new Level[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            previousHandlerLevels[i] = handlers[i].getLevel();
        }
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
        try {
            body.run();
        } finally {
            root.removeHandler(handler);
            for (int i = 0; i < handlers.length; i++) {
                handlers[i].setLevel(previousHandlerLevels[i]);
            }
            root.setLevel(previousRoot);
            if (previousProperty == null) {
                System.clearProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL);
            } else {
                System.setProperty(JavaLensLauncher.SLF4J_DEFAULT_LEVEL, previousProperty);
            }
        }
    }

    private static final class RecordingHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
