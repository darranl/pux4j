// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Maps raw touch IC coordinates to display logical coordinates.
 * The touch IC reports coordinates in its own native space (which may differ in
 * origin, axis direction, and scale from the display's logical space).
 * Mapping parameters are confirmed against the WaveShare demo code and validated
 * with physical corner-touch calibration during Phase 2 implementation.
 */
public final class TouchCoordinateMapper {

    private final int displayWidth;
    private final int displayHeight;
    private final int touchNativeWidth;
    private final int touchNativeHeight;
    private final boolean flipX;
    private final boolean flipY;
    private final boolean swapAxes;

    public TouchCoordinateMapper(
            int displayWidth, int displayHeight,
            int touchNativeWidth, int touchNativeHeight,
            boolean flipX, boolean flipY, boolean swapAxes) {
        this.displayWidth     = displayWidth;
        this.displayHeight    = displayHeight;
        this.touchNativeWidth  = touchNativeWidth;
        this.touchNativeHeight = touchNativeHeight;
        this.flipX    = flipX;
        this.flipY    = flipY;
        this.swapAxes = swapAxes;
    }

    public TouchPoint map(TouchPoint raw) {
        int x = raw.x();
        int y = raw.y();

        if (swapAxes) {
            int tmp = x;
            x = y;
            y = tmp;
        }
        if (flipX) x = touchNativeWidth  - 1 - x;
        if (flipY) y = touchNativeHeight - 1 - y;

        x = Math.round((float) x / touchNativeWidth  * displayWidth);
        y = Math.round((float) y / touchNativeHeight * displayHeight);

        x = Math.clamp(x, 0, displayWidth  - 1);
        y = Math.clamp(y, 0, displayHeight - 1);

        return new TouchPoint(raw.id(), x, y, raw.down());
    }
}
