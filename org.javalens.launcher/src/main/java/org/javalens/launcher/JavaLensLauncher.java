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
 * This solves the race condition where multiple MCP instances starting
 * simultaneously would conflict on workspace locking. By generating a
 * unique UUID subdirectory for each session BEFORE OSGi starts, each
 * instance gets its own isolated workspace.
 *
 * Usage: java -jar javalens.jar -data /path/to/workspaces [other args]
 *
 * The -data path will be modified to: /path/to/workspaces/{uuid}/
 *
 * Cleanup: On shutdown, the current session directory is cleaned up via
 * shutdown hook. Only the current session's directory is deleted - other
 * concurrent sessions are not affected.
 */
public class JavaLensLauncher {

    public static void main(String[] args) throws Exception {
        // Generate unique session ID (8 hex chars)
        String sessionId = UUID.randomUUID().toString().substring(0, 8);

        List<String> modifiedArgs = new ArrayList<>();
        Path sessionPath = null;

        for (int i = 0; i < args.length; i++) {
            if ("-data".equals(args[i]) && i + 1 < args.length) {
                Path basePath = Path.of(args[i + 1]);

                // Create new session directory with unique UUID
                sessionPath = basePath.resolve(sessionId);
                Files.createDirectories(sessionPath);

                // Register shutdown hook to clean up this session only
                final Path pathToClean = sessionPath;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    cleanupSession(pathToClean);
                }));

                modifiedArgs.add("-data");
                modifiedArgs.add(sessionPath.toString());
                i++; // Skip the original path argument
            } else {
                modifiedArgs.add(args[i]);
            }
        }

        if (sessionPath == null) {
            System.err.println("Warning: No -data argument provided. Session isolation disabled.");
        }

        // Delegate to Equinox launcher (runs in same JVM, not a subprocess)
        org.eclipse.equinox.launcher.Main.main(modifiedArgs.toArray(new String[0]));
    }

    /**
     * Recursively delete a session directory.
     * Only called for this session's own directory via shutdown hook.
     */
    private static void cleanupSession(Path sessionPath) {
        if (sessionPath == null || !Files.exists(sessionPath)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(sessionPath)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Ignore deletion errors - best effort
                    }
                });
        } catch (IOException e) {
            // Ignore cleanup errors - best effort
        }
    }
}
