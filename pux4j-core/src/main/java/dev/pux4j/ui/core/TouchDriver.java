// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import java.util.List;

/**
 * Low-level driver for a capacitive touch IC mounted on a WaveShare eInk HAT.
 * Coordinates returned by {@link #readTouches()} are in touch IC native space;
 * use {@link TouchCoordinateMapper} to convert them to display logical space.
 */
public interface TouchDriver {

    /**
     * Initialises the touch IC. Must be called once before {@link #readTouches()}.
     */
    void initialize();

    /**
     * Issues a hardware reset to the touch IC and re-runs the initialisation sequence.
     */
    void reset();

    /**
     * Read the current active touch contacts from the IC.
     * Returns an empty list if no contacts are active.
     * Coordinates are in touch IC native space — use {@link TouchCoordinateMapper} to
     * convert to display logical space.
     */
    List<TouchPoint> readTouches();
}
