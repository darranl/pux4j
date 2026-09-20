// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.transform.PixelTransforms;
import dev.pux4j.ui.transform.TransformStrategy;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Byte-parity test between {@code pux4j-transform}'s {@link PixelTransforms#transform} (with
 * {@link TransformStrategy#THRESHOLD}) and {@code Canvas}'s existing hardware-verified reference
 * implementation. Lives here, not in {@code pux4j-transform}, because {@link Canvas} is
 * package-private and {@code pux4j-transform} must not depend on {@code pux4j-validation} — see
 * {@code tier2-transform.md}'s "Testing approach" for why.
 *
 * <p>Coordinate-mapping parity (does a pixel land at the <em>right</em> native position) is
 * already fully covered by {@link CanvasOrientationParityTest}, one isolated pixel at a time.
 * This test's job is what that one doesn't cover: does the same varied, multi-pixel,
 * <strong>multi-colour</strong> content — exercising the shared luminance weights and the
 * white-padding initialisation together — pack to <em>identical bytes</em> through both code
 * paths, for every orientation. Uses {@link Canvas#drawImage} (which computes its own
 * independent copy of the luminance formula, separately from {@code packMonochrome}'s per-pixel
 * loop) rather than {@code fillRect}/{@code setBlack}/{@code setWhite}, and random per-channel
 * colours rather than grey, specifically so an R/B channel swap in either implementation would
 * be caught here — a grey-only fixture (B=G=R) cannot detect that class of bug at all, since
 * {@code r} alone maxes out at lum 76 and {@code b} alone at lum 29, both always &lt;128.
 */
class PixelTransformsCanvasParityTest {

    // Deliberately not a multiple of 8 (padding bits exist) and large enough to need several
    // rows/bytes per orientation's native framebuffer, same rationale as CanvasOrientationParityTest.
    private static final int FB_W = 11;
    private static final int FB_H = 19;

    @Test
    void thresholdPackingMatchesCanvasForVariedColourContentInEveryOrientation() {
        for (Orientation orientation : Orientation.values()) {
            OrientationMapping mapping = OrientationMapping.of(FB_W, FB_H, orientation);
            int logicalW = mapping.logicalWidth();
            int logicalH = mapping.logicalHeight();

            Canvas canvas = new Canvas(mapping);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bgra = arena.allocate((long) logicalW * logicalH * 4);
                int[] argb = new int[logicalW * logicalH];
                Random random = new Random(orientation.ordinal() * 1_000_003L + 7);

                for (int ly = 0; ly < logicalH; ly++) {
                    for (int lx = 0; lx < logicalW; lx++) {
                        int b = random.nextInt(256);
                        int g = random.nextInt(256);
                        int r = random.nextInt(256);
                        argb[ly * logicalW + lx] = 0xFF000000 | (r << 16) | (g << 8) | b;

                        long off = ((long) ly * logicalW + lx) * 4;
                        bgra.set(ValueLayout.JAVA_BYTE, off, (byte) b);
                        bgra.set(ValueLayout.JAVA_BYTE, off + 1, (byte) g);
                        bgra.set(ValueLayout.JAVA_BYTE, off + 2, (byte) r);
                        bgra.set(ValueLayout.JAVA_BYTE, off + 3, (byte) 0xFF);
                    }
                }
                canvas.drawImage(argb, logicalW, logicalH, 0, 0);

                byte[] canvasPacked = canvas.packMonochrome();
                MonochromeFrame transformed = (MonochromeFrame) PixelTransforms.transform(
                    bgra, PixelFormat.MONOCHROME, TransformStrategy.THRESHOLD, mapping);

                assertArrayEquals(canvasPacked, transformed.data(), "orientation=" + orientation);
            }
        }
    }
}
