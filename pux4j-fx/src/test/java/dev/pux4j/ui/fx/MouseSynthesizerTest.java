// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.fx.ContactSet.Pointer;
import javafx.event.Event;
import javafx.event.EventDispatchChain;
import javafx.event.EventTarget;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MouseSynthesizer} against a detached node tree (no {@code Scene}, no toolkit — see
 * {@link ScenePickerTest}); a stub {@link EventTarget} plays the {@code Scene}. Log entries are
 * {@code NAME:EVENT_TYPE}, recorded only where the node is the event's target — so what an
 * application's own handlers on that node would see ({@code MOUSE_ENTERED}, not the
 * {@code _TARGET} variant the node's dispatcher translates).
 */
class MouseSynthesizerTest {

    /** Stands in for the {@code Scene}: the outermost target. */
    private static final class Top implements EventTarget {
        final List<String> log;
        final List<MouseEvent> events;

        Top(List<String> log, List<MouseEvent> events) {
            this.log = log;
            this.events = events;
        }

        @Override
        public EventDispatchChain buildEventDispatchChain(EventDispatchChain tail) {
            return tail.append((event, chain) -> {
                if (event instanceof MouseEvent me && me.getTarget() == this) {
                    log.add("T:" + me.getEventType().getName());
                    events.add(me);
                }
                return chain.dispatchEvent(event);
            });
        }
    }

    private final List<String> log = new ArrayList<>();
    private final List<MouseEvent> events = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    private final Rectangle left = new Rectangle(0, 0, 100, 100);
    private final Rectangle right = new Rectangle(100, 0, 100, 100);
    private final Group root = new Group(left, right);
    private final Top top = new Top(log, events);
    private final MouseSynthesizer synth = new MouseSynthesizer(top, failures::add);

    MouseSynthesizerTest() {
        record(root, "G");
        record(left, "L");
        record(right, "R");
    }

    private void record(Node n, String name) {
        n.addEventHandler(MouseEvent.ANY, e -> {
            if (e.getTarget() == n) {
                log.add(name + ":" + e.getEventType().getName());
                events.add(e);
            }
        });
    }

    private void run(Pointer p) {
        synth.process(p, (x, y) -> ScenePicker.pick(root, x, y));
    }

    private double pressX;
    private double pressY;

    private void press(double x, double y) {
        pressX = x;
        pressY = y;
        run(new Pointer(true, x, y, true, false, true, 1, x, y, 1, true, false));
    }

    private void drag(double x, double y, boolean dragDetectDefault) {
        run(new Pointer(true, x, y, false, false, true, 1, 0, 0, 1, false, dragDetectDefault));
    }

    private void release(double x, double y) {
        // stillSincePress as ContactSet computes it: false once the pointer left the 20 px box.
        boolean still = Math.abs(x - pressX) <= 20 && Math.abs(y - pressY) <= 20;
        run(new Pointer(false, x, y, false, true, false, 0, pressX, pressY, 1, still, false));
    }

    private void clearLog() {
        log.clear();
        events.clear();
    }

    @Test
    void tap_enterChainThenMovedThenPressed_andReleaseClickExit() {
        press(10, 10);
        assertEquals(List.of("T:MOUSE_ENTERED_TARGET", "G:MOUSE_ENTERED", "L:MOUSE_ENTERED",
                "L:MOUSE_MOVED", "L:MOUSE_PRESSED"), log);
        clearLog();

        release(10, 10);
        assertEquals(List.of("L:MOUSE_RELEASED", "L:MOUSE_CLICKED",
                "T:MOUSE_EXITED_TARGET", "G:MOUSE_EXITED", "L:MOUSE_EXITED"), log);
        assertFalse(synth.isPressed());
    }

    @Test
    void repeatTapInPlace_hasNoMovedEvent() {
        press(10, 10);
        release(10, 10);
        clearLog();

        run(new Pointer(false, 10, 10, true, false, true, 1, 10, 10, 2, true, false));

        assertFalse(log.contains("L:MOUSE_MOVED"));
        assertTrue(log.contains("L:MOUSE_PRESSED"));
    }

    @Test
    void pressedAndHoverStates_followTheGesture() {
        press(10, 10);
        assertTrue(left.isPressed() && root.isPressed());
        assertTrue(left.isHover() && root.isHover());
        assertFalse(right.isPressed());

        release(10, 10);
        assertFalse(left.isPressed() || root.isPressed());
        assertFalse(left.isHover() || root.isHover());
    }

    @Test
    void eventShape() {
        press(10, 20);
        MouseEvent moved = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_MOVED).findFirst().orElseThrow();
        assertEquals(MouseButton.NONE, moved.getButton());
        assertEquals(0, moved.getClickCount());
        assertFalse(moved.isPrimaryButtonDown());
        assertTrue(moved.isSynthesized());

        MouseEvent pressed = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_PRESSED).findFirst().orElseThrow();
        assertEquals(MouseButton.PRIMARY, pressed.getButton());
        assertEquals(1, pressed.getClickCount());
        assertTrue(pressed.isPrimaryButtonDown());
        assertTrue(pressed.isStillSincePress());
        assertEquals(10, pressed.getSceneX());
        assertEquals(20, pressed.getScreenY());
        assertSame(left, pressed.getPickResult().getIntersectedNode());

        release(10, 20);
        MouseEvent released = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_RELEASED).findFirst().orElseThrow();
        assertFalse(released.isPrimaryButtonDown());
        MouseEvent clicked = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_CLICKED).findFirst().orElseThrow();
        assertFalse(clicked.isPrimaryButtonDown());
        assertSame(left, clicked.getPickResult().getIntersectedNode());
    }

    // ---- drag, hover while pressed, click target ---------------------------------------------

    @Test
    void dragOffNode_exitsItOnce_andKeepsDraggingAtPressTarget_thenClicksCommonAncestor() {
        press(10, 10);
        clearLog();

        drag(150, 10, false);
        assertEquals(List.of("L:MOUSE_EXITED", "L:MOUSE_DRAGGED"), log,
                "the press hierarchy is exited; R is not entered");
        assertFalse(left.isHover());
        assertTrue(root.isHover());
        clearLog();

        release(150, 10);
        assertEquals(List.of("L:MOUSE_RELEASED", "G:MOUSE_CLICKED",
                "T:MOUSE_EXITED_TARGET", "G:MOUSE_EXITED"), log);
    }

    @Test
    void dragBackOntoNode_reentersIt() {
        press(10, 10);
        drag(150, 10, false);
        clearLog();

        drag(20, 20, false);

        assertEquals(List.of("L:MOUSE_ENTERED", "L:MOUSE_DRAGGED"), log);
        assertTrue(left.isHover());
    }

    @Test
    void click_hasNoDistanceCutoff() {
        press(10, 10);
        clearLog();
        drag(90, 90, false);
        release(90, 90);

        assertTrue(log.contains("L:MOUSE_CLICKED"));
    }

    @Test
    void releaseOverNothing_clicksTheTopTarget() {
        press(10, 10);
        clearLog();
        drag(500, 500, false);
        release(500, 500);

        assertTrue(log.contains("T:MOUSE_CLICKED"));
        assertFalse(log.contains("G:MOUSE_CLICKED"));
    }

    @Test
    void contactOverNothing_targetsTheTop() {
        press(500, 500);
        release(500, 500);

        assertEquals(List.of("T:MOUSE_ENTERED_TARGET", "T:MOUSE_MOVED", "T:MOUSE_PRESSED",
                "T:MOUSE_RELEASED", "T:MOUSE_CLICKED", "T:MOUSE_EXITED_TARGET"), log);
        assertNull(events.get(2).getPickResult().getIntersectedNode());
    }

    // ---- DRAG_DETECTED -----------------------------------------------------------------------

    @Test
    void dragDetected_firesOncePerGesture_afterTheTriggeringDrag() {
        press(10, 10);
        clearLog();

        drag(15, 10, false);
        assertFalse(log.contains("L:DRAG_DETECTED"));
        drag(50, 10, true);
        assertEquals("L:MOUSE_DRAGGED", log.get(log.size() - 2));
        assertEquals("L:DRAG_DETECTED", log.get(log.size() - 1));
        drag(60, 10, true);

        assertEquals(1, log.stream().filter("L:DRAG_DETECTED"::equals).count());
    }

    @Test
    void dragDetected_isNotFiredOnThePress_unlessAHandlerForcesIt() {
        press(10, 10);
        assertFalse(log.contains("L:DRAG_DETECTED"));
        release(10, 10);
        clearLog();

        left.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> e.setDragDetect(true));
        press(10, 10);

        assertEquals("L:MOUSE_PRESSED", log.get(log.size() - 2));
        assertEquals("L:DRAG_DETECTED", log.get(log.size() - 1));
    }

    @Test
    void dragDetected_handlerCanSuppressTheDefault() {
        left.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> e.setDragDetect(false));
        press(10, 10);
        clearLog();

        drag(80, 10, true);

        assertFalse(log.contains("L:DRAG_DETECTED"));
    }

    @Test
    void dragDetected_isResetForTheNextGesture() {
        press(10, 10);
        drag(80, 10, true);
        release(80, 10);
        clearLog();

        press(10, 10);
        drag(80, 10, true);

        assertTrue(log.contains("L:DRAG_DETECTED"));
    }

    // ---- node removal, re-parenting and the like ----------------------------------------------

    @Test
    void pressedNodeRemoved_exitsItAndRetargetsToTheNearestRemainingAncestor() {
        press(10, 10);
        root.getChildren().remove(left);
        clearLog();

        drag(150, 10, false);
        assertEquals(List.of("L:MOUSE_EXITED", "G:MOUSE_DRAGGED"), log);
        clearLog();

        release(150, 10);
        assertEquals(List.of("G:MOUSE_RELEASED", "G:MOUSE_CLICKED"), log.subList(0, 2));
    }

    @Test
    void pressedNodeReparented_isTreatedAsRemoved() {
        press(10, 10);
        Group other = new Group();
        root.getChildren().add(other);
        other.getChildren().add(left); // re-parent: still in the same tree, but not under root
        clearLog();

        drag(150, 10, false);

        assertEquals("L:MOUSE_EXITED", log.get(0));
        assertTrue(log.contains("G:MOUSE_DRAGGED"));
    }

    @Test
    void reorderingSiblings_isNotARemoval() {
        press(10, 10);
        left.toFront();
        clearLog();

        drag(15, 10, false);

        assertEquals(List.of("L:MOUSE_DRAGGED"), log);
    }

    @Test
    void disabledOrInvisibleMidPress_keepsTheTarget() {
        press(10, 10);
        left.setDisable(true);
        clearLog();

        drag(15, 10, false);
        left.setVisible(false);
        drag(20, 10, false);

        // Unpickable nodes are exited as hover changes (as in JavaFX), but they stay the press
        // target: no retargeting, so every drag still goes to L.
        assertEquals(List.of("L:MOUSE_DRAGGED", "L:MOUSE_DRAGGED"),
                log.stream().filter(e -> e.endsWith("MOUSE_DRAGGED")).toList());
    }

    // ---- robustness ----------------------------------------------------------------------------

    @Test
    void throwingHandler_isReportedAndDoesNotStopTheGesture() {
        left.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            throw new IllegalStateException("boom");
        });

        press(10, 10);
        release(10, 10);

        assertEquals(1, failures.size());
        assertTrue(log.contains("L:MOUSE_RELEASED"));
        assertTrue(log.contains("L:MOUSE_CLICKED"));
        assertFalse(synth.isPressed());
    }

    @Test
    void startFullDragOffScene_failsIntoTheFailureSinkOnly() {
        left.addEventHandler(MouseEvent.DRAG_DETECTED, e -> left.startFullDrag());
        press(10, 10);

        drag(80, 10, true);
        release(80, 10);

        assertEquals(1, failures.size());
        assertTrue(log.contains("L:MOUSE_RELEASED"));
    }

    @Test
    void releaseOrDragWithoutAPress_isIgnored() {
        drag(10, 10, false);
        release(10, 10);

        assertEquals(List.of(), log);
        assertFalse(synth.isPressed());
    }

    @Test
    void pressWhileAGestureIsInProgress_discardsTheOldGesture() {
        press(10, 10);
        clearLog();

        press(150, 10);

        assertTrue(log.contains("R:MOUSE_PRESSED"));
        release(150, 10);
        assertTrue(log.contains("R:MOUSE_CLICKED"));
    }

    @Test
    void clear_forgetsTheGestureWithoutFiringAnything() {
        press(10, 10);
        clearLog();

        synth.clear();

        assertEquals(List.of(), log);
        assertFalse(synth.isPressed());
        release(10, 10); // the stale release is ignored
        assertEquals(List.of(), log);
    }

    @Test
    void nullPointer_isIgnored() {
        synth.process(null, (x, y) -> null);

        assertEquals(List.of(), log);
    }

    @Test
    void movedBeforePress_carriesThePreviousGesturesStillSincePress() {
        press(10, 10);
        run(new Pointer(true, 90, 10, false, false, true, 1, 10, 10, 1, false, false));
        release(90, 10);
        clearLog();

        press(150, 10);

        MouseEvent moved = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_MOVED).findFirst().orElseThrow();
        assertFalse(moved.isStillSincePress());
        Event pressed = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_PRESSED).findFirst().orElseThrow();
        assertTrue(((MouseEvent) pressed).isStillSincePress());
    }

    // ---- review follow-ups ---------------------------------------------------------------------

    @Test
    void releaseHandlerRemovesTheTarget_clickGoesToTheTopTarget() {
        left.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> root.getChildren().remove(left));
        press(10, 10);
        clearLog();

        release(10, 10);

        assertTrue(log.contains("T:MOUSE_CLICKED"));
        assertFalse(log.contains("L:MOUSE_CLICKED"), "the detached node must not get the click");
        assertFalse(synth.isPressed());
    }

    @Test
    void dragHandlerRemovesTheTarget_dragDetectedGoesToTheSurvivingAncestor() {
        left.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> root.getChildren().remove(left));
        press(10, 10);
        clearLog();

        drag(80, 10, true);

        assertTrue(log.contains("G:DRAG_DETECTED"));
        assertFalse(log.contains("L:DRAG_DETECTED"));
        assertTrue(log.contains("L:MOUSE_EXITED"));
    }

    @Test
    void movedBeforePress_farFromThePreviousPress_isNoLongerStill() {
        press(10, 10);
        release(10, 10);
        clearLog();

        press(150, 10);

        MouseEvent moved = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_MOVED).findFirst().orElseThrow();
        assertFalse(moved.isStillSincePress());
    }

    @Test
    void movedBeforePress_nearThePreviousPress_staysStill() {
        press(10, 10);
        release(10, 10);
        clearLog();

        press(25, 10);

        MouseEvent moved = events.stream()
                .filter(e -> e.getEventType() == MouseEvent.MOUSE_MOVED).findFirst().orElseThrow();
        assertTrue(moved.isStillSincePress());
    }

    @Test
    void abandonedGesture_leavesNoPressedOrHoverStateBehind() {
        press(10, 10);
        assertTrue(left.isPressed() && left.isHover());
        clearLog();

        press(150, 10); // the release of the first gesture never arrived

        assertFalse(left.isPressed());
        assertFalse(left.isHover());
        assertTrue(log.contains("L:MOUSE_RELEASED"));
        assertFalse(log.contains("L:MOUSE_CLICKED"), "an abandoned gesture is not a click");
        assertTrue(right.isPressed() && right.isHover());
    }

    @Test
    void draggingWithinAnotherNodeAfterAFilteredBreak_firesNoEnterOrExit() {
        press(10, 10);
        drag(150, 10, false);
        clearLog();

        drag(170, 20, false);

        assertEquals(List.of("L:MOUSE_DRAGGED"), log);
    }
}
