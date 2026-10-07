package org.javalens.core;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The time limit on one tool call, which JDT honors through the progress monitors it is given.
 *
 * <p>A tool call runs {@link #within within} its deadline on the calling thread, and every JDT call
 * made meanwhile takes {@link #monitor()}, whose {@code isCanceled} answers true once the deadline
 * has expired. JDT checks for cancellation as it works and then throws
 * {@code OperationCanceledException}: an index query before it reads each index
 * (PatternSearchJob :219), the wait for pending index jobs (JobManager.performConcurrentJob
 * :319-320), the match locator before each possible match, document, package declaration and unit
 * it resolves (MatchLocator :1355, :1469, :1607, :1949), the batch resolver after each unit and
 * binding (CompilationUnitResolver.worked :1451-1455, rethrown at :1045 and :1180), and the type
 * hierarchy, AST parser and refactoring condition checks through their sub-monitors. A call past
 * its limit therefore stops at the next check.
 *
 * <p>A deadline latches: once a check has answered true, {@link #expired()} stays true, so the
 * caller can discard whatever answer the cancelled work left, and a clock deadline is also
 * expired once its time has passed, whether or not any check was made.
 *
 * <p>Outside {@link #within}, {@link #monitor()} is a plain monitor that is never cancelled, so
 * project loads and disk-sync repairs run unbounded.
 */
public final class Deadline {

    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    private final int seconds;
    /** The clock read at each check; null for a deadline that expires at a check count. */
    private final LongSupplier clock;
    private final long endNanos;
    /** The check that expires a counted deadline; unused for a clock deadline. */
    private final long expiringCheck;
    private long checks;
    private boolean expired;

    private Deadline(int seconds, LongSupplier clock, long endNanos, long expiringCheck) {
        this.seconds = seconds;
        this.clock = clock;
        this.endNanos = endNanos;
        this.expiringCheck = expiringCheck;
        this.expired = clock == null && expiringCheck <= 0;
    }

    /** A deadline {@code seconds} from now. */
    public static Deadline of(int seconds) {
        return of(seconds, System::nanoTime);
    }

    /** Test seam: a deadline {@code seconds} after {@code clock} now reads, in nanoseconds. */
    static Deadline of(int seconds, LongSupplier clock) {
        return new Deadline(seconds, clock, clock.getAsLong() + seconds * 1_000_000_000L, 0);
    }

    /**
     * Test seam: a deadline of {@code seconds} that expires at its {@code check}-th cancellation
     * check, with no regard to time. The checks before it answer not cancelled; that check and
     * every later one answer cancelled. With {@code check} 0 the deadline has expired before any
     * check, and with {@link Long#MAX_VALUE} it never expires, which counts a call's checks.
     */
    public static Deadline atCheck(int seconds, long check) {
        return new Deadline(seconds, null, 0, check);
    }

    /**
     * The monitor every JDT call takes: on a thread running {@link #within} a deadline, a new
     * monitor that is cancelled once that deadline has expired; on any other thread, a new plain
     * monitor that is never cancelled.
     */
    public static IProgressMonitor monitor() {
        Deadline deadline = CURRENT.get();
        return deadline == null ? new NullProgressMonitor() : new DeadlineMonitor(deadline);
    }

    /** The deadline the calling thread runs within, or null when it runs within none. */
    public static Deadline current() {
        return CURRENT.get();
    }

    /**
     * Run {@code work} within this deadline on the calling thread: {@link #monitor()} follows this
     * deadline until {@code work} returns or throws, then follows the deadline the thread ran
     * within before, if any.
     */
    public <T> T within(Supplier<T> work) {
        Deadline outer = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(outer);
            }
        }
    }

    /** The limit in seconds. */
    public int seconds() {
        return seconds;
    }

    /**
     * Whether this deadline has expired: a check has answered cancelled, or, for a clock
     * deadline, its time has passed. Asking is not a cancellation check.
     */
    public synchronized boolean expired() {
        if (!expired && clock != null && clock.getAsLong() - endNanos >= 0) {
            expired = true;
        }
        return expired;
    }

    /** Test seam: the number of cancellation checks made against this deadline. */
    public synchronized long checks() {
        return checks;
    }

    /** A cancellation check: whether the work must stop. JDT may check from several threads. */
    private synchronized boolean check() {
        checks++;
        if (!expired && clock == null && checks >= expiringCheck) {
            expired = true;
        }
        return expired();
    }

    /** A plain monitor that is also cancelled once its deadline has expired. */
    private static final class DeadlineMonitor extends NullProgressMonitor {

        private final Deadline deadline;

        DeadlineMonitor(Deadline deadline) {
            this.deadline = deadline;
        }

        @Override
        public boolean isCanceled() {
            return super.isCanceled() || deadline.check();
        }
    }
}
