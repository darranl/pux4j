// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.test;

import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

/**
 * Queue-based touch driver for tests. Callers enqueue touch events;
 * {@link #readTouches()} drains one batch per call.
 * Stub implementation — to be completed in Phase 3.
 */
public final class ProgrammaticTouchDriver implements TouchDriver {

    private final Queue<List<TouchPoint>> queue = new ArrayDeque<>();

    public void enqueue(List<TouchPoint> contacts) {
        queue.add(List.copyOf(contacts));
    }

    public void enqueue(TouchPoint... contacts) {
        queue.add(List.of(contacts));
    }

    @Override public void initialize() {}
    @Override public void reset()      { queue.clear(); }

    @Override
    public List<TouchPoint> readTouches() {
        var batch = queue.poll();
        return batch != null ? batch : Collections.emptyList();
    }
}
