// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-parity test: {@code pux4j-core}'s {@link OrientationMapping} must place a single black
 * pixel at exactly the framebuffer position {@link Canvas#packMonochrome()} (hardware-verified,
 * unchanged by this test) already places it at.
 *
 * <p>This is what turns the {@code OrientationMapping} port into a provable refactor of
 * {@code Canvas.mapToFramebuffer} rather than a rewrite — it exercises the real,
 * hardware-verified packing/bit-layout code, not a reimplementation of it, and compares its
 * output against the new type pixel-by-pixel rather than trusting that the two formulas were
 * transcribed correctly by eye. See {@code notes/project-plan.md} Phase 6.0.
 */
class CanvasOrientationParityTest {

    // 11x19 (narrow x tall) mirrors the real portrait-shaped native framebuffer shape
    // (e.g. 122x250, 128x296). Deliberately >8 and not a multiple of 8: at FB_W=8 or less,
    // fbRowBytes is always 1 and every pixel lands in byte 0, so the byte-index half of
    // packMonochrome's addressing (idx = fy*fbRowBytes + fx/8) is never exercised — an
    // off-by-8 or byte/bit confusion in the mapping would be invisible. 11 forces fx/8 to
    // actually vary (bytes 0 and 1 both used), while still leaving row padding (5 bits) to
    // make sure that padding isn't accidentally counted as a pixel (see
    // assertSingleBlackBitAt below).
    private static final int FB_W = 11;
    private static final int FB_H = 19;
    private static final int FB_ROW_BYTES = (FB_W + 7) / 8;

    /**
     * For every orientation and every logical pixel: renders a {@code Canvas} with exactly
     * that one pixel set black (all others left white, the default), packs it via
     * {@link Canvas#packMonochrome()}, and asserts that the single black bit in the packed
     * output lands at the framebuffer coordinate {@link OrientationMapping#nativeX} /
     * {@link OrientationMapping#nativeY} predicts for that logical pixel — and nowhere else.
     *
     * <p>Testing one isolated pixel at a time (rather than, say, a filled rectangle or a
     * fixed test pattern) means each assertion pins down that exact logical->native
     * coordinate pair with no ambiguity from neighbouring pixels, so an off-by-one or a
     * swapped axis in either implementation is caught at the specific pixel it affects.
     */
    @Test
    void packMonochromeAgreesWithOrientationMappingForEveryPixel() {
        for (Orientation orientation : Orientation.values()) {
            OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, orientation);

            for (int ly = 0; ly < mapping.logicalHeight(); ly++) {
                for (int lx = 0; lx < mapping.logicalWidth(); lx++) {
                    Canvas canvas = new Canvas(mapping);
                    canvas.setBlack();
                    canvas.fillRect(lx, ly, 1, 1);

                    byte[] packed = canvas.packMonochrome();

                    assertSingleBlackBitAt(packed, mapping.nativeX(lx, ly), mapping.nativeY(lx, ly), orientation, lx, ly);
                }
            }
        }
    }

    /**
     * Asserts the packed framebuffer has a black (cleared) bit at exactly the given native
     * coordinate, and that it is the <em>only</em> black bit among the real pixels — ruling
     * out both "wrong position" (a mapping bug) and "right position plus stray extra bits"
     * (a packing/indexing bug) in one check.
     *
     * <p>Counts only bits at valid pixel columns ({@code fx} in {@code [0, FB_W)}), not every
     * bit in the byte array: each row is padded out to a whole number of bytes
     * ({@code FB_ROW_BYTES * 8 - FB_W} padding bits per row), and {@code packMonochrome}
     * pre-fills the whole buffer white (bit 1). Counting the full byte array would silently
     * couple this assertion to that padding convention instead of to actual pixel placement.
     */
    private static void assertSingleBlackBitAt(byte[] packed, int expectedX, int expectedY, Orientation orientation, int lx, int ly) {
        int idx = expectedY * FB_ROW_BYTES + (expectedX / 8);
        int bit = 7 - (expectedX % 8);
        boolean isBlack = (packed[idx] & (1 << bit)) == 0;
        assertTrue(isBlack, "expected black bit at framebuffer (" + expectedX + "," + expectedY
            + ") for logical (" + lx + "," + ly + ") orientation " + orientation);

        int blackBitCount = 0;
        for (int fy = 0; fy < FB_H; fy++) {
            for (int fx = 0; fx < FB_W; fx++) {
                int pixelIdx = fy * FB_ROW_BYTES + (fx / 8);
                int pixelBit = 7 - (fx % 8);
                if ((packed[pixelIdx] & (1 << pixelBit)) == 0) {
                    blackBitCount++;
                }
            }
        }
        assertEquals(1, blackBitCount, "expected exactly one black pixel for logical (" + lx + "," + ly
            + ") orientation " + orientation);
    }
}
