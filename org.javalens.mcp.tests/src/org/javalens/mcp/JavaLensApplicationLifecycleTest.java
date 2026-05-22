package org.javalens.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle invariants for {@link JavaLensApplication}'s shutdown path.
 *
 * <p>The message loop is single-threaded — tool invocations are dispatched
 * sequentially from stdin. {@link JavaLensApplication#stop()} sets a volatile
 * {@code running} flag; the loop re-checks it at the top of every iteration.
 * A tool call already in progress when {@code stop()} fires completes
 * naturally and writes its response before the loop exits — there is no
 * mid-call cancellation surface, so half-written tool output is not a
 * reachable state.
 *
 * <p>JDT workspace metadata is persisted transactionally by Eclipse's
 * resource framework. JavaLens itself does not write workspace metadata
 * outside JDT's APIs, so the "half-written workspace metadata" concern in
 * the C2-2 plan item has no path to occur from JavaLens code.
 */
class JavaLensApplicationLifecycleTest {

    @Test
    @DisplayName("stop() sets the running flag to false")
    void stop_setsRunningFalse() throws Exception {
        JavaLensApplication app = new JavaLensApplication();
        assertTrue(readRunning(app),
            "running must be true on a freshly constructed application");

        app.stop();

        assertFalse(readRunning(app),
            "stop() must set running=false so the message loop exits at its next iteration boundary");
    }

    @Test
    @DisplayName("stop() is idempotent — second call does not throw or flip the flag back")
    void stop_isIdempotent() throws Exception {
        JavaLensApplication app = new JavaLensApplication();
        app.stop();
        app.stop(); // must not throw or change state
        assertFalse(readRunning(app),
            "running must stay false across repeated stop() calls");
    }

    @Test
    @DisplayName("Two application instances have independent running flags (no shared state)")
    void runningFlag_isInstanceScoped() throws Exception {
        JavaLensApplication a = new JavaLensApplication();
        JavaLensApplication b = new JavaLensApplication();

        a.stop();

        assertFalse(readRunning(a), "a.running must be false after a.stop()");
        assertTrue(readRunning(b),
            "b.running must remain true — running is per-instance, not static");
    }

    @Test
    @DisplayName("Loading state is independent per instance (constructor default is NOT_LOADED)")
    void loadingState_perInstanceDefault() throws Exception {
        JavaLensApplication a = new JavaLensApplication();
        JavaLensApplication b = new JavaLensApplication();
        assertEquals(ProjectLoadingState.NOT_LOADED, readLoadingState(a));
        assertEquals(ProjectLoadingState.NOT_LOADED, readLoadingState(b));
    }

    @Test
    @DisplayName("Per-session state fields (running, loadingState, jdtService, loadingError) are all instance fields, not static")
    void perSessionState_isAllInstanceScoped() throws Exception {
        // Per-JVM JavaLens is a singleton, but the instance fields MUST be non-static so
        // that — if anyone ever instantiates two apps in one JVM — they don't silently
        // corrupt each other's state. The static `instance` field is intentionally shared
        // (it routes static getLoadingState() calls); everything else is per-instance.
        for (String fieldName : new String[]{"running", "loadingState", "jdtService", "loadingError"}) {
            Field f = JavaLensApplication.class.getDeclaredField(fieldName);
            assertFalse(java.lang.reflect.Modifier.isStatic(f.getModifiers()),
                fieldName + " must be an instance field, not static");
        }

        // Sanity: starting fresh, both instances report the constructor defaults independently.
        JavaLensApplication a = new JavaLensApplication();
        JavaLensApplication b = new JavaLensApplication();
        assertNull(readJdtService(a), "a.jdtService must start null");
        assertNull(readJdtService(b), "b.jdtService must start null");
        assertEquals(ProjectLoadingState.NOT_LOADED, readLoadingState(a));
        assertEquals(ProjectLoadingState.NOT_LOADED, readLoadingState(b));
    }

    @Test
    @DisplayName("Stopping one instance does not change the other instance's loading state")
    void stop_oneInstance_otherLoadingStateUnaffected() throws Exception {
        JavaLensApplication a = new JavaLensApplication();
        JavaLensApplication b = new JavaLensApplication();

        // Force b into LOADING via reflection — simulate b being in mid-load when a stops.
        Field stateField = JavaLensApplication.class.getDeclaredField("loadingState");
        stateField.setAccessible(true);
        stateField.set(b, ProjectLoadingState.LOADING);

        a.stop();

        assertEquals(ProjectLoadingState.LOADING, readLoadingState(b),
            "b's loadingState must be unaffected when a is stopped");
        assertTrue(readRunning(b),
            "b's running flag must be unaffected when a is stopped");
    }

    private static boolean readRunning(JavaLensApplication app) throws Exception {
        Field f = JavaLensApplication.class.getDeclaredField("running");
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    private static ProjectLoadingState readLoadingState(JavaLensApplication app) throws Exception {
        Field f = JavaLensApplication.class.getDeclaredField("loadingState");
        f.setAccessible(true);
        return (ProjectLoadingState) f.get(app);
    }

    private static Object readJdtService(JavaLensApplication app) throws Exception {
        Field f = JavaLensApplication.class.getDeclaredField("jdtService");
        f.setAccessible(true);
        return f.get(app);
    }
}
