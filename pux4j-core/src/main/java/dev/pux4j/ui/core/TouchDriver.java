package dev.pux4j.ui.core;

import java.util.List;

public interface TouchDriver {

    void initialize();
    void reset();

    /**
     * Read the current active touch contacts from the IC.
     * Returns an empty list if no contacts are active.
     * Coordinates are in touch IC native space — use {@link TouchCoordinateMapper} to
     * convert to display logical space.
     */
    List<TouchPoint> readTouches();
}
