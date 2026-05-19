package org.javalens.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
 */
public class JavaLensLauncher {

    public static void main(String[] args) throws Exception {
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
