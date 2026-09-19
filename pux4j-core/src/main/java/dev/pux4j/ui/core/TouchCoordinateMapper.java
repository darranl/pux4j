// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Maps raw touch IC coordinates to display logical coordinates, using a
 * {@link TouchCalibration} — the fixed physical relationship between one specific touch IC
 * and the display it is bonded to.
 *
 * <p>The touch IC reports coordinates in its own native space (which may differ in
 * origin, axis direction, and scale from the display's logical space). This mapper
 * applies three transforms in order:
 * <ol>
 *   <li><strong>swapAxes</strong> — swap X and Y before any other transform (needed when
 *       the IC's X axis maps to the display's Y axis, e.g. some landscape-mounted panels).</li>
 *   <li><strong>flipX / flipY</strong> — reflect each axis against the IC's native range
 *       <em>after</em> any swap (needed when the IC origin is at the opposite corner from
 *       the display origin).</li>
 *   <li><strong>Scale</strong> — linear scale from native resolution to logical pixels.</li>
 * </ol>
 *
 * <p>The flip and scale steps must use the native range for the axis they're currently
 * operating on — which is swapped, after {@code swapAxes}, from the calibration's declared
 * {@code nativeWidth}/{@code nativeHeight}. Getting this wrong is invisible whenever the
 * native resolution happens to be square (the swapped and un-swapped ranges coincide) and
 * silently wrong otherwise; see {@code TouchCoordinateMapperTest} for the regression case
 * (a non-square native resolution with {@code swapAxes}, matching the real WaveShare 2.13"
 * V4 HAT calibration).
 */
public final class TouchCoordinateMapper {

    private final int displayWidth;
    private final int displayHeight;
    private final int effectiveNativeWidth;
    private final int effectiveNativeHeight;
    private final boolean flipX;
    private final boolean flipY;
    private final boolean swapAxes;

    /**
     * Constructs a mapper for the given display dimensions and touch calibration.
     *
     * @param displayWidth  logical display width in pixels; must be &gt; 0
     * @param displayHeight logical display height in pixels; must be &gt; 0
     * @param calibration   the touch IC's physical calibration relative to this display
     * @throws IllegalArgumentException if either dimension argument is &lt;= 0
     */
    public TouchCoordinateMapper(int displayWidth, int displayHeight, TouchCalibration calibration) {
        if (displayWidth  <= 0) throw new IllegalArgumentException("displayWidth must be > 0");
        if (displayHeight <= 0) throw new IllegalArgumentException("displayHeight must be > 0");
        this.displayWidth  = displayWidth;
        this.displayHeight = displayHeight;
        this.flipX    = calibration.flipX();
        this.flipY    = calibration.flipY();
        this.swapAxes = calibration.swapAxes();
        // After swapAxes, a raw X reading ranges over the IC's native *height* (and a raw Y
        // reading over its native width) — the dimensions used for flip and scale must swap
        // along with the coordinates themselves, not stay pinned to the un-swapped
        // nativeWidth/nativeHeight.
        this.effectiveNativeWidth  = swapAxes ? calibration.nativeHeight() : calibration.nativeWidth();
        this.effectiveNativeHeight = swapAxes ? calibration.nativeWidth()  : calibration.nativeHeight();
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
        if (flipX) { x = effectiveNativeWidth  - 1 - x; }
        if (flipY) { y = effectiveNativeHeight - 1 - y; }

        x = Math.round((float) x / effectiveNativeWidth  * displayWidth);
        y = Math.round((float) y / effectiveNativeHeight * displayHeight);

        x = Math.clamp(x, 0, displayWidth  - 1);
        y = Math.clamp(y, 0, displayHeight - 1);

        return new TouchPoint(raw.id(), x, y, raw.down());
    }
}
