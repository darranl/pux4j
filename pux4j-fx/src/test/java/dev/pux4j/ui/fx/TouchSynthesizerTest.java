// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.fx.ContactSet.ContactChange;
import dev.pux4j.ui.fx.ContactSet.PollResult;
import dev.pux4j.ui.fx.ContactSet.State;
import javafx.event.EventDispatchChain;
import javafx.event.EventTarget;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TouchSynthesizer} against a detached node tree (no {@code Scene}, no toolkit — see
 * {@link MouseSynthesizerTest}); a stub {@link EventTarget} plays the {@code Scene}. Log entries
 * are {@code NAME:EVENT_TYPE:id}, recorded where the node is the event's target.
 */
class TouchSynthesizerTest {

    /** Stands in for the {@code Scene}: the target of a point with nothing under it. */
    private static final class Top implements EventTarget {
        final Consumer<TouchEvent> sink;

        Top(Consumer<TouchEvent> sink) {
            this.sink = sink;
        }

        @Override
        public EventDispatchChain buildEventDispatchChain(EventDispatchChain tail) {
            return tail.append((event, chain) -> {
                if (event instanceof TouchEvent te && te.getTarget() == this) {
                    sink.accept(te);
                }
                return chain.dispatchEvent(event);
            });
        }
    }

    private final List<String> log = new ArrayList<>();
    private final List<TouchEvent> events = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    /** {@code getGrabbed()} as the logging handler saw it, parallel to {@link #events}. */
    private final List<EventTarget> grabSeen = new ArrayList<>();

    private final Rectangle left = new Rectangle(0, 0, 100, 100);
    private final Rectangle right = new Rectangle(100, 0, 100, 100);
    private final Group root = new Group(left, right);
    private final Top top = new Top(e -> {
        log.add("T:" + e.getEventType().getName() + ":" + e.getTouchPoint().getId());
        events.add(e);
        grabSeen.add(e.getTouchPoint().getGrabbed());
    });
    private final TouchSynthesizer synth = new TouchSynthesizer(top, failures::add);

    TouchSynthesizerTest() {
        record(left, "L");
        record(right, "R");
    }

    private void record(Node n, String name) {
        n.addEventHandler(TouchEvent.ANY, e -> {
            if (e.getTarget() == n) {
                log.add(name + ":" + e.getEventType().getName() + ":" + e.getTouchPoint().getId());
                events.add(e);
                grabSeen.add(e.getTouchPoint().getGrabbed());
            }
        });
    }

    private static ContactChange c(int id, State state, double x, double y) {
        return new ContactChange(id, state, x, y);
    }

    private void set(int setId, ContactChange... changes) {
        synth.process(new PollResult(setId, List.of(changes), null),
                (x, y) -> ScenePicker.pick(root, x, y));
    }

    @Test
    void pressGoesToTheNodeUnderTheContactAndIsGrabbed() {
        set(1, c(1, State.PRESSED, 50, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1"), log);
        TouchEvent e = events.get(0);
        assertEquals(1, e.getEventSetId());
        assertEquals(1, e.getTouchCount());
        assertSame(left, grabSeen.get(0));
        assertSame(left, e.getTouchPoint().getTarget());
        assertSame(left, e.getTouchPoint().getPickResult().getIntersectedNode());
        assertFalse(e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown());
    }

    @Test
    void aGrabbedPointFollowsItsGrabWhereverItMoves() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.MOVED, 150, 50));
        set(3, c(1, State.MOVED, 500, 500));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "L:TOUCH_MOVED:1", "L:TOUCH_MOVED:1"), log);
        // The pick result still says what is under the contact.
        assertSame(right, events.get(1).getTouchPoint().getPickResult().getIntersectedNode());
        assertNull(events.get(2).getTouchPoint().getPickResult().getIntersectedNode());
        assertEquals(150, events.get(1).getTouchPoint().getSceneX());
        // The handler still sees the press target as the grab.
        assertEquals(List.of(left, left, left), grabSeen);
    }

    @Test
    void overNothingTheTopTargetReceivesTheEvents() {
        set(1, c(1, State.PRESSED, 500, 500));
        set(2, c(1, State.RELEASED, 500, 500));

        assertEquals(List.of("T:TOUCH_PRESSED:1", "T:TOUCH_RELEASED:1"), log);
    }

    @Test
    void releaseEndsTheGrabSoTheNextPressPicksAfresh() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.RELEASED, 50, 50));
        set(1, c(1, State.PRESSED, 150, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "L:TOUCH_RELEASED:1", "R:TOUCH_PRESSED:1"), log);
    }

    @Test
    void anUngrabbedPointIsRepickedFromTheNextSet() {
        left.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab());
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.MOVED, 150, 50));
        set(3, c(1, State.MOVED, 160, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "R:TOUCH_MOVED:1", "R:TOUCH_MOVED:1"), log);
        // An ungrabbed point carries no grab into its event.
        assertNull(grabSeen.get(1));
    }

    @Test
    void aHandlerCanGrabAnUngrabbedPointAndKeepIt() {
        List<EventTarget> seenByGrabber = new ArrayList<>();
        left.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab());
        right.addEventHandler(TouchEvent.TOUCH_MOVED, e -> {
            seenByGrabber.add(e.getTouchPoint().getGrabbed());
            e.getTouchPoint().grab();
        });
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.MOVED, 150, 50)); // ungrabbed: goes to R, which grabs it
        set(3, c(1, State.MOVED, 50, 50));  // so R still gets it over L

        assertEquals(List.of("L:TOUCH_PRESSED:1", "R:TOUCH_MOVED:1", "R:TOUCH_MOVED:1"), log);
        // The point arrived ungrabbed, so grab() really changed something; then it arrived grabbed.
        assertEquals(java.util.Arrays.asList(null, right), seenByGrabber);
    }

    @Test
    void aHandlerCanGrabAPointForAnotherNode() {
        left.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().grab(right));
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.MOVED, 60, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "R:TOUCH_MOVED:1"), log);
    }

    @Test
    void twoPointsShareOneListAndSetId() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "L:TOUCH_STATIONARY:1", "R:TOUCH_PRESSED:2"), log);
        TouchEvent a = events.get(1);
        TouchEvent b = events.get(2);
        assertEquals(2, a.getEventSetId());
        assertEquals(2, b.getEventSetId());
        assertEquals(2, a.getTouchCount());
        assertEquals(List.of(1, 2), a.getTouchPoints().stream().map(TouchPoint::getId).toList());
        // The same TouchPoint objects in every event of the set, and each event's own point is one of them.
        for (int i = 0; i < 2; i++) {
            assertSame(a.getTouchPoints().get(i), b.getTouchPoints().get(i));
        }
        assertSame(a.getTouchPoints().get(0), a.getTouchPoint());
        assertSame(b.getTouchPoints().get(1), b.getTouchPoint());
        assertSame(a.getTouchPoints().get(1), b.getTouchPoint());
    }

    @Test
    void aReleasedPointIsDroppedFromLaterSetsAndTheOthersKeepTheirGrabs() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));
        set(3, c(1, State.RELEASED, 50, 50), c(2, State.STATIONARY, 150, 50));
        set(4, c(2, State.MOVED, 50, 50)); // over L now, but still grabbed by R

        TouchEvent released = events.get(3);
        assertEquals(TouchEvent.TOUCH_RELEASED, released.getEventType());
        assertEquals(2, released.getTouchCount());
        assertEquals(1, events.get(5).getTouchCount());
        assertEquals("R:TOUCH_MOVED:2", log.get(log.size() - 1));
    }

    @Test
    void targetsAreChosenBeforeAnyHandlerRuns() {
        // The first point's handler removes the node the second point is over.
        left.addEventHandler(TouchEvent.TOUCH_STATIONARY, e -> root.getChildren().remove(right));
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));

        assertEquals("R:TOUCH_PRESSED:2", log.get(log.size() - 1));
    }

    @Test
    void aGrabbedNodeThatLeftTheSceneKeepsReceivingItsPointsEvents() {
        set(1, c(1, State.PRESSED, 50, 50));
        root.getChildren().remove(left);
        set(2, c(1, State.MOVED, 60, 50));
        set(3, c(1, State.RELEASED, 60, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "L:TOUCH_MOVED:1", "L:TOUCH_RELEASED:1"), log);
    }

    @Test
    void aThrowingHandlerDoesNotStopTheRestOfTheSetOrItsGrabProcessing() {
        left.addEventHandler(TouchEvent.TOUCH_STATIONARY, e -> {
            throw new IllegalStateException("boom");
        });
        right.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
            e.getTouchPoint().ungrab();
            throw new IllegalStateException("bang");
        });
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));
        // Point 2's ungrab was made in a handler that then threw: it must still take effect.
        set(3, c(1, State.MOVED, 60, 50), c(2, State.MOVED, 60, 50));

        // Both handlers ran, so the second point's event was fired although the first handler threw.
        assertEquals(List.of("boom", "bang"), failures.stream().map(Throwable::getMessage).toList());
        // Point 1 kept its grab (L) and point 2, ungrabbed, was re-picked (also L).
        assertEquals(List.of("L:TOUCH_MOVED:1", "L:TOUCH_MOVED:2"),
                log.subList(log.size() - 2, log.size()));
    }

    @Test
    void anUnknownIdIsTreatedAsUngrabbedAndDoesNotThrow() {
        set(2, c(7, State.MOVED, 150, 50));

        assertEquals(List.of("R:TOUCH_MOVED:7"), log);
        assertNull(events.get(0).getTouchPoint().getGrabbed());
    }

    @Test
    void aPressIgnoresAStaleGrab() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(5, c(1, State.PRESSED, 150, 50)); // the release was lost; not a first set
        set(6, c(1, State.MOVED, 160, 50));
        set(7, c(1, State.MOVED, 50, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "R:TOUCH_PRESSED:1", "R:TOUCH_MOVED:1",
                "R:TOUCH_MOVED:1"), log);
    }

    @Test
    void aNewGestureDiscardsGrabsLeftOverFromTheLastOne() {
        set(1, c(1, State.PRESSED, 50, 50), c(2, State.PRESSED, 150, 50));
        set(1, c(1, State.PRESSED, 50, 50)); // the releases were lost
        // Point 2 is unknown now: it is picked afresh, not sent to its old grab (R).
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.MOVED, 50, 50));

        assertEquals("L:TOUCH_MOVED:2", log.get(log.size() - 1));
    }

    @Test
    void aJumpIsAReleaseAndAPressInOneSet() {
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.RELEASED, 50, 50), c(2, State.PRESSED, 150, 50));
        set(3, c(2, State.MOVED, 50, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "L:TOUCH_RELEASED:1", "R:TOUCH_PRESSED:2",
                "R:TOUCH_MOVED:2"), log);
        assertEquals(2, events.get(1).getEventSetId());
        assertEquals(2, events.get(2).getEventSetId());
    }

    @Test
    void aConsumedEventDoesNotStopTheOthersInTheSet() {
        left.addEventHandler(TouchEvent.TOUCH_PRESSED, TouchEvent::consume);
        set(1, c(1, State.PRESSED, 50, 50), c(2, State.PRESSED, 150, 50));

        assertEquals(List.of("L:TOUCH_PRESSED:1", "R:TOUCH_PRESSED:2"), log);
    }

    @Test
    void coordinatesAreLocalToEachHandlersNode() {
        Rectangle node = new Rectangle(0, 0, 100, 100);
        node.setLayoutX(300);
        Group scene = new Group(node);
        List<double[]> seen = new ArrayList<>();
        node.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
            if (e.getTouchPoint().getId() == 1) {
                seen.add(new double[] {e.getTouchPoint().getX(), e.getTouchPoint().getY(),
                        e.getTouchPoints().get(1).getX(), e.getTouchPoints().get(1).getY(),
                        e.getTouchPoint().getSceneX(), e.getTouchPoint().getScreenX()});
            }
        });
        synth.process(new PollResult(1, List.of(c(1, State.PRESSED, 350, 40), c(2, State.PRESSED, 320, 10)), null),
                (x, y) -> ScenePicker.pick(scene, x, y));

        assertEquals(1, seen.size());
        assertEquals(List.of(50.0, 40.0, 20.0, 10.0, 350.0, 350.0),
                java.util.Arrays.stream(seen.get(0)).boxed().toList());
    }

    @Test
    void aHandlerSeesAndChangesOtherPointsGrabsAcrossTheSet() {
        List<EventTarget> seenForTwo = new ArrayList<>();
        // Point 1's event (fired first) ungrabs point 2; point 2's event sees that.
        left.addEventHandler(TouchEvent.TOUCH_STATIONARY, e -> e.getTouchPoints().get(1).ungrab());
        right.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> seenForTwo.add(e.getTouchPoint().getGrabbed()));
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));
        set(3, c(1, State.STATIONARY, 50, 50), c(2, State.MOVED, 50, 50));

        assertEquals(java.util.Arrays.asList((EventTarget) null), seenForTwo);
        // Point 2 ended the set ungrabbed, so it is picked afresh (L) in the next one.
        assertEquals("L:TOUCH_MOVED:2", log.get(log.size() - 1));
    }

    @Test
    void aLaterHandlerCanChangeAnEarlierPointsGrab() {
        // Point 2's event (fired second) ungrabs point 1, whose event is already done.
        right.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoints().get(0).ungrab());
        set(1, c(1, State.PRESSED, 50, 50));
        set(2, c(1, State.STATIONARY, 50, 50), c(2, State.PRESSED, 150, 50));
        set(3, c(1, State.MOVED, 150, 50), c(2, State.STATIONARY, 150, 50));

        assertEquals("R:TOUCH_MOVED:1", log.get(log.size() - 2));
    }

    @Test
    void anEmptyResultFiresNothing() {
        synth.process(PollResult.NONE, (x, y) -> null);
        assertTrue(log.isEmpty());
    }
}
