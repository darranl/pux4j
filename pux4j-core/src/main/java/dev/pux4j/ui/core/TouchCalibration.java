// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * The physical calibration of a touch IC relative to the display it is bonded to: the IC's
 * native coordinate resolution, and the axis flips/swap needed to convert its raw readings
 * into display logical space (see {@link TouchCoordinateMapper}).
 *
 * <p>This is a fixed fact of one specific IC-and-panel pairing — the display and touch panel
 * are physically bonded together and this relationship does not change at runtime — so it is
 * owned by {@link TouchDriverFactory#touchCalibration}, not supplied separately by a caller.
 * Before this type existed, these five values were threaded through command-line flags
 * (`--touch-native-width`, `--flip-x`, `--swap-axes`, etc.) at every invocation, with no
 * single source of truth; that let the two real HAT profiles drift (one launcher script
 * profile silently had none of them set at all).
 *
 * @param nativeWidth  the touch IC's native X reporting range; must be &gt; 0
 * @param nativeHeight the touch IC's native Y reporting range; must be &gt; 0
 * @param flipX        reflect the X axis after the optional {@code swapAxes}
 * @param flipY        reflect the Y axis after the optional {@code swapAxes}
 * @param swapAxes     swap X and Y before the flip and scale transforms
 */
public record TouchCalibration(int nativeWidth, int nativeHeight, boolean flipX, boolean flipY, boolean swapAxes) {

    public TouchCalibration {
        if (nativeWidth <= 0) throw new IllegalArgumentException("nativeWidth must be > 0");
        if (nativeHeight <= 0) throw new IllegalArgumentException("nativeHeight must be > 0");
    }
}
