// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouchContactTrackerTest {

    private static final long MS = 1_000_000L;
    private static final long TIMEOUT_MS = TouchContactTracker.DEFAULT_HOLD_TIMEOUT_MS;
    private static final List<TouchPoint> FINGER = List.of(new TouchPoint(0, 10, 20, true));

    private static TouchContactTracker tracker() {
        return new TouchContactTracker(TIMEOUT_MS);
    }

    @Test
    void noReportBeforeAnyTouchIsEmpty() {
        assertTrue(tracker().onNoReport(0).isEmpty());
    }

    @Test
    void emptyPollsBetweenReportsKeepTheContactHeld() {
        TouchContactTracker t = tracker();
        assertEquals(FINGER, t.onReport(FINGER, 0));
        for (int ms = 10; ms <= 160; ms += 10) {
            assertEquals(FINGER, t.onNoReport(ms * MS), "poll at " + ms + " ms");
        }
        assertEquals(FINGER, t.onReport(FINGER, 165 * MS));
        assertEquals(FINGER, t.onNoReport(175 * MS));
    }

    @Test
    void theLongestObservedGapBetweenReportsDoesNotLiftTheContact() {
        TouchContactTracker t = tracker();
        t.onReport(FINGER, 0);
        assertEquals(FINGER, t.onNoReport(309 * MS));
    }

    @Test
    void aReportWithNoFingerDownIsAnImmediateLift() {
        TouchContactTracker t = tracker();
        t.onReport(FINGER, 0);
        assertTrue(t.onReport(List.of(), 20 * MS).isEmpty());
        assertTrue(t.onNoReport(25 * MS).isEmpty(), "a lift stays a lift until the next report");
    }

    @Test
    void contactTimesOutWhenTheChipNeverAnnouncesALift() {
        TouchContactTracker t = tracker();
        t.onReport(FINGER, 0);
        assertEquals(FINGER, t.onNoReport(TIMEOUT_MS * MS));
        assertTrue(t.onNoReport(TIMEOUT_MS * MS + 1).isEmpty());
        assertTrue(t.onNoReport(TIMEOUT_MS * MS + 2).isEmpty(), "stays lifted");
    }

    @Test
    void aNewReportAfterTimeoutIsANewPress() {
        TouchContactTracker t = tracker();
        t.onReport(FINGER, 0);
        t.onNoReport(TIMEOUT_MS * MS + 1);
        List<TouchPoint> again = List.of(new TouchPoint(0, 30, 40, true));
        assertEquals(again, t.onReport(again, 2_000 * MS));
    }

    @Test
    void clearForgetsTheHeldContact() {
        TouchContactTracker t = tracker();
        t.onReport(FINGER, 0);
        t.clear();
        assertTrue(t.onNoReport(10 * MS).isEmpty());
    }

    @Test
    void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TouchContactTracker(0));
    }
}
