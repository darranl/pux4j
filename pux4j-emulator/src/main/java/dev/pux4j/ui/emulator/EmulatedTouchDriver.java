// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Translates JavaFX mouse press/release events into single-contact touch events.
 * Mouse events are queued by {@link EInkEmulatorWindow}; {@link #readTouches()} drains
 * one batch per call. Thread-safe.
 */
public final class EmulatedTouchDriver implements TouchDriver {

    private static final Logger log = LoggerFactory.getLogger(EmulatedTouchDriver.class);

    private final ConcurrentLinkedQueue<List<TouchPoint>> queue = new ConcurrentLinkedQueue<>();

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

    /** Called by {@link EInkEmulatorWindow} on mouse-pressed; coordinates are in canvas pixels. */
    void onMousePressed(double canvasX, double canvasY, int scaleFactor) {
        int lx = (int) (canvasX / scaleFactor);
        int ly = (int) (canvasY / scaleFactor);
        log.trace("touch down x={} y={}", lx, ly);
        queue.add(List.of(new TouchPoint(0, lx, ly, true)));
    }

    /** Called by {@link EInkEmulatorWindow} on mouse-released; coordinates are in canvas pixels. */
    void onMouseReleased(double canvasX, double canvasY, int scaleFactor) {
        int lx = (int) (canvasX / scaleFactor);
        int ly = (int) (canvasY / scaleFactor);
        log.trace("touch up x={} y={}", lx, ly);
        queue.add(List.of(new TouchPoint(0, lx, ly, false)));
    }
}
