// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.TouchPoint;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one poll's worth of logical-space touch contacts into synthetic JavaFX input events.
 * Extracted from {@link EInkBridge} so it can be tested without a panel; it holds the per-contact
 * state and nothing else. Always used from the FX thread, so it needs no synchronization.
 *
 * <p>Picking is supplied as a {@link Picker} so tests can run against a detached node tree
 * (no {@code Scene}, no toolkit).
 *
 * <p>Not every touch IC reports a lift as an explicit {@code down=false} point — the
 * GT1151Q (2.13" HAT) signals it purely by the contact disappearing from the next report,
 * while the ICNT86X (2.9" HAT) does send an explicit {@code down=false}. Both must work:
 * after dispatching everything {@code contacts} reports, any id still tracked that wasn't in
 * this batch is inferred to have lifted, and its release is synthesized at its last known
 * position. Without this, a GT1151Q press would never see a {@code MOUSE_RELEASED}/{@code
 * MOUSE_CLICKED} at all, and the stale {@link ContactState} would misdispatch the next
 * unrelated tap sharing that contact id as a drag of the first press's node.
 */
final class TouchInjector {

    /** Finds the node under a scene-coordinate point, or {@code null} if there is none. */
    @FunctionalInterface
    interface Picker {
        Node pick(double x, double y);
    }

    /**
     * Maximum scene-coordinate distance, in pixels, between a press and its matching release
     * for the pair to still synthesize a {@code MOUSE_CLICKED}.
     */
    static final double CLICK_TOLERANCE_PX = 4.0;

    /**
     * A contact's dispatch state: the node it was pressed on (or {@code null} if the press
     * hit nothing), its press position (for {@link #CLICK_TOLERANCE_PX} on release), and its
     * last known position (to detect movement between polls, for {@code MOUSE_DRAGGED}).
     */
    private record ContactState(Node pressedNode, double pressX, double pressY, double lastX, double lastY) {}

    private final Map<Integer, ContactState> activeContacts = new HashMap<>();

    /** True while any contact is tracked as down. */
    boolean hasActiveContacts() {
        return !activeContacts.isEmpty();
    }

    /** Forgets every tracked contact without firing anything. */
    void clear() {
        activeContacts.clear();
    }

    /** Dispatches one poll's contacts; see the class javadoc for the two lift paths. */
    void inject(Picker picker, List<TouchPoint> contacts) {
        Set<Integer> reportedIds = new HashSet<>();
        for (TouchPoint p : contacts) {
            reportedIds.add(p.id());
            if (p.down()) {
                dispatchDownContact(picker, p);
            } else {
                dispatchUpContact(picker, p);
            }
        }
        if (!activeContacts.isEmpty()) {
            for (Integer id : List.copyOf(activeContacts.keySet())) {
                if (!reportedIds.contains(id)) {
                    ContactState state = activeContacts.remove(id);
                    releaseContact(picker, state, state.lastX(), state.lastY());
                }
            }
        }
    }

    /** New contact → {@code MOUSE_PRESSED} at the picked node (if any). Existing contact that
     * moved since the last poll → {@code MOUSE_DRAGGED} at its pressed node (if any); a
     * contact that hasn't moved is left alone (no event). */
    private void dispatchDownContact(Picker picker, TouchPoint p) {
        ContactState state = activeContacts.get(p.id());
        if (state == null) {
            Node target = picker.pick(p.x(), p.y());
            if (target != null) {
                fireMouseEvent(target, MouseEvent.MOUSE_PRESSED, p.x(), p.y());
            }
            activeContacts.put(p.id(), new ContactState(target, p.x(), p.y(), p.x(), p.y()));
        } else if (p.x() != state.lastX() || p.y() != state.lastY()) {
            if (state.pressedNode() != null) {
                fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_DRAGGED, p.x(), p.y());
            }
            activeContacts.put(p.id(),
                    new ContactState(state.pressedNode(), state.pressX(), state.pressY(), p.x(), p.y()));
        }
    }

    /** Explicit {@code down=false} point (ICNT86X-style lift) — releases at the reported
     * position. */
    private void dispatchUpContact(Picker picker, TouchPoint p) {
        ContactState state = activeContacts.remove(p.id());
        if (state != null) {
            releaseContact(picker, state, p.x(), p.y());
        }
    }

    /**
     * {@code MOUSE_RELEASED} at {@code state}'s pressed node, plus {@code MOUSE_CLICKED} if
     * the release lands back on that same node within {@link #CLICK_TOLERANCE_PX} of the
     * original press position (not the last drag position — a slow drag back to the start
     * shouldn't register as a click). Shared by both lift paths, which differ only in where
     * the release position comes from (an explicit point vs. the contact's last known position).
     */
    private void releaseContact(Picker picker, ContactState state, double releaseX, double releaseY) {
        if (state.pressedNode() == null) {
            return;
        }
        fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_RELEASED, releaseX, releaseY);

        double distanceFromPress = Math.hypot(releaseX - state.pressX(), releaseY - state.pressY());
        Node releaseTarget = picker.pick(releaseX, releaseY);
        if (releaseTarget == state.pressedNode() && distanceFromPress <= CLICK_TOLERANCE_PX) {
            fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_CLICKED, releaseX, releaseY);
        }
    }

    /**
     * Fires a synthetic mouse event at {@code target} via {@code Event.fireEvent}, giving
     * correct capture/bubble dispatch along the node's parent chain. {@code synthesized} is
     * {@code true}. Known limitations of this path (see the input-event mapping doc): no
     * {@code :hover}, {@code stillSincePress} always {@code true}, and — since the bridge runs
     * stage-less — screen coordinates are approximated as scene coordinates.
     */
    private static void fireMouseEvent(Node target, EventType<MouseEvent> type, double x, double y) {
        boolean primaryDown = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        MouseEvent event = new MouseEvent(
                type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                primaryDown, false, false,
                true, false, true,
                new PickResult(target, x, y));
        Event.fireEvent(target, event);
    }
}
