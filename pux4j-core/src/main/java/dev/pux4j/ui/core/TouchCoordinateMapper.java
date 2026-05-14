// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Maps raw touch IC coordinates to display logical coordinates.
 *
 * <p>The touch IC reports coordinates in its own native space (which may differ in
 * origin, axis direction, and scale from the display's logical space). This mapper
 * applies three transforms in order:
 * <ol>
 *   <li><strong>swapAxes</strong> — swap X and Y before any other transform (needed when
 *       the IC's X axis maps to the display's Y axis, e.g. some landscape-mounted panels).</li>
 *   <li><strong>flipX / flipY</strong> — reflect each axis: {@code x = nativeWidth - 1 - x}
 *       (needed when the IC origin is at the opposite corner from the display origin).</li>
 *   <li><strong>Scale</strong> — linear scale from native resolution to logical pixels.</li>
 * </ol>
 *
 * <p>Correct parameter values for the WaveShare 2.9" V2 HAT (ICNT86X, landscape):
 * <pre>
 *   touchNativeWidth  = 296  (IC reports pixel coords in display's X range)
 *   touchNativeHeight = 128  (IC reports pixel coords in display's Y range)
 *   flipX = true, flipY = true  (IC origin is at bottom-right in landscape)
 *   swapAxes = false
 * </pre>
 *
 * <p>Calibrate by running the hardware validation test corner-touch scenarios and
 * adjusting flipX/flipY/swapAxes until all four corners register correctly.
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
        assert displayWidth  > 0 : "displayWidth must be > 0";
        assert displayHeight > 0 : "displayHeight must be > 0";
        assert touchNativeWidth  > 0 : "touchNativeWidth must be > 0";
        assert touchNativeHeight > 0 : "touchNativeHeight must be > 0";
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
