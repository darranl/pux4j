// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the raw per-poll contact lists from a touch driver into JavaFX-style touch sets and a
 * single mouse-pointer description. Holds all the gesture state, contains no JavaFX types and is
 * driven by the caller's clock, so it is unit-testable without a toolkit. Not thread-safe: use it
 * from one thread (the touch thread). See {@code docs/input-event-mapping.md} for the rules it
 * implements (modelled on Monocle's touch filters and {@code Scene}'s click counting).
 *
 * <p>Raw ids from the drivers are not trusted: the GT1151Q renumbers contacts and the ICNT86X's
 * id is an array index. Contacts keep an identity of their own (ids 1, 2, … within a gesture,
 * not reused until every contact has lifted); when the set of raw ids changes, readings are
 * paired with contacts by nearest position.
 */
final class ContactSet {

    private static final Logger LOG = LoggerFactory.getLogger(ContactSet.class);

    /** A move whose straight-line distance from the last passed-on position is below this is
     * dropped (Monocle's {@code touchRadius}); each reported move can cost an e-ink refresh. */
    static final double DEAD_BAND_PX = 20.0;
    /** Per-axis distance for click counting, {@code stillSincePress} and the drag-detect default
     * (Monocle's multi-click distance). */
    static final double MULTI_CLICK_PX = 20.0;
    static final long MULTI_CLICK_NANOS = 500_000_000L;
    /** A reading farther than this plus {@link #MAX_SPEED_PX_PER_MS} times the time since the
     * contact's last reading cannot be the same finger. Starting values, to be tuned from
     * captures on the real panels. */
    static final double JUMP_SLACK_PX = 20.0;
    static final double MAX_SPEED_PX_PER_MS = 10.0;

    enum State { PRESSED, MOVED, STATIONARY, RELEASED }

    /** One contact's entry in a touch set; {@code x}/{@code y} are the passed-on position
     * (for {@code RELEASED}, the last one). */
    record ContactChange(int id, State state, double x, double y) {}

    /**
     * The mouse pointer's activity in one poll. {@code buttonDown}/{@code buttonUp} are the
     * transitions (first contact down / last contact up); {@code buttonHeld} is the state after
     * the poll. {@code dragDetectDefault} is JavaFX's default for the poll (per-axis distance from
     * the mouse press beyond the multi-click distance); whether {@code DRAG_DETECTED} has
     * already fired is the consumer's to track, since a handler can force it.
     */
    record Pointer(boolean moved, double x, double y, boolean buttonDown, boolean buttonUp,
                   boolean buttonHeld, int primaryId, double pressX, double pressY,
                   int clickCount, boolean stillSincePress, boolean dragDetectDefault) {}

    /** {@code setId} is 0 and {@code changes} empty when there is no touch set; {@code pointer}
     * is {@code null} when the mouse pointer did nothing. */
    record PollResult(int setId, List<ContactChange> changes, Pointer pointer) {
        static final PollResult NONE = new PollResult(0, List.of(), null);
    }

    private static final class Contact {
        int rawId;
        final int id;
        double readX, readY;     // last reading
        double anchorX, anchorY; // last passed-on position
        long readNanos;

        Contact(int rawId, int id, double x, double y, long nanos) {
            this.rawId = rawId;
            this.id = id;
            readX = anchorX = x;
            readY = anchorY = y;
            readNanos = nanos;
        }

        double allowedMove(long nowNanos) {
            return JUMP_SLACK_PX + MAX_SPEED_PX_PER_MS * Math.max(0, nowNanos - readNanos) / 1e6;
        }
    }

    private final List<Contact> active = new ArrayList<>();
    private int nextId = 1;
    private int setCounter;

    // Mouse pointer state; survives the end of a gesture so a double tap can be counted.
    private double lastPointerX = Double.NaN;
    private double lastPointerY = Double.NaN;
    private double pressX;
    private double pressY;
    private int clickCount;
    private boolean clickOut;
    private boolean still;
    private long lastPressNanos;

    boolean hasActiveContacts() {
        return !active.isEmpty();
    }

    /** Forgets everything without producing a result. */
    void clear() {
        active.clear();
        nextId = 1;
        setCounter = 0;
        lastPointerX = lastPointerY = Double.NaN;
        clickCount = 0;
        clickOut = false;
        still = false;
    }

    /**
     * @param contacts what the driver reported this poll (logical space)
     * @param nowNanos monotonic time sampled right after the read
     */
    PollResult poll(List<TouchPoint> contacts, long nowNanos) {
        // 1. Reduce to the final state per raw id.
        Map<Integer, TouchPoint> last = new LinkedHashMap<>();
        for (TouchPoint p : contacts) {
            if (last.put(p.id(), p) != null) {
                LOG.warn("Duplicate raw touch id {} in one report; keeping the last", p.id());
            }
        }
        List<TouchPoint> readings = new ArrayList<>();
        List<Contact> explicitUps = new ArrayList<>();
        for (TouchPoint p : last.values()) {
            if (p.down()) {
                readings.add(p);
            } else {
                Contact c = findByRaw(p.id());
                if (c != null) {
                    explicitUps.add(c);
                }
            }
        }

        boolean wasDown = !active.isEmpty();
        List<ContactChange> changes = new ArrayList<>();

        // 2. Definite lifts.
        List<Contact> candidates = new ArrayList<>(active);
        candidates.removeAll(explicitUps);
        List<Contact> lifted = new ArrayList<>(explicitUps);

        // 3. Pair readings with contacts.
        boolean sameIds = explicitUps.isEmpty() && readings.size() == candidates.size()
                && readings.stream().allMatch(r -> findByRaw(r.id()) != null);
        List<TouchPoint> unmatchedReadings = new ArrayList<>(readings);
        Map<Contact, TouchPoint> matched = new LinkedHashMap<>();
        if (sameIds) {
            for (TouchPoint r : readings) {
                Contact c = findByRaw(r.id());
                if (Math.hypot(r.x() - c.readX, r.y() - c.readY) <= c.allowedMove(nowNanos)) {
                    matched.put(c, r);
                    unmatchedReadings.remove(r);
                }
            }
        } else {
            record Pair(Contact c, TouchPoint r, double d) {}
            List<Pair> pairs = new ArrayList<>();
            for (Contact c : candidates) {
                for (TouchPoint r : readings) {
                    double d = Math.hypot(r.x() - c.readX, r.y() - c.readY);
                    if (d <= c.allowedMove(nowNanos)) {
                        pairs.add(new Pair(c, r, d));
                    }
                }
            }
            pairs.sort(Comparator.comparingDouble(Pair::d));
            for (Pair pr : pairs) {
                if (!matched.containsKey(pr.c()) && unmatchedReadings.contains(pr.r())) {
                    matched.put(pr.c(), pr.r());
                    unmatchedReadings.remove(pr.r());
                }
            }
        }
        for (Contact c : candidates) {
            if (!matched.containsKey(c)) {
                lifted.add(c); // inferred lift
            }
        }

        // 4. Apply: lifts, moves, presses.
        boolean setChanged = false;
        for (Contact c : lifted) {
            active.remove(c);
            changes.add(new ContactChange(c.id, State.RELEASED, c.anchorX, c.anchorY));
            setChanged = true;
        }
        List<ContactChange> stationary = new ArrayList<>();
        for (Map.Entry<Contact, TouchPoint> e : matched.entrySet()) {
            Contact c = e.getKey();
            TouchPoint r = e.getValue();
            c.rawId = r.id();
            c.readX = r.x();
            c.readY = r.y();
            c.readNanos = nowNanos;
            if (Math.hypot(r.x() - c.anchorX, r.y() - c.anchorY) < DEAD_BAND_PX) {
                stationary.add(new ContactChange(c.id, State.STATIONARY, c.anchorX, c.anchorY));
            } else {
                c.anchorX = r.x();
                c.anchorY = r.y();
                changes.add(new ContactChange(c.id, State.MOVED, c.anchorX, c.anchorY));
                setChanged = true;
            }
        }
        for (TouchPoint r : unmatchedReadings) {
            Contact c = new Contact(r.id(), nextId++, r.x(), r.y(), nowNanos);
            active.add(c);
            changes.add(new ContactChange(c.id, State.PRESSED, c.anchorX, c.anchorY));
            setChanged = true;
        }

        // 5. The touch set.
        int setId = 0;
        List<ContactChange> set = List.of();
        if (setChanged) {
            changes.addAll(stationary);
            changes.sort(Comparator.comparingInt(ContactChange::id));
            set = List.copyOf(changes);
            setId = ++setCounter;
        }

        // 6. The mouse pointer.
        boolean nowDown = !active.isEmpty();
        Pointer pointer = null;
        if (wasDown || nowDown) {
            Contact primary = active.stream().min(Comparator.comparingInt(c -> c.id)).orElse(null);
            double px = primary != null ? primary.anchorX : lastPointerX;
            double py = primary != null ? primary.anchorY : lastPointerY;
            boolean moved = nowDown && (px != lastPointerX || py != lastPointerY);
            boolean down = !wasDown && nowDown;
            boolean up = wasDown && !nowDown;
            if (moved || down || up) {
                if (moved) {
                    clickMoved(px, py);
                }
                if (down) {
                    pressX = px;
                    pressY = py;
                    clickPress(px, py, nowNanos);
                }
                boolean dragDefault = nowDown && (Math.abs(px - pressX) > MULTI_CLICK_PX
                        || Math.abs(py - pressY) > MULTI_CLICK_PX);
                pointer = new Pointer(moved, px, py, down, up, nowDown,
                        primary != null ? primary.id : 0, pressX, pressY,
                        clickCount, still, dragDefault);
                if (nowDown) {
                    lastPointerX = px;
                    lastPointerY = py;
                }
            }
        }

        if (active.isEmpty()) {
            nextId = 1;
            setCounter = 0;
        }
        if (setId == 0 && pointer == null) {
            return PollResult.NONE;
        }
        return new PollResult(setId, set, pointer);
    }

    private Contact findByRaw(int rawId) {
        for (Contact c : active) {
            if (c.rawId == rawId) {
                return c;
            }
        }
        return null;
    }

    /** {@code Scene.ClickCounter.moved}: leaving the box around the last press is sticky. */
    private void clickMoved(double x, double y) {
        if (Math.abs(x - pressX) > MULTI_CLICK_PX || Math.abs(y - pressY) > MULTI_CLICK_PX) {
            clickOut = true;
            still = false;
        }
    }

    /** {@code Scene.ClickCounter} press handling. {@code stillSincePress} is reported true on
     * the press itself (JavaFX reports the previous gesture's value there). */
    private void clickPress(double x, double y, long nowNanos) {
        if (clickCount > 0 && nowNanos - lastPressNanos >= MULTI_CLICK_NANOS) {
            clickOut = true;
        }
        if (clickOut) {
            clickCount = 0;
        }
        clickCount++;
        clickOut = false;
        still = true;
        lastPressNanos = nowNanos;
    }
}
