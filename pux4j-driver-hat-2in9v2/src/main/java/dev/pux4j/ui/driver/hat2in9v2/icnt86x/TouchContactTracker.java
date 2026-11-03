// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.TouchPoint;

import java.util.List;

/**
 * Turns the ICNT86X's report-driven output into "current contacts" semantics.
 *
 * <p>The chip raises INT only when it has a new report. A finger resting on the panel is reported
 * roughly every 165 ms (up to about 300 ms), and between two reports a poll finds INT high and
 * there is nothing to read, although the finger is still down. Reporting that as "no contacts"
 * makes a consumer see a lift followed by a fresh press on the next report, i.e. a click per
 * report instead of one per touch.
 *
 * <p>So an empty poll is not a lift. The contacts are held until the chip reports a lift (a report
 * with no finger down) or no report has arrived for the hold timeout. The chip announces the lift
 * on only about half of touches (event id 4), so the timeout is the normal way a touch ends. It is
 * deliberately generous: an e-paper panel takes a moment to respond, so users hold longer. Not
 * thread-safe; the touch driver is only read from one thread.
 */
final class TouchContactTracker {

    /** Default hold timeout, in milliseconds; above the longest observed gap between reports of a held finger. */
    static final long DEFAULT_HOLD_TIMEOUT_MS = 500;

    private final long holdTimeoutNanos;
    private List<TouchPoint> held = List.of();
    private long lastReportNanos;

    TouchContactTracker(long holdTimeoutMs) {
        if (holdTimeoutMs < 1) {
            throw new IllegalArgumentException("holdTimeoutMs must be >= 1, got " + holdTimeoutMs);
        }
        this.holdTimeoutNanos = holdTimeoutMs * 1_000_000L;
    }

    /** The chip delivered a report. An empty list means no finger is down: a definite lift. */
    List<TouchPoint> onReport(List<TouchPoint> contacts, long nowNanos) {
        held = List.copyOf(contacts);
        lastReportNanos = nowNanos;
        return held;
    }

    /** A poll that found no new report: the held contacts, unless they have timed out. */
    List<TouchPoint> onNoReport(long nowNanos) {
        if (!held.isEmpty() && nowNanos - lastReportNanos > holdTimeoutNanos) {
            held = List.of();
        }
        return held;
    }

    /** Forget any held contact (after a hardware reset). */
    void clear() {
        held = List.of();
    }
}
