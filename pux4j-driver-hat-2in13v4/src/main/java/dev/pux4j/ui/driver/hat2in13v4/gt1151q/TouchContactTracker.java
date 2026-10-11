// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.gt1151q;

import dev.pux4j.ui.core.TouchPoint;

import java.util.List;

/**
 * Turns the GT1151Q's report-driven output into "current contacts" semantics.
 *
 * <p>The chip raises INT only when it has a new report, and a finger resting on the panel
 * produces reports at the chip's scan rate, not continuously. Between two reports a poll finds
 * INT high and there is nothing to read — but the finger is still down. Reporting that as "no
 * contacts" makes a consumer see a lift followed by a fresh press on the next report, i.e. a
 * click per report instead of one per touch.
 *
 * <p>So an empty poll is not a lift. A contact set is held until either the chip reports an
 * explicit zero-contact frame (a definite lift), or no report has arrived for
 * {@link #HOLD_TIMEOUT_NANOS} (the fallback, in case the chip never sends a zero-contact
 * frame on release). Not thread-safe; the touch driver is only read from one thread.
 */
final class TouchContactTracker {

    /** Longer than any plausible gap between reports of a held finger, short enough that a
     * lift the chip never announces is noticed promptly. */
    static final long HOLD_TIMEOUT_NANOS = 100_000_000L;

    private List<TouchPoint> held = List.of();
    private long lastReportNanos;

    /** The chip delivered a report. An empty list is an explicit lift. */
    List<TouchPoint> onReport(List<TouchPoint> contacts, long nowNanos) {
        held = List.copyOf(contacts);
        lastReportNanos = nowNanos;
        return held;
    }

    /** A poll that found no new report: the held contacts, unless they have timed out. */
    List<TouchPoint> onNoReport(long nowNanos) {
        if (!held.isEmpty() && nowNanos - lastReportNanos > HOLD_TIMEOUT_NANOS) {
            held = List.of();
        }
        return held;
    }

    /** Forget any held contact (after a hardware reset). */
    void clear() {
        held = List.of();
    }
}
