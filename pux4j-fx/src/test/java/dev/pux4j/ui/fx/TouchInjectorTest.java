// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.TouchPoint;
import javafx.event.EventType;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises the touch → mouse-event behaviour as extracted from {@link EInkBridge}, against a
 * detached node tree (no {@code Scene}, no toolkit — see {@link ScenePickerTest}). These pin the
 * current behaviour so the event-mapping changes can be made deliberately; tests that encode the
 * to-be-replaced rules (the 4 px click tolerance, one mouse stream per contact) say so.
 */
class TouchInjectorTest {

    private final TouchInjector injector = new TouchInjector();
    private final List<String> log = new ArrayList<>();
    private final List<MouseEvent> events = new ArrayList<>();

    private final Rectangle left = new Rectangle(0, 0, 100, 100);
    private final Rectangle right = new Rectangle(100, 0, 100, 100);
    private final Group root = new Group(left, right);

    TouchInjectorTest() {
        record(left, "L");
        record(right, "R");
    }

    private void record(Node n, String name) {
        for (EventType<MouseEvent> t : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED,
                MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            n.addEventHandler(t, e -> {
                log.add(name + ":" + e.getEventType().getName());
                events.add(e);
            });
        }
    }

    private void poll(TouchPoint... points) {
        injector.inject((x, y) -> ScenePicker.pick(root, x, y), List.of(points));
    }

    private static TouchPoint down(int id, int x, int y) {
        return new TouchPoint(id, x, y, true);
    }

    private static TouchPoint up(int id, int x, int y) {
        return new TouchPoint(id, x, y, false);
    }

    @Test
    void tap_withExplicitLift_firesPressedReleasedClicked() {
        poll(down(0, 10, 10));
        assertTrue(injector.hasActiveContacts());
        poll(up(0, 10, 10));

        assertEquals(List.of("L:MOUSE_PRESSED", "L:MOUSE_RELEASED", "L:MOUSE_CLICKED"), log);
        assertFalse(injector.hasActiveContacts());
    }

    @Test
    void tap_withInferredLift_releasesAtLastPosition() {
        poll(down(0, 10, 10));
        poll(); // GT1151Q-style: the contact just disappears

        assertEquals(List.of("L:MOUSE_PRESSED", "L:MOUSE_RELEASED", "L:MOUSE_CLICKED"), log);
        assertEquals(10, events.get(1).getSceneX());
        assertFalse(injector.hasActiveContacts());
    }

    @Test
    void heldStill_firesNothingFurther() {
        poll(down(0, 10, 10));
        poll(down(0, 10, 10));
        poll(down(0, 10, 10));

        assertEquals(List.of("L:MOUSE_PRESSED"), log);
    }

    @Test
    void move_firesDraggedAtPressedNode_evenOverAnotherNode() {
        poll(down(0, 10, 10));
        poll(down(0, 150, 10));

        assertEquals(List.of("L:MOUSE_PRESSED", "L:MOUSE_DRAGGED"), log);
        assertEquals(150, events.get(1).getSceneX());
    }

    /** Encodes the 4 px rule that the event-mapping work replaces. */
    @Test
    void clickWithinTolerance_fires_beyondItDoesNot() {
        poll(down(0, 10, 10));
        poll(up(0, 13, 10)); // 3 px
        assertTrue(log.contains("L:MOUSE_CLICKED"));

        log.clear();
        poll(down(0, 10, 10));
        poll(up(0, 20, 10)); // 10 px, still on the same node
        assertEquals(List.of("L:MOUSE_PRESSED", "L:MOUSE_RELEASED"), log);
    }

    /** Encodes the "same node only" rule that the event-mapping work replaces. */
    @Test
    void releaseOnAnotherNode_firesReleasedAtPressedNodeButNoClick() {
        poll(down(0, 90, 10));
        poll(up(0, 110, 10));

        assertEquals(List.of("L:MOUSE_PRESSED", "L:MOUSE_RELEASED"), log);
    }

    /** Encodes "nothing fires when no node is hit", which the event-mapping work replaces. */
    @Test
    void contactOverNothing_firesNothingButStillTracksTheContact() {
        poll(down(0, 500, 500));
        assertTrue(injector.hasActiveContacts());
        poll(up(0, 500, 500));

        assertEquals(List.of(), log);
        assertFalse(injector.hasActiveContacts());
    }

    @Test
    void syntheticMouseEventShape() {
        poll(down(0, 10, 20));
        MouseEvent e = events.get(0);

        assertTrue(e.isSynthesized());
        assertTrue(e.isPrimaryButtonDown());
        assertEquals(1, e.getClickCount());
        assertTrue(e.isStillSincePress());
        assertEquals(10, e.getSceneX());
        assertEquals(20, e.getSceneY());
    }

    @Test
    void twoContacts_eachGetTheirOwnMouseStream() {
        poll(down(0, 10, 10), down(1, 150, 10));
        poll(up(0, 10, 10), up(1, 150, 10));

        assertEquals(List.of("L:MOUSE_PRESSED", "R:MOUSE_PRESSED",
                "L:MOUSE_RELEASED", "L:MOUSE_CLICKED", "R:MOUSE_RELEASED", "R:MOUSE_CLICKED"), log);
    }

    @Test
    void reusedId_afterLift_startsAFreshPress() {
        poll(down(0, 10, 10));
        poll(); // inferred lift
        log.clear();
        poll(down(0, 150, 10));

        assertEquals(List.of("R:MOUSE_PRESSED"), log);
    }

    @Test
    void clear_forgetsContactsWithoutFiringAnything() {
        poll(down(0, 10, 10));
        log.clear();
        injector.clear();
        poll();

        assertEquals(List.of(), log);
        assertFalse(injector.hasActiveContacts());
    }
}
