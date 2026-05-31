// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import java.util.concurrent.CompletableFuture;

/**
 * Low-level driver for a WaveShare eInk display IC.
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #initialize()} once after construction before any frame writes.
 * After use, call {@link #sleep()} to put the IC into low-power standby.
 * Call {@link #wake()} to resume from sleep. Drivers that implement
 * {@link AutoCloseable} release SPI/I2C resources on {@code close()}.
 *
 * <h2>Threading</h2>
 * <p>{@link #writeFrame} and {@link #writeRegion} return immediately; hardware I/O
 * executes on a virtual thread. The returned future completes when the BUSY pin clears.
 * All other methods execute synchronously on the calling thread.
 *
 * <h2>RefreshPolicy</h2>
 * <p>Install a {@link RefreshPolicy} via {@link #setRefreshPolicy} to automatically
 * upgrade partial refreshes to full ones after a configurable threshold, limiting
 * pixel-voltage drift on panels used heavily with partial refresh.
 */
public interface EInkDisplayDriver {

    /** Returns the logical display width in pixels for the current orientation. */
    int getWidth();

    /** Returns the logical display height in pixels for the current orientation. */
    int getHeight();

    /** Returns the orientation this driver was configured with. */
    Orientation getOrientation();

    /** Returns the static capability descriptor for this display IC. */
    DisplayCapabilities getCapabilities();

    /**
     * Initialises the display IC: sends the power-on command sequence and loads
     * waveform LUTs. Must be called once before any frame write.
     */
    void initialize();

    /**
     * Issues a hardware reset to the display IC and re-runs the initialisation sequence.
     */
    void reset();

    /**
     * Puts the display IC into low-power deep-sleep mode.
     * Call {@link #wake()} to resume.
     */
    void sleep();

    /**
     * Wakes the display IC from deep sleep and re-runs the initialisation sequence.
     */
    void wake();

    /**
     * Writes pixel data and triggers a display refresh; returns immediately.
     * Hardware I/O executes on a virtual thread; the future completes when the BUSY pin clears.
     *
     * <p>Before dispatching, the driver evaluates the installed {@link RefreshPolicy}.
     * If the policy returns {@code true}, a FULL refresh is performed instead of the
     * requested mode, and the refresh counters are reset.
     *
     * @param frame pixel data; accepted types depend on the display's {@link DisplayCapabilities}
     * @return a future that completes when the display has finished refreshing
     */
    CompletableFuture<Void> writeFrame(FrameData frame);

    /**
     * Writes a rectangular sub-region and triggers a partial refresh; returns immediately.
     * Hardware I/O executes on a virtual thread; the future completes when the BUSY pin clears.
     * Coordinates must satisfy {@link DisplayCapabilities#partialAlignment()}.
     *
     * @param x      left edge of the region in logical pixels
     * @param y      top edge of the region in logical pixels
     * @param width  region width in logical pixels
     * @param height region height in logical pixels
     * @param frame  pixel data for the region; must be a {@link MonochromeFrame}
     * @return a future that completes when the display has finished refreshing
     * @throws UnsupportedOperationException if the driver does not support partial refresh
     *         or if {@code frame} is a {@link FourGrayFrame}
     */
    CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame);

    /**
     * Returns a snapshot of the driver's refresh activity counters.
     * Counters reset to zero after every FULL refresh.
     */
    default RefreshStats getRefreshStats() {
        return new RefreshStats(0, 0, 0L);
    }

    /**
     * Installs a {@link RefreshPolicy} that the driver evaluates before each
     * {@link #writeFrame} call. The default policy is {@link RefreshPolicy#NEVER}.
     *
     * <p>Use {@link RefreshPolicy#afterPartials(int)} for threshold-based upgrades,
     * or supply a custom lambda to compose any behaviour you need.
     */
    default void setRefreshPolicy(RefreshPolicy policy) {
        // no-op for drivers that do not implement policy
    }
}

