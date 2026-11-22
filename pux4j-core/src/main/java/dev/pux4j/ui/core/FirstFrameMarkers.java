// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import dev.pux4j.ui.core.internal.FirstFrameMarkersDriver;

/**
 * Wraps a display driver so the first frame sent to it is logged: an INFO line
 * {@code first-frame-handed epochMs=<ms>} when the write reaches the driver and
 * {@code first-frame-written epochMs=<ms> writeMs=<ms>} when it completes. A script that records
 * {@code date +%s%3N} before launching an app can then separate start-up time from the panel
 * refresh time. Always on; the cost is one log line pair per run.
 */
public final class FirstFrameMarkers {

    private FirstFrameMarkers() {}

    /** Returns {@code driver} wrapped to log the first-frame markers. */
    public static EInkDisplayDriver wrap(EInkDisplayDriver driver) {
        if (driver instanceof FirstFrameMarkersDriver) {
            return driver;   // already marked: a second decorator would log the markers twice
        }
        return new FirstFrameMarkersDriver(driver);
    }
}
