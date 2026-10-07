package org.javalens.core;

import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the deadline every tool call runs within: the monitor JDT is handed follows the deadline
 * the calling thread runs within and is never cancelled outside one, a counted deadline expires
 * at exactly its check, a clock deadline at exactly its time, and both latch.
 */
class DeadlineTest {

    @Test
    @DisplayName("outside any deadline, monitor() is never cancelled and no deadline is current")
    void outsideAnyDeadline_monitorNeverCancelled() {
        IProgressMonitor monitor = Deadline.monitor();
        List<Boolean> answers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            answers.add(monitor.isCanceled());
        }
        assertAll(
            () -> assertNull(Deadline.current()),
            () -> assertEquals(List.of(false, false, false, false, false), answers));
    }

    @Test
    @DisplayName("a deadline expiring at its 3rd check answers not cancelled twice, then cancelled at every later check")
    void atCheck_expiresAtExactlyThatCheck() {
        Deadline deadline = Deadline.atCheck(30, 3);
        List<Boolean> answers = new ArrayList<>();
        List<Boolean> expiredAfterEach = new ArrayList<>();
        deadline.within(() -> {
            IProgressMonitor monitor = Deadline.monitor();
            for (int i = 0; i < 5; i++) {
                answers.add(monitor.isCanceled());
                expiredAfterEach.add(deadline.expired());
            }
            return null;
        });
        assertAll(
            () -> assertEquals(List.of(false, false, true, true, true), answers),
            () -> assertEquals(List.of(false, false, true, true, true), expiredAfterEach),
            () -> assertEquals(5, deadline.checks(), "asking expired() is not a check"),
            () -> assertEquals(30, deadline.seconds()));
    }

    @Test
    @DisplayName("a deadline expiring at check 0 has expired before any check; one at Long.MAX_VALUE never expires and counts its checks")
    void atCheck_zeroAndNever() {
        Deadline expired = Deadline.atCheck(30, 0);
        Deadline never = Deadline.atCheck(30, Long.MAX_VALUE);
        boolean expiredBefore = expired.expired();
        boolean firstAnswer = expired.within(() -> Deadline.monitor().isCanceled());
        List<Boolean> neverAnswers = never.within(() -> {
            IProgressMonitor monitor = Deadline.monitor();
            List<Boolean> answers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                answers.add(monitor.isCanceled());
            }
            return answers;
        });
        assertAll(
            () -> assertEquals(true, expiredBefore),
            () -> assertEquals(true, firstAnswer),
            () -> assertEquals(List.of(false, false, false, false), neverAnswers),
            () -> assertEquals(false, never.expired()),
            () -> assertEquals(4, never.checks()));
    }

    @Test
    @DisplayName("within makes the deadline current for the work only, restores an outer deadline after an inner one, also when the work throws")
    void within_installsAndRestores() {
        Deadline outer = Deadline.atCheck(30, Long.MAX_VALUE);
        Deadline inner = Deadline.atCheck(5, Long.MAX_VALUE);
        List<Deadline> seen = new ArrayList<>();
        outer.within(() -> {
            seen.add(Deadline.current());
            inner.within(() -> {
                seen.add(Deadline.current());
                return null;
            });
            seen.add(Deadline.current());
            assertThrows(IllegalStateException.class, () -> inner.within(() -> {
                seen.add(Deadline.current());
                throw new IllegalStateException("work failed");
            }));
            seen.add(Deadline.current());
            return null;
        });
        assertAll(
            () -> assertEquals(5, seen.size()),
            () -> assertSame(outer, seen.get(0)),
            () -> assertSame(inner, seen.get(1)),
            () -> assertSame(outer, seen.get(2)),
            () -> assertSame(inner, seen.get(3)),
            () -> assertSame(outer, seen.get(4)),
            () -> assertNull(Deadline.current()));
    }

    @Test
    @DisplayName("each monitor() is a new monitor: one cancelled by its holder leaves the deadline and the others as they were")
    void monitors_areIndependentOfEachOther() {
        Deadline deadline = Deadline.atCheck(30, Long.MAX_VALUE);
        deadline.within(() -> {
            IProgressMonitor first = Deadline.monitor();
            IProgressMonitor second = Deadline.monitor();
            first.setCanceled(true);
            assertAll(
                () -> assertNotSame(first, second),
                () -> assertEquals(true, first.isCanceled()),
                () -> assertEquals(false, second.isCanceled()),
                () -> assertEquals(false, deadline.expired()));
            return null;
        });
    }

    @Test
    @DisplayName("a clock deadline of 5 seconds is not expired 1ns before its end, expires at its end at a check or when asked, and stays expired")
    void clockDeadline_expiresAtItsEndAndLatches() {
        AtomicLong clock = new AtomicLong(1_000L);
        Deadline checked = Deadline.of(5, clock::get);
        Deadline asked = Deadline.of(5, clock::get);
        long end = 1_000L + 5_000_000_000L;

        clock.set(end - 1);
        boolean checkedBeforeEnd = checked.within(() -> Deadline.monitor().isCanceled());
        boolean askedBeforeEnd = asked.expired();
        clock.set(end);
        boolean checkedAtEnd = checked.within(() -> Deadline.monitor().isCanceled());
        boolean askedAtEnd = asked.expired();
        clock.set(0L);
        assertAll(
            () -> assertEquals(false, checkedBeforeEnd),
            () -> assertEquals(false, askedBeforeEnd),
            () -> assertEquals(true, checkedAtEnd),
            () -> assertEquals(true, askedAtEnd, "a clock deadline is expired once its time has passed, with no check made"),
            () -> assertEquals(true, checked.expired(), "an expired deadline stays expired"),
            () -> assertEquals(true, asked.expired(), "an expired deadline stays expired"),
            () -> assertEquals(5, checked.seconds()));
    }
}
