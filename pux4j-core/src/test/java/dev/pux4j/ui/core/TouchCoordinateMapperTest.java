// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TouchCoordinateMapperTest {

    /**
     * With no flips and no axis swap, and native resolution equal to display resolution,
     * a raw point maps to itself unchanged — the simplest possible case, establishing a
     * baseline before the transform combinations below.
     */
    @Test
    void identityCalibrationMapsPointsUnchanged() {
        var mapper = new TouchCoordinateMapper(296, 128, new TouchCalibration(296, 128, false, false, false));

        assertPoint(mapper, 0, 0, 0, 0);
        assertPoint(mapper, 295, 127, 295, 127);
        assertPoint(mapper, 148, 64, 148, 64);
    }

    /**
     * flipX alone reflects only the X axis against the native width; Y passes through.
     * Native and display resolution are equal here so scaling is a no-op, isolating the
     * flip itself.
     */
    @Test
    void flipXReflectsOnlyXAxis() {
        var mapper = new TouchCoordinateMapper(296, 128, new TouchCalibration(296, 128, true, false, false));

        assertPoint(mapper, 0, 0, 295, 0);
        assertPoint(mapper, 295, 127, 0, 127);
    }

    /**
     * flipY alone reflects only the Y axis against the native height; X passes through.
     */
    @Test
    void flipYReflectsOnlyYAxis() {
        var mapper = new TouchCoordinateMapper(296, 128, new TouchCalibration(296, 128, false, true, false));

        assertPoint(mapper, 0, 0, 0, 127);
        assertPoint(mapper, 295, 127, 295, 0);
    }

    /**
     * swapAxes alone, with a non-square native resolution, transposes raw (x,y) into (y,x)
     * and — this is the part a naive implementation gets wrong — the scale step for each
     * final axis must divide by the *pre-swap* native range of whichever raw axis now feeds
     * it: the final X value came from raw Y (native range {@code nativeHeight}=122), and the
     * final Y value came from raw X (native range {@code nativeWidth}=250). Values below are
     * {@code round(rawValue / itsOwnNativeRange * targetDisplayRange)}, clamped to display
     * bounds.
     */
    @Test
    void swapAxesWithNonSquareNativeResolutionMapsCornersCorrectly() {
        var mapper = new TouchCoordinateMapper(250, 122, new TouchCalibration(250, 122, false, false, true));

        assertPoint(mapper, 0, 0, 0, 0);
        assertPoint(mapper, 249, 0, 0, 121);   // raw Y=0 -> x=0; raw X=249 -> y=round(249/250*122)=121 (clamped from 122)
        assertPoint(mapper, 0, 121, 248, 0);   // raw Y=121 -> x=round(121/122*250)=248; raw X=0 -> y=0
        assertPoint(mapper, 249, 121, 248, 121);
    }

    /**
     * The real WaveShare 2.13" V4 HAT calibration ({@code Gt1151qTouchDriverFactory}:
     * nativeWidth=122, nativeHeight=250, flipX=true, swapAxes=true) — the GT1151Q reports raw
     * coordinates in the same native-portrait frame as the SSD1680 chip (122 wide x 250 tall;
     * confirmed against the vendored WaveShare demo,
     * {@code hardware/common/Touch_e-Paper_Code/python/examples/TP2in13_test.py}, not against
     * {@code hardware/spec/touch-gt1151q.md}, which had this backwards). This test exists to
     * pin down a real regression risk found and fixed 2026-09-19: an earlier attempt at this
     * fix used {@code (250, 122)} — the doc's wrong axis ranges — which is not the same
     * calibration algebraically even though both numbers "look right" individually; that
     * version would have collapsed roughly the upper half of the physical touch surface onto
     * the display's bottom edge. With the correct {@code (122, 250)} calibration, the fixed
     * {@link TouchCoordinateMapper} formula reproduces the pre-existing, 2026-05-17
     * hardware-validated behavior exactly ({@code x = 249 - rawY}, {@code y = rawX}) — this
     * was a genuine behavior-preserving refactor after all, once the constant matched physical
     * reality.
     */
    @Test
    void swapAxesWithFlipXMatchesRealHat2in13v4Calibration() {
        var calibration = new TouchCalibration(122, 250, true, false, true);
        // Display is logical LANDSCAPE_INVERTED size for this HAT: 250 wide x 122 tall.
        var mapper = new TouchCoordinateMapper(250, 122, calibration);

        // raw (x,y) -> x = 249 - rawY, y = rawX
        assertPoint(mapper, 0, 0, 249, 0);
        assertPoint(mapper, 121, 0, 249, 121);
        assertPoint(mapper, 0, 249, 0, 0);
        assertPoint(mapper, 121, 249, 0, 121);
    }

    private static void assertPoint(TouchCoordinateMapper mapper, int rawX, int rawY, int expectedX, int expectedY) {
        TouchPoint mapped = mapper.map(new TouchPoint(0, rawX, rawY, true));
        assertEquals(expectedX, mapped.x(), "x for raw (" + rawX + "," + rawY + ")");
        assertEquals(expectedY, mapped.y(), "y for raw (" + rawX + "," + rawY + ")");
    }
}
