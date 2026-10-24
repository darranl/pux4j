// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.input.PickResult;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns the touch set in a {@link ContactSet.PollResult} into the {@link TouchEvent}s a
 * {@code Scene} delivers on a touch platform. It follows {@code Scene.touchEventNext} and
 * {@code Scene.processTouchEvent}, driven through public API only: one shared list of
 * {@link TouchPoint}s per set; targets and pick results chosen for every point before any handler
 * runs; one event per point in id order; the grabs read back once, after the whole set. The rules,
 * and the deviations from JavaFX, are in {@code docs/input-event-mapping.md}.
 *
 * <p>A point is delivered to the node it was pressed on (its <em>grab</em>) until it is released
 * or a handler ungrabs it; an ungrabbed point goes to whatever is under it, or to {@code top}.
 * As in JavaFX the grab is not checked against the scene: a grabbed node that has been removed
 * keeps receiving its point's events.
 *
 * <p>Always used from the FX thread, so it needs no synchronization.
 */
final class TouchSynthesizer {

    private static final Logger LOG = LoggerFactory.getLogger(TouchSynthesizer.class);

    private final EventTarget top;
    private final Consumer<Throwable> handlerFailure;

    /** The grab per touch-point id ({@code Scene.touchTargets}). */
    private final Map<Integer, EventTarget> grabs = new HashMap<>();

    /**
     * @param top the target of a point with nothing under it: the {@code Scene} in production
     */
    TouchSynthesizer(EventTarget top) {
        this(top, TouchSynthesizer::reportToThread);
    }

    TouchSynthesizer(EventTarget top, Consumer<Throwable> handlerFailure) {
        this.top = top;
        this.handlerFailure = handlerFailure;
    }

    /** Synthesizes the touch events for one poll's touch set; does nothing when it has none. */
    void process(ContactSet.PollResult result, Picker picker) {
        List<ContactSet.ContactChange> changes = result.changes();
        if (result.setId() == 0 || changes.isEmpty()) {
            return;
        }
        if (result.setId() == 1 && !grabs.isEmpty()) {
            // A new gesture: whatever is left came from one that never saw its releases.
            LOG.warn("Touch gesture started with {} grab(s) left over; discarding them", grabs.size());
            grabs.clear();
        }

        // Scene.touchEventNext: every point's target and pick result, before any handler runs.
        List<TouchPoint> points = new ArrayList<>(changes.size());
        for (ContactSet.ContactChange c : changes) {
            points.add(newPoint(c, picker));
        }

        // Scene.processTouchEvent: one event per point, all carrying the same list.
        for (TouchPoint tp : points) {
            fire(tp.getTarget(), new TouchEvent(eventType(tp.getState()), tp, points,
                    result.setId(), false, false, false, false));
        }

        // Grab processing: a grab left in place carries to the next set; a release ends it.
        for (TouchPoint tp : points) {
            EventTarget grabbed = tp.getGrabbed();
            if (grabbed == null || tp.getState() == TouchPoint.State.RELEASED) {
                grabs.remove(tp.getId());
            } else {
                grabs.put(tp.getId(), grabbed);
            }
        }
    }

    private TouchPoint newPoint(ContactSet.ContactChange c, Picker picker) {
        Node picked = picker.pick(c.x(), c.y());
        PickResult pickResult = picked != null
                ? new PickResult(picked, c.x(), c.y())
                : new PickResult(top, c.x(), c.y());
        // A press always starts afresh; a stale grab (a lost release) must not capture it.
        EventTarget grabbed = c.state() == ContactSet.State.PRESSED ? null : grabs.get(c.id());
        EventTarget target = grabbed != null ? grabbed : (picked != null ? picked : top);

        TouchPoint tp = new TouchPoint(c.id(), pointState(c.state()), c.x(), c.y(), c.x(), c.y(),
                target, pickResult);
        if (grabbed != null) {
            tp.grab(grabbed);
        }
        if (c.state() == ContactSet.State.PRESSED) {
            tp.grab(target);
            grabs.put(c.id(), target);
        } else if (c.state() == ContactSet.State.RELEASED) {
            grabs.remove(c.id());
        }
        return tp;
    }

    private static TouchPoint.State pointState(ContactSet.State state) {
        return switch (state) {
            case PRESSED -> TouchPoint.State.PRESSED;
            case MOVED -> TouchPoint.State.MOVED;
            case STATIONARY -> TouchPoint.State.STATIONARY;
            case RELEASED -> TouchPoint.State.RELEASED;
        };
    }

    private static EventType<TouchEvent> eventType(TouchPoint.State state) {
        return switch (state) {
            case PRESSED -> TouchEvent.TOUCH_PRESSED;
            case MOVED -> TouchEvent.TOUCH_MOVED;
            case STATIONARY -> TouchEvent.TOUCH_STATIONARY;
            case RELEASED -> TouchEvent.TOUCH_RELEASED;
        };
    }

    /**
     * Unlike {@code Scene}, a {@code RuntimeException} from a handler does not skip the remaining
     * points or the grab processing for the poll: it goes to the thread's uncaught-exception
     * handler (where applications expect FX handler exceptions to end up) and synthesis carries
     * on, so one throwing handler cannot leave the grab state stale. An {@code Error} propagates.
     */
    private void fire(EventTarget target, Event event) {
        try {
            Event.fireEvent(target, event);
        } catch (RuntimeException e) {
            handlerFailure.accept(e);
        }
    }

    private static void reportToThread(Throwable t) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
    }
}
