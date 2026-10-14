// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.TouchPoint;
import dev.pux4j.ui.fx.ContactSet.ContactChange;
import dev.pux4j.ui.fx.ContactSet.PollResult;
import dev.pux4j.ui.fx.ContactSet.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Toolkit-free tests of the contact/set model; the clock is passed in. */
class ContactSetTest {

    private static final long MS = 1_000_000L;

    private final ContactSet set = new ContactSet();
    private long now = 1_000 * MS;

    private static TouchPoint down(int id, int x, int y) {
        return new TouchPoint(id, x, y, true);
    }

    private static TouchPoint up(int id, int x, int y) {
        return new TouchPoint(id, x, y, false);
    }

    /** Polls 10 ms after the previous poll. */
    private PollResult poll(TouchPoint... points) {
        return pollAfter(10, points);
    }

    private PollResult pollAfter(long ms, TouchPoint... points) {
        now += ms * MS;
        return set.poll(List.of(points), now);
    }

    private static void assertChange(ContactChange c, int id, State state, double x, double y) {
        assertEquals(id, c.id(), "id");
        assertEquals(state, c.state(), "state of " + c);
        assertEquals(x, c.x(), "x");
        assertEquals(y, c.y(), "y");
    }

    // ---- single contact ------------------------------------------------------------------

    @Test
    void tap_explicitLift() {
        PollResult press = poll(down(0, 50, 40));
        assertEquals(1, press.setId());
        assertEquals(1, press.changes().size());
        assertChange(press.changes().get(0), 1, State.PRESSED, 50, 40);
        assertTrue(press.pointer().buttonDown());
        assertTrue(press.pointer().moved());
        assertEquals(1, press.pointer().clickCount());

        PollResult lift = poll(up(0, 50, 40));
        assertChange(lift.changes().get(0), 1, State.RELEASED, 50, 40);
        assertTrue(lift.pointer().buttonUp());
        assertFalse(lift.pointer().buttonHeld());
        assertFalse(set.hasActiveContacts());
    }

    @Test
    void tap_inferredLift() {
        poll(down(3, 50, 40));
        PollResult lift = poll();

        assertEquals(1, lift.changes().size());
        assertChange(lift.changes().get(0), 1, State.RELEASED, 50, 40);
        assertTrue(lift.pointer().buttonUp());
    }

    @Test
    void heldStill_producesNoSet() {
        poll(down(0, 50, 40));
        assertSame(PollResult.NONE, poll(down(0, 50, 40)));
        assertSame(PollResult.NONE, poll(down(0, 55, 44))); // inside the dead-band
    }

    @Test
    void firstSetIdIs1_andResetsAfterTheLastLift() {
        assertEquals(1, poll(down(0, 10, 10)).setId());
        assertEquals(2, poll(up(0, 10, 10)).setId());
        assertEquals(1, poll(down(0, 10, 10)).setId());
    }

    @Test
    void idZeroIsAnOrdinaryRawId() {
        PollResult r = poll(down(0, 10, 10), down(1, 100, 10));
        assertEquals(List.of(1, 2), r.changes().stream().map(ContactChange::id).toList());
    }

    @Test
    void unknownExplicitUp_isIgnored() {
        assertSame(PollResult.NONE, poll(up(7, 10, 10)));
    }

    @Test
    void duplicateRawIds_keepTheLast() {
        PollResult r = poll(down(0, 10, 10), down(0, 90, 90));
        assertEquals(1, r.changes().size());
        assertChange(r.changes().get(0), 1, State.PRESSED, 90, 90);
    }

    // ---- dead-band, drag detect -----------------------------------------------------------

    @Test
    void deadBand_isMeasuredFromThePassedOnPosition_soASlowDragEventuallyMoves() {
        poll(down(0, 100, 50));
        PollResult moved = null;
        int x = 100;
        for (int i = 0; i < 40 && moved == null; i++) {
            x++;
            PollResult r = poll(down(0, x, 50));
            if (r != PollResult.NONE) {
                moved = r;
            }
        }
        assertNotNull(moved);
        assertEquals(120, x); // exactly 20 px away is outside the dead-band (strict <)
        assertChange(moved.changes().get(0), 1, State.MOVED, 120, 50);
        assertTrue(moved.pointer().moved());
    }

    @Test
    void deadBand_isStraightLine_notPerAxis() {
        poll(down(0, 100, 50));
        // 15 px on each axis is 21.2 px straight-line: outside.
        assertEquals(State.MOVED, poll(down(0, 115, 65)).changes().get(0).state());
    }

    @Test
    void releaseLandsOnTheLastPassedOnPosition() {
        poll(down(0, 100, 50));
        poll(down(0, 105, 50)); // dropped by the dead-band
        PollResult lift = poll(up(0, 105, 50));
        assertChange(lift.changes().get(0), 1, State.RELEASED, 100, 50);
    }

    @Test
    void dragDetectDefault_isPerAxisFromTheMousePress_strictlyBeyondTwentyPixels() {
        poll(down(0, 100, 50));
        assertFalse(poll(down(0, 120, 50)).pointer().dragDetectDefault()); // |dx| == 20, not >
        assertTrue(poll(down(0, 141, 50)).pointer().dragDetectDefault());
    }

    // ---- multiple contacts ----------------------------------------------------------------

    @Test
    void twoContacts_idsAndStationary() {
        poll(down(0, 10, 10));
        PollResult second = poll(down(0, 10, 10), down(1, 200, 10));
        assertEquals(2, second.changes().size());
        assertChange(second.changes().get(0), 1, State.STATIONARY, 10, 10);
        assertChange(second.changes().get(1), 2, State.PRESSED, 200, 10);
        assertFalse(second.pointer() != null && second.pointer().buttonDown());
    }

    @Test
    void mouseButtonStaysDownUntilTheLastContactLifts_andPrimaryIsReassigned() {
        poll(down(0, 10, 10));
        poll(down(0, 10, 10), down(1, 200, 20));

        PollResult firstLifts = poll(down(1, 200, 20));
        assertChange(firstLifts.changes().get(0), 1, State.RELEASED, 10, 10);
        assertChange(firstLifts.changes().get(1), 2, State.STATIONARY, 200, 20);
        ContactSet.Pointer p = firstLifts.pointer();
        assertNotNull(p);
        assertTrue(p.moved()); // the pointer jumps to the surviving contact
        assertFalse(p.buttonUp());
        assertTrue(p.buttonHeld());
        assertEquals(2, p.primaryId());
        assertEquals(200, p.x());

        PollResult lastLifts = poll();
        assertTrue(lastLifts.pointer().buttonUp());
    }

    @Test
    void idsAreNotReusedUntilEveryContactHasLifted_thenRestartAt1() {
        poll(down(0, 10, 10), down(1, 200, 10));
        PollResult oneLifts = poll(down(1, 200, 10));
        assertEquals(1, oneLifts.changes().get(0).id());
        PollResult newFinger = poll(down(1, 200, 10), down(2, 100, 90));
        assertChange(newFinger.changes().get(1), 3, State.PRESSED, 100, 90);

        poll();
        assertEquals(1, poll(down(0, 10, 10)).changes().get(0).id());
    }

    @Test
    void fiveContacts() {
        PollResult r = poll(down(0, 10, 10), down(1, 60, 10), down(2, 110, 10),
                down(3, 160, 10), down(4, 210, 10));
        assertEquals(5, r.changes().size());
        assertTrue(r.changes().stream().allMatch(c -> c.state() == State.PRESSED));
    }

    @Test
    void driverRenumbersTheSurvivor_itKeepsItsIdentity() {
        // ICNT86X style: the raw id is an array index, so when index 0 lifts index 1 becomes 0.
        poll(down(0, 10, 10), down(1, 200, 10));
        PollResult r = poll(down(0, 200, 10));

        assertEquals(2, r.changes().size());
        assertChange(r.changes().get(0), 1, State.RELEASED, 10, 10);
        assertChange(r.changes().get(1), 2, State.STATIONARY, 200, 10);
    }

    @Test
    void rawIdChangesForTheSameFinger_isNotARePress() {
        poll(down(4, 100, 50));
        PollResult r = poll(down(6, 103, 52));
        assertSame(PollResult.NONE, r);
        assertTrue(set.hasActiveContacts());
    }

    // ---- jump rule -------------------------------------------------------------------------

    @Test
    void implausibleJump_isReleasePlusPress_andTheMouseButtonStaysDown() {
        poll(down(0, 10, 10));
        PollResult r = poll(down(0, 280, 120)); // ~290 px in 10 ms

        assertEquals(2, r.changes().size());
        assertChange(r.changes().get(0), 1, State.RELEASED, 10, 10);
        assertChange(r.changes().get(1), 2, State.PRESSED, 280, 120);
        assertTrue(r.pointer().moved());
        assertFalse(r.pointer().buttonDown());
        assertFalse(r.pointer().buttonUp());
        assertTrue(r.pointer().buttonHeld());
    }

    @Test
    void sameDistanceAfterALongGap_isNotAJump() {
        poll(down(0, 10, 10));
        PollResult r = pollAfter(400, down(0, 280, 120));

        assertEquals(1, r.changes().size());
        assertChange(r.changes().get(0), 1, State.MOVED, 280, 120);
    }

    @Test
    void fastButPlausibleSwipe_isAMove() {
        poll(down(0, 10, 50));
        assertEquals(State.MOVED, poll(down(0, 90, 50)).changes().get(0).state()); // 80 px / 10 ms
    }

    // ---- explicit-lift corner cases -------------------------------------------------------

    @Test
    void liftThenRepressOfTheSameIdInOneReport_isOneContinuingContact() {
        poll(down(0, 100, 50));
        PollResult r = poll(up(0, 100, 50), down(0, 150, 50));
        assertEquals(1, r.changes().size());
        assertChange(r.changes().get(0), 1, State.MOVED, 150, 50);
    }

    @Test
    void soleContactLiftsAndAnotherPressesInTheSamePoll_buttonStaysDown() {
        poll(down(0, 10, 10));
        PollResult r = poll(up(0, 10, 10), down(1, 250, 100));
        assertEquals(List.of(State.RELEASED, State.PRESSED),
                r.changes().stream().map(ContactChange::state).toList());
        assertFalse(r.pointer().buttonUp());
        assertFalse(r.pointer().buttonDown());
    }

    // ---- pointer: MOUSE_MOVED across gestures, clicks ------------------------------------

    @Test
    void secondTapAtTheSamePlace_doesNotMoveThePointer() {
        poll(down(0, 50, 40));
        poll(up(0, 50, 40));
        PollResult again = poll(down(0, 50, 40));
        assertFalse(again.pointer().moved());
        assertTrue(again.pointer().buttonDown());
    }

    @Test
    void doubleTap_countsTwo_thenAThirdTapAfterTheTimeoutRestarts() {
        poll(down(0, 50, 40));
        poll(up(0, 50, 40));
        pollAfter(100, down(0, 52, 40));
        PollResult secondUp = poll(up(0, 52, 40));
        assertEquals(2, secondUp.pointer().clickCount());

        poll();
        pollAfter(600, down(0, 52, 40));
        // The count is re-read on the press of the new gesture:
        PollResult third = poll(up(0, 52, 40));
        assertEquals(1, third.pointer().clickCount());
    }

    @Test
    void tapFarFromThePreviousTap_restartsTheCount() {
        poll(down(0, 50, 40));
        poll(up(0, 50, 40));
        PollResult r = pollAfter(100, down(0, 150, 40));
        assertEquals(1, r.pointer().clickCount());
    }

    @Test
    void leavingTheClickBoxIsSticky_evenIfThePointerComesBack() {
        poll(down(0, 100, 50));
        poll(down(0, 150, 50));  // out of the box
        poll(down(0, 100, 50));  // back again
        poll(up(0, 100, 50));
        PollResult r = pollAfter(100, down(0, 100, 50));
        assertEquals(1, r.pointer().clickCount());
    }

    @Test
    void stillSincePress_clearsOnceTheContactMovesBeyondTheBox() {
        assertTrue(poll(down(0, 100, 50)).pointer().stillSincePress());
        assertTrue(poll(down(0, 120, 50)).pointer().stillSincePress()); // 20 px: not beyond the box
        assertFalse(poll(down(0, 141, 50)).pointer().stillSincePress());
    }

    @Test
    void clear_forgetsContactsAndGestureState() {
        poll(down(0, 50, 40));
        set.clear();
        assertFalse(set.hasActiveContacts());
        PollResult r = poll(down(0, 50, 40));
        assertEquals(1, r.setId());
        assertEquals(1, r.pointer().clickCount());
        assertNull(poll(down(0, 50, 40)).pointer());
    }
}
