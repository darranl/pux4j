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

    /**
     * Constructs a mapper with the given display dimensions, touch IC native resolution,
     * and axis-transform flags.
     *
     * @param displayWidth      logical display width in pixels; must be &gt; 0
     * @param displayHeight     logical display height in pixels; must be &gt; 0
     * @param touchNativeWidth  touch IC native X range; must be &gt; 0
     * @param touchNativeHeight touch IC native Y range; must be &gt; 0
     * @param flipX             reflect the X axis after optional swapAxes
     * @param flipY             reflect the Y axis after optional swapAxes
     * @param swapAxes          swap X and Y before flip and scale transforms
     * @throws IllegalArgumentException if any dimension argument is &lt;= 0
     */
    public TouchCoordinateMapper(
            int displayWidth, int displayHeight,
            int touchNativeWidth, int touchNativeHeight,
            boolean flipX, boolean flipY, boolean swapAxes) {
        if (displayWidth  <= 0) throw new IllegalArgumentException("displayWidth must be > 0");
        if (displayHeight <= 0) throw new IllegalArgumentException("displayHeight must be > 0");
        if (touchNativeWidth  <= 0) throw new IllegalArgumentException("touchNativeWidth must be > 0");
        if (touchNativeHeight <= 0) throw new IllegalArgumentException("touchNativeHeight must be > 0");
        this.displayWidth     = displayWidth;
        this.displayHeight    = displayHeight;
        this.touchNativeWidth  = touchNativeWidth;
        this.touchNativeHeight = touchNativeHeight;
        this.flipX    = flipX;
        this.flipY    = flipY;
        this.swapAxes = swapAxes;
    }

    /**
     * Applies swapAxes, flip, and scale transforms to a raw {@link TouchPoint},
     * returning a new point in display logical space clamped to display bounds.
     *
     * @param raw touch point in touch IC native coordinate space
     * @return touch point in display logical coordinate space
     */
    public TouchPoint map(TouchPoint raw) {
        int x = raw.x();
        int y = raw.y();

        if (swapAxes) {
            int tmp = x;
            x = y;
            y = tmp;
        }
        if (flipX) { x = touchNativeWidth  - 1 - x; }
        if (flipY) { y = touchNativeHeight - 1 - y; }

        x = Math.round((float) x / touchNativeWidth  * displayWidth);
        y = Math.round((float) y / touchNativeHeight * displayHeight);

        x = Math.clamp(x, 0, displayWidth  - 1);
        y = Math.clamp(y, 0, displayHeight - 1);

        return new TouchPoint(raw.id(), x, y, raw.down());
    }
}
