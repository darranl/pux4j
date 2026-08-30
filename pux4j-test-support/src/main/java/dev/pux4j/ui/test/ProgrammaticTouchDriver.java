// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.test;

import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Queue-based touch driver for tests. Thread-safe.
 * Callers enqueue touch events; {@link #readTouches()} drains one batch per call.
 */
public final class ProgrammaticTouchDriver implements TouchDriver {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticTouchDriver.class);

    private final ConcurrentLinkedQueue<List<TouchPoint>> queue = new ConcurrentLinkedQueue<>();

    /**
     * Enqueues a single touch contact as one read batch.
     */
    public void queueTouch(TouchPoint contact) {
        log.trace("queueTouch id={} x={} y={} down={}", contact.id(), contact.x(), contact.y(), contact.down());
        queue.add(List.of(contact));
    }

    /**
     * Enqueues a multi-contact event as one read batch.
     */
    public void queueTouches(List<TouchPoint> contacts) {
        log.trace("queueTouches count={}", contacts.size());
        queue.add(List.copyOf(contacts));
    }

    @Override public void initialize() {}
    @Override public void reset()      { queue.clear(); }

    @Override
    public List<TouchPoint> readTouches() {
        var batch = queue.poll();
        if (batch != null) {
            log.trace("readTouches returning {} contacts", batch.size());
        }
        return batch != null ? batch : List.of();
    }
}
