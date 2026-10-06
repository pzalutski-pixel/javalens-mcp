package org.javalens.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Wrapper launcher that injects a session UUID into the workspace path
 * before delegating to the Equinox launcher.
 *
 * <p>This solves the race condition where multiple MCP instances starting
 * simultaneously would conflict on workspace locking. By generating a
 * unique UUID subdirectory for each session BEFORE OSGi starts, each
 * instance gets its own isolated workspace.
 *
 * <p>Usage: {@code java -jar javalens.jar -data /path/to/workspaces [other args]}
 *
 * <p>The {@code -data} path is rewritten to {@code /path/to/workspaces/{uuid}/}.
 * If {@code -data} is omitted the launcher falls back to
 * {@code ${user.home}/.javalens/workspaces/{uuid}/} so a missing argument
 * still produces an isolated workspace instead of dropping into Equinox's
 * implementation-defined default.
 *
 * <p>Cleanup: On shutdown, the current session directory is cleaned up via
 * shutdown hook. Only the current session's directory is deleted - other
 * concurrent sessions are not affected. Cleanup failures (e.g. files
 * locked by another process) are reported to stderr so the user can
 * diagnose disk-growth issues; previously they were swallowed silently.
 *
 * <p>Log level: before OSGi starts, the launcher sets the level of all stderr log output
 * from {@code JAVALENS_LOG_LEVEL}, see {@link #resolveLogLevel}.
 */
public class JavaLensLauncher {

    /** The system property slf4j-simple reads its default level from. */
    static final String SLF4J_DEFAULT_LEVEL = "org.slf4j.simpleLogger.defaultLogLevel";

    /** The environment variable that sets the log level. */
    static final String LOG_LEVEL_VARIABLE = "JAVALENS_LOG_LEVEL";

    /** The values JAVALENS_LOG_LEVEL accepts, compared without case. */
    private static final List<String> LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public static void main(String[] args) throws Exception {
        applyLogLevel(resolveLogLevel(System.getProperty(SLF4J_DEFAULT_LEVEL), System.getenv(LOG_LEVEL_VARIABLE)),
            System.err);

        Path defaultBase = Path.of(System.getProperty("user.home"), ".javalens", "workspaces");
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        LaunchPlan plan = prepareArgs(args, defaultBase, sessionId);

        Files.createDirectories(plan.sessionPath());

        if (plan.usedDefault()) {
            System.err.println("[javalens] No -data argument provided; using default workspace at "
                + plan.sessionPath());
        }

        final Path pathToClean = plan.sessionPath();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanupSession(pathToClean)));

        org.eclipse.equinox.launcher.Main.main(plan.modifiedArgs());
    }

    /**
     * Bundle of values returned by {@link #prepareArgs}: the rewritten arg array, the
     * session-specific workspace path the launcher will create, and whether the path
     * was synthesized (no {@code -data} was supplied by the caller).
     */
    public record LaunchPlan(String[] modifiedArgs, Path sessionPath, boolean usedDefault) {}

    /**
     * Pure-function arg rewrite. Splits out from {@link #main} so the rewrite can be
     * unit-tested without touching the real filesystem or invoking Equinox.
     *
     * @param args        the raw command-line arguments
     * @param defaultBase fallback workspace root when {@code -data} is omitted
     * @param sessionId   session UUID slice (typically 8 hex chars) — passed in
     *                    rather than generated internally so tests are deterministic
     */
    public static LaunchPlan prepareArgs(String[] args, Path defaultBase, String sessionId) {
        List<String> modifiedArgs = new ArrayList<>();
        Path sessionPath = null;

        for (int i = 0; i < args.length; i++) {
            if ("-data".equals(args[i]) && i + 1 < args.length) {
                Path basePath = Path.of(args[i + 1]);
                sessionPath = basePath.resolve(sessionId);
                modifiedArgs.add("-data");
                modifiedArgs.add(sessionPath.toString());
                i++;
            } else {
                modifiedArgs.add(args[i]);
            }
        }

        boolean usedDefault = sessionPath == null;
        if (usedDefault) {
            sessionPath = defaultBase.resolve(sessionId);
            modifiedArgs.add(0, "-data");
            modifiedArgs.add(1, sessionPath.toString());
        }

        return new LaunchPlan(modifiedArgs.toArray(new String[0]), sessionPath, usedDefault);
    }

    /**
     * The level JavaLens logs at.
     *
     * @param slf4jLevel the value to set as slf4j-simple's default level, or null when an
     *                   explicit system property already sets it
     * @param julLevel   the matching java.util.logging level
     * @param notice     the line to print on stderr before starting, or null
     */
    public record LogLevel(String slf4jLevel, Level julLevel, String notice) {}

    /**
     * Pure-function log level resolution. An explicit {@code -Dorg.slf4j.simpleLogger.defaultLogLevel}
     * wins: slf4j-simple reads it itself, java.util.logging follows it as slf4j-simple reads
     * it, and JAVALENS_LOG_LEVEL is not consulted. Otherwise JAVALENS_LOG_LEVEL is trimmed;
     * unset or blank means INFO; TRACE, DEBUG, INFO, WARN or ERROR, compared without case,
     * sets that level; any other value logs at INFO with a one-line notice.
     *
     * @param slf4jProperty the value of {@value #SLF4J_DEFAULT_LEVEL}, or null when unset
     * @param variable      the value of JAVALENS_LOG_LEVEL, or null when unset
     */
    public static LogLevel resolveLogLevel(String slf4jProperty, String variable) {
        if (slf4jProperty != null) {
            return new LogLevel(null, julLevel(slf4jProperty), null);
        }
        String value = variable == null ? "" : variable.strip();
        if (value.isEmpty()) {
            return new LogLevel("info", Level.INFO, null);
        }
        for (String level : LOG_LEVELS) {
            if (level.equalsIgnoreCase(value)) {
                String name = level.toLowerCase(Locale.ROOT);
                return new LogLevel(name, julLevel(name), null);
            }
        }
        return new LogLevel("info", Level.INFO, "[javalens] " + LOG_LEVEL_VARIABLE + " \"" + oneLine(value)
            + "\" is not one of TRACE, DEBUG, INFO, WARN, ERROR; logging at INFO");
    }

    /**
     * The java.util.logging level for an slf4j-simple level name, read the way slf4j-simple
     * reads it: case-insensitive names, OFF included, and INFO for anything else.
     */
    private static Level julLevel(String slf4jLevel) {
        if ("trace".equalsIgnoreCase(slf4jLevel)) {
            return Level.FINEST;
        } else if ("debug".equalsIgnoreCase(slf4jLevel)) {
            return Level.FINE;
        } else if ("info".equalsIgnoreCase(slf4jLevel)) {
            return Level.INFO;
        } else if ("warn".equalsIgnoreCase(slf4jLevel)) {
            return Level.WARNING;
        } else if ("error".equalsIgnoreCase(slf4jLevel)) {
            return Level.SEVERE;
        } else if ("off".equalsIgnoreCase(slf4jLevel)) {
            return Level.OFF;
        }
        return Level.INFO;
    }

    /** {@code value} with every control character written as a backslash, "u" and four hex digits. */
    private static String oneLine(String value) {
        StringBuilder text = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                text.append(String.format("\\u%04x", c));
            } else {
                text.appendCodePoint(c);
            }
        });
        return text.toString();
    }

    /**
     * Apply {@code level}: print its notice on {@code err}, set slf4j-simple's default level
     * unless an explicit property sets it, and set the java.util.logging root logger and
     * its handlers. The root logger is held by the LogManager for the life of the JVM, so
     * its level is never lost.
     */
    static void applyLogLevel(LogLevel level, PrintStream err) {
        if (level.notice() != null) {
            err.println(level.notice());
            err.flush();
        }
        if (level.slf4jLevel() != null) {
            System.setProperty(SLF4J_DEFAULT_LEVEL, level.slf4jLevel());
        }
        Logger root = Logger.getLogger("");
        root.setLevel(level.julLevel());
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(level.julLevel());
        }
    }

    /**
     * Recursively delete a session directory. Failures are aggregated and reported
     * to stderr at the end — never thrown, since this runs in a shutdown hook
     * where exceptions would be lost.
     */
    static void cleanupSession(Path sessionPath) {
        if (sessionPath == null || !Files.exists(sessionPath)) {
            return;
        }

        List<String> failed = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sessionPath)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        failed.add(p + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                    }
                });
        } catch (IOException e) {
            System.err.println("[javalens] Failed to walk session dir " + sessionPath
                + " for cleanup: " + e);
            return;
        }
        if (!failed.isEmpty()) {
            System.err.println("[javalens] Cleanup of " + sessionPath + " left "
                + failed.size() + " path(s) behind (likely concurrent access). Affected: " + failed);
        }
    }
}
