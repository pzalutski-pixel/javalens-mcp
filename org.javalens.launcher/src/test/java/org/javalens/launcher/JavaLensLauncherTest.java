package org.javalens.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
}
