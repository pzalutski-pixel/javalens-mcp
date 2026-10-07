package org.javalens.mcp.fixtures;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs, during the next classpath change, the interleaving in which JDT removes a library's
 * index while the job indexing the library walks it.
 *
 * <p>JDT processes a classpath change that adds a library it holds no timestamp for in one
 * pass: it requests the library's indexing job, then finds the library new, removes its index
 * and requests the job again. A job that has already created its index and is walking the
 * library to compare it with the index then resets the index cached for the library, which is
 * no longer the index it created. Armed for one library, this starts the library's job just
 * before JDT processes the change, holds the job inside its walk (at JDT's trace of the walk's
 * start) while JDT processes the change, and lets it go on once JDT is done. JDT's trace output
 * is routed here while armed and not printed.
 *
 * <p>{@link #events()} records the interleaving as it happened: {@code started} (the job is
 * requested ahead of JDT's processing of the change), {@code held} (the job is inside its walk),
 * {@code removed} (JDT removes the library's index), {@code released} (the job goes on).
 */
public final class LibraryIndexRace implements AutoCloseable {

    private static final long WAIT_SECONDS = 60;

    private final IPath library;
    private final String walkTrace;
    private final String removeTrace;
    private final List<String> events = new ArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private final Field debugTraceField;
    private final Object originalDebugTrace;
    private final Field traceToStdoutField;
    private final boolean originalTraceToStdout;
    private final Field verboseField;
    private final boolean originalVerbose;
    private final IResourceChangeListener beforeJdt = this::beforeJdtProcessesChange;
    private final IResourceChangeListener afterJdt = this::afterJdtProcessedChange;

    private LibraryIndexRace(IPath library) throws ReflectiveOperationException {
        this.library = library;
        this.walkTrace = "-> indexing " + library.toOSString();
        this.removeTrace = "removing index " + library;

        debugTraceField = JavaModelManager.class.getDeclaredField("DEBUG_TRACE");
        debugTraceField.setAccessible(true);
        originalDebugTrace = debugTraceField.get(null);
        traceToStdoutField = JavaModelManager.class.getDeclaredField("TRACE_TO_STDOUT");
        traceToStdoutField.setAccessible(true);
        originalTraceToStdout = traceToStdoutField.getBoolean(null);
        verboseField = IndexManager.class.getSuperclass().getField("VERBOSE");
        originalVerbose = verboseField.getBoolean(null);
    }

    /**
     * Arm the race for {@code library}, a library path as it appears on a resolved classpath.
     * The next classpath change, a change to a {@code .classpath} file of an open project, runs
     * the interleaving.
     */
    public static LibraryIndexRace arm(IPath library) throws ReflectiveOperationException {
        LibraryIndexRace race = new LibraryIndexRace(library);
        Class<?> debugTraceType = race.debugTraceField.getType();
        Object tracer = Proxy.newProxyInstance(debugTraceType.getClassLoader(), new Class<?>[] { debugTraceType },
            (proxy, method, args) -> {
                if (method.getName().equals("trace") && args != null && args.length >= 2
                        && args[1] instanceof String message) {
                    race.onTrace(message);
                }
                return null;
            });
        race.debugTraceField.set(null, tracer);
        race.traceToStdoutField.setBoolean(null, false);
        race.verboseField.setBoolean(null, true);
        JavaCore.addPreProcessingResourceChangedListener(race.beforeJdt, IResourceChangeEvent.POST_CHANGE);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(race.afterJdt, IResourceChangeEvent.POST_CHANGE);
        return race;
    }

    /** The interleaving as it happened, in order. */
    public synchronized List<String> events() {
        return List.copyOf(events);
    }

    private synchronized void record(String event) {
        events.add(event);
    }

    private void onTrace(String message) {
        if (message.equals(walkTrace) && started.getCount() == 0 && held.getCount() == 1) {
            record("held");
            held.countDown();
            await(released, "released");
        } else if (message.equals(removeTrace) && held.getCount() == 0 && released.getCount() == 1) {
            record("removed");
        }
    }

    /** Start the library's job ahead of JDT's processing of the change, and wait until it is in its walk. */
    private void beforeJdtProcessesChange(IResourceChangeEvent event) {
        if (started.getCount() == 0) {
            return;
        }
        IProject project = projectWithChangedClasspath(event.getDelta());
        if (project == null) {
            return;
        }
        record("started");
        started.countDown();
        JavaModelManager.getIndexManager().indexLibrary(library, project, null);
        await(held, "held");
    }

    /** Let the job go on once JDT has processed the change it was held for. */
    private void afterJdtProcessedChange(IResourceChangeEvent event) {
        if (held.getCount() == 0 && released.getCount() == 1) {
            record("released");
            released.countDown();
        }
    }

    /** The open project whose {@code .classpath} file the delta adds or changes, or null. */
    private static IProject projectWithChangedClasspath(IResourceDelta delta) {
        if (delta == null) {
            return null;
        }
        for (IResourceDelta projectDelta : delta.getAffectedChildren()) {
            IResourceDelta classpath = projectDelta.findMember(IPath.fromPortableString(".classpath"));
            if (classpath != null && classpath.getKind() != IResourceDelta.REMOVED
                    && projectDelta.getResource() instanceof IProject project && project.isOpen()) {
                return project;
            }
        }
        return null;
    }

    private void await(CountDownLatch latch, String event) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                record("timed out waiting for " + event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record("interrupted waiting for " + event);
        }
    }

    /** Restore JDT's tracing, remove the listeners and let a held job go on. */
    @Override
    public void close() throws ReflectiveOperationException {
        JavaCore.removePreProcessingResourceChangedListener(beforeJdt);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(afterJdt);
        released.countDown();
        verboseField.setBoolean(null, originalVerbose);
        traceToStdoutField.setBoolean(null, originalTraceToStdout);
        debugTraceField.set(null, originalDebugTrace);
    }
}
