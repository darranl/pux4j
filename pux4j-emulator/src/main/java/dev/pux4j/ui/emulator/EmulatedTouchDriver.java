package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;

import java.util.List;

/**
 * Translates JavaFX mouse events into touch contacts.
 * Stub implementation — to be completed in Phase 3.
 */
public final class EmulatedTouchDriver implements TouchDriver {

    @Override public void initialize() {}
    @Override public void reset()      {}

    @Override
    public List<TouchPoint> readTouches() {
        throw new UnsupportedOperationException("EmulatedTouchDriver not yet implemented — Phase 3");
    }
}
