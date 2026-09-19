// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientationMappingTest {

    // 11x19 (narrow x tall) mirrors the real portrait-shaped native framebuffer shape
    // (e.g. 122x250, 128x296) at a size small enough to enumerate exhaustively and to
    // hand-derive expected corner coordinates for. Deliberately >8 and not a multiple of 8:
    // this type's own coordinate math has no byte-packing in it, but its sibling test
    // (CanvasOrientationParityTest, pux4j-validation) needs exactly this property to exercise
    // Canvas's bit-packing correctly, so both use the same-shaped fixture for consistency.
    private static final int FB_W = 11;
    private static final int FB_H = 19;

    /**
     * For every orientation and every logical pixel, converting logical -> native -> logical
     * must return the original coordinate. This is the weakest possible correctness property
     * (it would also pass for a mapping that scrambled pixels consistently), but it catches
     * off-by-one errors and inverse-formula mistakes cheaply across the whole logical grid
     * before the more targeted corner/handedness tests below spend effort on specific cases.
     */
    @Test
    void roundTripsThroughLogicalAndNativeSpace() {
        for (Orientation orientation : Orientation.values()) {
            OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, orientation);

            for (int ly = 0; ly < mapping.logicalHeight(); ly++) {
                for (int lx = 0; lx < mapping.logicalWidth(); lx++) {
                    int nx = mapping.nativeX(lx, ly);
                    int ny = mapping.nativeY(lx, ly);
                    assertEquals(lx, mapping.logicalX(nx, ny),
                        "logicalX round-trip failed at (" + lx + "," + ly + ") for " + orientation);
                    assertEquals(ly, mapping.logicalY(nx, ny),
                        "logicalY round-trip failed at (" + lx + "," + ly + ") for " + orientation);
                }
            }
        }
    }

    /**
     * The reverse direction of {@link #roundTripsThroughLogicalAndNativeSpace()}: for every
     * native framebuffer pixel, converting native -> logical -> native must return the
     * original coordinate, and the intermediate logical coordinate must fall within
     * {@code [0, logicalWidth) x [0, logicalHeight)}. This direction matters on its own —
     * {@code EmulatedEInkDisplay} uses {@code logicalX}/{@code logicalY} as its *primary*
     * direction (native chip data in, on-screen pixels out) — and the forward round-trip
     * above cannot catch a bug here: a logical->native->logical round trip can still hold
     * even if the native->logical formulas independently produce out-of-range results for
     * some native inputs (e.g. a sign error that happens to cancel out on the way back).
     */
    @Test
    void roundTripsThroughNativeAndLogicalSpace() {
        for (Orientation orientation : Orientation.values()) {
            OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, orientation);

            for (int ny = 0; ny < mapping.framebufferHeight(); ny++) {
                for (int nx = 0; nx < mapping.framebufferWidth(); nx++) {
                    int lx = mapping.logicalX(nx, ny);
                    int ly = mapping.logicalY(nx, ny);
                    assertTrue(lx >= 0 && lx < mapping.logicalWidth(),
                        "logicalX out of range at native (" + nx + "," + ny + ") for " + orientation + ": " + lx);
                    assertTrue(ly >= 0 && ly < mapping.logicalHeight(),
                        "logicalY out of range at native (" + nx + "," + ny + ") for " + orientation + ": " + ly);

                    assertEquals(nx, mapping.nativeX(lx, ly),
                        "nativeX round-trip failed at (" + nx + "," + ny + ") for " + orientation);
                    assertEquals(ny, mapping.nativeY(lx, ly),
                        "nativeY round-trip failed at (" + nx + "," + ny + ") for " + orientation);
                }
            }
        }
    }

    /**
     * {@code LANDSCAPE} is a 90-degree rotation of the native framebuffer: hand-derives the
     * four logical-corner -> native-corner mappings from {@code Canvas.mapToFramebuffer}'s
     * {@code {ly, fbH-1-lx}} formula and checks each one explicitly, plus that
     * {@code logicalWidth}/{@code logicalHeight} report the rotated (swapped) dimensions. This
     * pins down the exact rotation direction — a test that only checked dimensions or
     * round-tripping would pass equally well for the mirror-image rotation.
     */
    @Test
    void landscapeMapsCorners() {
        OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, Orientation.LANDSCAPE);
        assertEquals(19, mapping.logicalWidth());
        assertEquals(11, mapping.logicalHeight());

        assertCorner(mapping, 0, 0, 0, 18);
        assertCorner(mapping, 18, 0, 0, 0);
        assertCorner(mapping, 0, 10, 10, 18);
        assertCorner(mapping, 18, 10, 10, 0);
    }

    /**
     * Same intent as {@link #landscapeMapsCorners()} but for {@code LANDSCAPE_INVERTED} (the
     * 90-degree rotation in the opposite direction from {@code LANDSCAPE}), derived from
     * {@code Canvas.mapToFramebuffer}'s {@code {fbW-1-ly, lx}} formula. Distinguishing this
     * from plain {@code LANDSCAPE} matters because both share the same dimension swap and
     * would look identical to a test that only checked {@code logicalWidth}/{@code Height}.
     */
    @Test
    void landscapeInvertedMapsCorners() {
        OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, Orientation.LANDSCAPE_INVERTED);
        assertEquals(19, mapping.logicalWidth());
        assertEquals(11, mapping.logicalHeight());

        assertCorner(mapping, 0, 0, 10, 0);
        assertCorner(mapping, 18, 0, 10, 18);
        assertCorner(mapping, 0, 10, 0, 0);
        assertCorner(mapping, 18, 10, 0, 18);
    }

    /**
     * {@code PORTRAIT} is the identity mapping (the native framebuffer is already
     * portrait-shaped, so no rotation is needed) — checks logical dimensions equal the
     * framebuffer dimensions unchanged, and that native coordinates equal logical coordinates
     * at each sampled corner.
     */
    @Test
    void portraitIsIdentity() {
        OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, Orientation.PORTRAIT);
        assertEquals(11, mapping.logicalWidth());
        assertEquals(19, mapping.logicalHeight());

        assertCorner(mapping, 0, 0, 0, 0);
        assertCorner(mapping, 10, 0, 10, 0);
        assertCorner(mapping, 0, 18, 0, 18);
        assertCorner(mapping, 10, 18, 10, 18);
    }

    /**
     * {@code PORTRAIT_INVERTED} is a 180-degree rotation (both axes flipped, no swap) —
     * checks logical dimensions equal the framebuffer dimensions unchanged (no axis swap,
     * unlike the landscape orientations) and that each sampled corner maps to the
     * diagonally-opposite corner.
     */
    @Test
    void portraitInvertedMapsCorners() {
        OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, Orientation.PORTRAIT_INVERTED);
        assertEquals(11, mapping.logicalWidth());
        assertEquals(19, mapping.logicalHeight());

        assertCorner(mapping, 0, 0, 10, 18);
        assertCorner(mapping, 10, 0, 0, 18);
        assertCorner(mapping, 0, 18, 10, 0);
        assertCorner(mapping, 10, 18, 0, 0);
    }

    /**
     * Every orientation's coordinate mapping must be a proper rotation (determinant +1),
     * never a mirror/transpose (determinant -1) — the two are indistinguishable on symmetric
     * test content but produce a flipped image on real hardware. A real diary entry
     * (2026-08-30) recorded exactly this defect from an earlier transpose-based attempt at
     * this mapping. {@code OrientationMapping}'s constructor now throws for any orientation
     * whose determinant isn't +1 (impossible to reach with the four fixed orientations here,
     * short of editing the class itself) — this test is the black-box companion check: it
     * exercises the same property through the public API, independent of the internal
     * representation, so it keeps failing if that internal enforcement is ever weakened or
     * removed.
     *
     * <p>Tests this algebraically rather than by picture: computes the images of the two
     * logical unit basis vectors (moving one step in X, then one step in Y, from the origin)
     * under the native-space mapping, then takes the determinant of the resulting 2x2 matrix.
     * Covers all four orientations, not just the two landscape ones — {@code PORTRAIT_INVERTED}
     * is a 180-degree rotation (also determinant +1) and a transpose-shaped mistake there
     * would be exactly as invisible on symmetric content as the original landscape bug.
     */
    @Test
    void everyOrientationIsARotationNotAMirror() {
        for (Orientation orientation : Orientation.values()) {
            OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, orientation);

            int originX = mapping.nativeX(0, 0);
            int originY = mapping.nativeY(0, 0);
            int dxLogicalX = mapping.nativeX(1, 0) - originX;
            int dyLogicalX = mapping.nativeY(1, 0) - originY;
            int dxLogicalY = mapping.nativeX(0, 1) - originX;
            int dyLogicalY = mapping.nativeY(0, 1) - originY;

            int determinant = dxLogicalX * dyLogicalY - dyLogicalX * dxLogicalY;
            assertEquals(1, determinant,
                "expected a proper rotation (determinant +1), got a mirror for " + orientation);
        }
    }

    /** Asserts a single logical -> native corner mapping ({@link #landscapeMapsCorners()} etc). */
    private static void assertCorner(OrientationMapping mapping, int lx, int ly, int expectedNativeX, int expectedNativeY) {
        assertEquals(expectedNativeX, mapping.nativeX(lx, ly), "nativeX at logical (" + lx + "," + ly + ")");
        assertEquals(expectedNativeY, mapping.nativeY(lx, ly), "nativeY at logical (" + lx + "," + ly + ")");
    }
}
