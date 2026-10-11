// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.gt1151q;

import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.pux4j.ui.driver.hat2in13v4.gt1151q.TouchContactTracker.HOLD_TIMEOUT_NANOS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouchContactTrackerTest {

    private static final long MS = 1_000_000L;
    private static final List<TouchPoint> FINGER = List.of(new TouchPoint(1, 10, 20, true));

    @Test
    void noReportBeforeAnyTouchIsEmpty() {
        assertTrue(new TouchContactTracker().onNoReport(0).isEmpty());
    }

    @Test
    void emptyPollsBetweenReportsKeepTheContactHeld() {
        TouchContactTracker t = new TouchContactTracker();
        assertEquals(FINGER, t.onReport(FINGER, 0));
        // Several polls (10 ms apart) between reports must not look like a lift.
        for (int ms = 10; ms <= 50; ms += 10) {
            assertEquals(FINGER, t.onNoReport(ms * MS), "poll at " + ms + " ms");
        }
        assertEquals(FINGER, t.onReport(FINGER, 60 * MS));
        assertEquals(FINGER, t.onNoReport(70 * MS));
    }

    @Test
    void explicitZeroContactReportIsAnImmediateLift() {
        TouchContactTracker t = new TouchContactTracker();
        t.onReport(FINGER, 0);
        assertTrue(t.onReport(List.of(), 20 * MS).isEmpty());
        assertTrue(t.onNoReport(25 * MS).isEmpty(), "a lift stays a lift until the next report");
    }

    @Test
    void contactTimesOutWhenTheChipNeverAnnouncesALift() {
        TouchContactTracker t = new TouchContactTracker();
        t.onReport(FINGER, 0);
        assertEquals(FINGER, t.onNoReport(HOLD_TIMEOUT_NANOS));
        assertTrue(t.onNoReport(HOLD_TIMEOUT_NANOS + 1).isEmpty());
        assertTrue(t.onNoReport(HOLD_TIMEOUT_NANOS + 2).isEmpty(), "stays lifted");
    }

    @Test
    void aNewReportAfterTimeoutIsANewPress() {
        TouchContactTracker t = new TouchContactTracker();
        t.onReport(FINGER, 0);
        t.onNoReport(HOLD_TIMEOUT_NANOS + 1);
        List<TouchPoint> again = List.of(new TouchPoint(1, 30, 40, true));
        assertEquals(again, t.onReport(again, 500 * MS));
    }

    @Test
    void clearForgetsHeldContacts() {
        TouchContactTracker t = new TouchContactTracker();
        t.onReport(FINGER, 0);
        t.clear();
        assertTrue(t.onNoReport(MS).isEmpty());
    }

    @Test
    void returnedListIsNotAffectedByLaterMutationOfTheInput() {
        TouchContactTracker t = new TouchContactTracker();
        java.util.ArrayList<TouchPoint> mutable = new java.util.ArrayList<>(FINGER);
        t.onReport(mutable, 0);
        mutable.clear();
        assertEquals(FINGER, t.onNoReport(MS));
    }
}
