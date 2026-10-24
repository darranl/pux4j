// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns the single mouse pointer described by {@link ContactSet.Pointer} into the mouse events a
 * {@code Scene} would deliver on a pointer platform: {@code MOUSE_MOVED}, {@code PRESSED},
 * {@code DRAGGED}, {@code DRAG_DETECTED}, {@code RELEASED}, {@code CLICKED} and the
 * {@code MOUSE_ENTERED_TARGET}/{@code MOUSE_EXITED_TARGET} chains. It is a port of the relevant
 * parts of {@code Scene.MouseHandler.process} (enter/exit with the press hierarchy filter, the
 * target of events while pressed, click target, drag detection, node removal), driven through
 * public API only. The rules, and the deviations from JavaFX, are in
 * {@code docs/input-event-mapping.md}.
 *
 * <p>Always used from the FX thread, so it needs no synchronization. Picking is supplied as a
 * {@link Picker} and the root of every hierarchy (the {@code Scene} in production) as an
 * {@link EventTarget}, so tests can run against detached node trees with no toolkit.
 */
final class MouseSynthesizer {

    private static final Logger LOG = LoggerFactory.getLogger(MouseSynthesizer.class);

    private final EventTarget top;
    private final Consumer<Throwable> handlerFailure;

    /** Entered targets, innermost first ({@code Scene.currentEventTargets}). */
    private List<EventTarget> entered = new ArrayList<>();
    /** The press target's hierarchy, innermost first, while a gesture is in progress. */
    private List<EventTarget> pressChain = List.of();
    private boolean held;
    private boolean dragDetectDone;
    private boolean lastStill;
    private double lastPressX = Double.NaN;
    private double lastPressY = Double.NaN;
    private MouseEvent lastEvent;

    /**
     * @param top the outermost target of every hierarchy and the target of events with nothing
     *            under the pointer: the {@code Scene} in production
     */
    MouseSynthesizer(EventTarget top) {
        this(top, MouseSynthesizer::reportToThread);
    }

    MouseSynthesizer(EventTarget top, Consumer<Throwable> handlerFailure) {
        this.top = top;
        this.handlerFailure = handlerFailure;
    }

    /** True from the press until the release has been synthesized. */
    boolean isPressed() {
        return held;
    }

    /** Forgets all state without firing anything. */
    void clear() {
        entered = new ArrayList<>();
        pressChain = List.of();
        held = false;
        dragDetectDone = false;
        lastStill = false;
        lastPressX = lastPressY = Double.NaN;
        lastEvent = null;
    }

    /**
     * Ends a gesture whose release never arrived (a lost dispatch, a failed poll): releases it at
     * its press target with no click and exits everything, so no node is left {@code :pressed} or
     * {@code :hover}, then forgets the state.
     */
    private void abandonGesture() {
        if (lastEvent != null && !pressChain.isEmpty()) {
            MouseEvent last = lastEvent;
            MouseEvent release = new MouseEvent(MouseEvent.MOUSE_RELEASED,
                    last.getSceneX(), last.getSceneY(), last.getScreenX(), last.getScreenY(),
                    MouseButton.PRIMARY, last.getClickCount(),
                    false, false, false, false, false, false, false,
                    true, false, last.isStillSincePress(), last.getPickResult());
            fire(pressChain.get(0), release);
            exitAll(release);
        }
        clear();
    }

    /** Synthesizes the mouse events for one poll's pointer activity. */
    void process(ContactSet.Pointer p, Picker picker) {
        if (p == null) {
            return;
        }
        if (p.buttonDown()) {
            if (held) {
                LOG.warn("Pointer pressed while a gesture was still in progress; abandoning it");
                abandonGesture();
            }
            if (p.moved()) {
                dispatch(MouseEvent.MOUSE_MOVED, p, picker);
            }
            dispatch(MouseEvent.MOUSE_PRESSED, p, picker);
        } else if (p.buttonHeld()) {
            if (!held) {
                LOG.warn("Pointer moved with the button down but no press was seen; ignoring");
                return;
            }
            if (p.moved()) {
                dispatch(MouseEvent.MOUSE_DRAGGED, p, picker);
            }
        } else if (p.buttonUp()) {
            if (!held) {
                LOG.warn("Pointer released but no press was seen; ignoring");
                return;
            }
            dispatch(MouseEvent.MOUSE_RELEASED, p, picker);
        }
    }

    // -------------------------------------------------------------------------

    private void dispatch(EventType<MouseEvent> type, ContactSet.Pointer p, Picker picker) {
        removeDetachedTargets();

        Node picked = picker.pick(p.x(), p.y());
        List<EventTarget> pickedChain = hierarchy(picked);
        PickResult result = picked != null
                ? new PickResult(picked, p.x(), p.y())
                : new PickResult(top, p.x(), p.y());
        MouseEvent event = newEvent(type, p, result);

        if (type == MouseEvent.MOUSE_PRESSED) {
            pressChain = pickedChain;
            dragDetectDone = false;
            lastPressX = p.x();
            lastPressY = p.y();
        }
        enterExit(event, pickedChain);

        // Scene.DnDGesture.processDragDetection: the default is set before the event is fired,
        // a handler may override it, and it is read back afterwards.
        boolean detectable = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        if (detectable) {
            event.setDragDetect(type == MouseEvent.MOUSE_DRAGGED && !dragDetectDone
                    && p.dragDetectDefault());
        }

        EventTarget target = held || type == MouseEvent.MOUSE_PRESSED
                ? pressChain.get(0) : pickedChain.get(0);
        fire(target, event);

        if (type == MouseEvent.MOUSE_PRESSED) {
            held = true;
        }
        lastEvent = event;
        // A handler may have removed the press target: retarget before anything else uses it.
        removeDetachedTargets();
        if (detectable && !dragDetectDone && event.isDragDetect()) {
            dragDetectDone = true;
            EventTarget detectTarget = pressChain.get(0);
            fire(detectTarget, event.copyFor(event.getSource(), detectTarget, MouseEvent.DRAG_DETECTED));
            removeDetachedTargets();
        }
        if (type == MouseEvent.MOUSE_RELEASED) {
            click(p, result, picked);
            exitAll(event);
        }
        if (type != MouseEvent.MOUSE_MOVED) {
            lastStill = p.stillSincePress();
        }
    }

    private MouseEvent newEvent(EventType<MouseEvent> type, ContactSet.Pointer p, PickResult result) {
        boolean moved = type == MouseEvent.MOUSE_MOVED;
        boolean primaryDown = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        // A MOUSE_MOVED carries the previous gesture's stillSincePress, as Scene's ClickGenerator
        // does, after the move itself has been counted: leaving the box around that press clears it.
        boolean movedStill = lastStill
                && Math.abs(p.x() - lastPressX) <= ContactSet.MULTI_CLICK_PX
                && Math.abs(p.y() - lastPressY) <= ContactSet.MULTI_CLICK_PX;
        return new MouseEvent(type, p.x(), p.y(), p.x(), p.y(),
                moved ? MouseButton.NONE : MouseButton.PRIMARY,
                moved ? 0 : p.clickCount(),
                false, false, false, false,
                primaryDown, false, false,
                true, false, moved ? movedStill : p.stillSincePress(), result);
    }

    /**
     * {@code Scene.MouseHandler.handleEnterExit}: exits/enters the nodes that differ between the
     * previously entered hierarchy and the picked one. While the button is held only nodes in the
     * press hierarchy are entered or exited.
     */
    private void enterExit(MouseEvent event, List<EventTarget> picked) {
        int i = entered.size() - 1;
        int j = picked.size() - 1;
        int k = pressChain.size() - 1;
        while (i >= 0 && j >= 0 && entered.get(i) == picked.get(j)) {
            i--;
            j--;
            k--;
        }
        int memK = k;
        for (; i >= 0; i--, k--) {
            EventTarget exited = entered.get(i);
            if (held && (k < 0 || exited != pressChain.get(k))) {
                break;
            }
            fire(exited, event.copyFor(exited, exited, MouseEvent.MOUSE_EXITED_TARGET));
        }
        k = memK;
        for (; j >= 0; j--, k--) {
            EventTarget enteredNow = picked.get(j);
            if (held && (k < 0 || enteredNow != pressChain.get(k))) {
                break;
            }
            fire(enteredNow, event.copyFor(enteredNow, enteredNow, MouseEvent.MOUSE_ENTERED_TARGET));
        }
        entered = new ArrayList<>(picked.subList(j + 1, picked.size()));
    }

    /** A touch surface has no pointer to leave: after the lift, exit everything (outermost first,
     * as {@code Scene} does for a pointer leaving the scene). */
    private void exitAll(MouseEvent event) {
        held = false;
        pressChain = List.of();
        enterExit(event, List.of());
    }

    /** {@code Scene.ClickGenerator.postProcess}: at the deepest common ancestor, no distance cutoff. */
    private void click(ContactSet.Pointer p, PickResult result, Node picked) {
        // Both hierarchies are rebuilt after the handlers ran, as ClickGenerator.postProcess does,
        // so a handler that removed either end changes where the click lands.
        List<EventTarget> releasedChain = hierarchy(picked);
        int i = pressChain.size() - 1;
        int j = releasedChain.size() - 1;
        EventTarget common = null;
        while (i >= 0 && j >= 0 && pressChain.get(i) == releasedChain.get(j)) {
            common = pressChain.get(i);
            i--;
            j--;
        }
        if (common != null) {
            MouseEvent click = new MouseEvent(null, common, MouseEvent.MOUSE_CLICKED,
                    p.x(), p.y(), p.x(), p.y(), MouseButton.PRIMARY, p.clickCount(),
                    false, false, false, false,
                    false, false, false, false, false,
                    true, false, p.stillSincePress(), result);
            fire(common, click);
        }
    }

    /**
     * {@code Scene.MouseHandler.handleNodeRemoval}, detected structurally on the next event: the
     * press hierarchy is walked and the topmost node no longer linked to its recorded parent is
     * the one removed. Entered nodes up to and including it get {@code MOUSE_EXITED_TARGET}
     * (innermost first), and the press target becomes the nearest remaining ancestor. Unlike
     * JavaFX this fires after the node is detached, so the exits do not bubble to the surviving
     * ancestors; {@code :pressed} on the removed nodes cannot be cleared through public API.
     */
    private void removeDetachedTargets() {
        if (!held || lastEvent == null) {
            return;
        }
        int removed = -1;
        for (int n = 0; n < pressChain.size() - 1; n++) {
            if (!linkedToNext(n)) {
                removed = n; // keep going: the topmost broken link is the removed subtree's root
            }
        }
        if (removed < 0) {
            return;
        }
        EventTarget topmostRemoved = pressChain.get(removed);
        int at = entered.indexOf(topmostRemoved);
        if (at >= 0) {
            List<EventTarget> exiting = new ArrayList<>(entered.subList(0, at + 1));
            entered = new ArrayList<>(entered.subList(at + 1, entered.size()));
            for (EventTarget t : exiting) {
                fire(t, lastEvent.copyFor(t, t, MouseEvent.MOUSE_EXITED_TARGET));
            }
        }
        pressChain = new ArrayList<>(pressChain.subList(removed + 1, pressChain.size()));
    }

    /** Whether {@code pressChain[n]} is still a child of {@code pressChain[n + 1]}; the last node
     * before {@code top} must still be the scene's root (any node, if {@code top} is not a scene). */
    private boolean linkedToNext(int n) {
        Node node = (Node) pressChain.get(n);
        EventTarget next = pressChain.get(n + 1);
        if (next == top) {
            return node.getParent() == null && (!(top instanceof Scene scene) || scene.getRoot() == node);
        }
        return node.getParent() == next;
    }

    /** The node, its ancestors and then {@code top}; just {@code top} for no node. A {@code SubScene}
     * is not followed (Scene's {@code fillHierarchy} does): {@link ScenePicker} never descends into
     * one, and a picker that did would make {@link #linkedToNext} see every event as a removal. */
    private List<EventTarget> hierarchy(Node node) {
        List<EventTarget> chain = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) {
            chain.add(n);
        }
        chain.add(top);
        return chain;
    }

    /**
     * Unlike {@code Scene}, an exception from a handler does not abort the rest of the work for
     * the poll: it goes to the thread's uncaught-exception handler (where applications expect
     * FX handler exceptions to end up) and synthesis carries on, so one throwing handler cannot
     * leave the gesture state stale.
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
