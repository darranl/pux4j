// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Stateless single-frame BGRA → {@link FrameData} conversion. No diff, no state — see
 * {@link RenderSession} for the stateful pipeline that also diffs against the previous frame
 * and picks a refresh strategy.
 *
 * <p>Input is BGRA (JavaFX's {@code PixelFormat.getByteBgraInstance()} byte order: Blue, Green,
 * Red, Alpha), in <em>logical</em> space. Output is packed in <em>native framebuffer</em> space —
 * the rotation implied by {@code mapping}'s {@link Orientation} is applied inside the packing
 * loop, exactly as {@code Canvas.packMonochrome} does in {@code pux4j-validation}.
 */
public final class PixelTransforms {

    private PixelTransforms() {}

    /**
     * Converts a logical-space BGRA pixel buffer into native-space {@link FrameData} for the
     * given {@link PixelFormat} and {@link TransformStrategy}, using the module's default 4-gray
     * classification boundaries ({@link RenderOptions}'s own default — see
     * {@code RenderOptions.defaultFourGrayBinBoundaries()}) when {@code strategy} is
     * {@link TransformStrategy#FOUR_GRAY_BINNING}.
     *
     * @throws IllegalArgumentException if {@code format}/{@code strategy} are incompatible, or if
     *         {@code bgra} is smaller than {@code mapping.logicalWidth() * mapping.logicalHeight() * 4}
     *         bytes
     */
    public static FrameData transform(MemorySegment bgra, PixelFormat format,
                                       TransformStrategy strategy, OrientationMapping mapping) {
        return transform(bgra, format, strategy, mapping, RenderOptions.defaultFourGrayBinBoundaries());
    }

    /**
     * As {@link #transform(MemorySegment, PixelFormat, TransformStrategy, OrientationMapping)},
     * with explicit 4-gray classification boundaries (ignored unless {@code strategy} is
     * {@link TransformStrategy#FOUR_GRAY_BINNING}).
     *
     * @param fourGrayBinBoundaries three ascending greyscale thresholds {@code [lo, mid, hi]};
     *                              see {@code RenderOptions.fourGrayBinBoundaries()}
     */
    public static FrameData transform(MemorySegment bgra, PixelFormat format,
                                       TransformStrategy strategy, OrientationMapping mapping,
                                       float[] fourGrayBinBoundaries) {
        requireCompatible(format, strategy);

        long required = (long) mapping.logicalWidth() * mapping.logicalHeight() * 4L;
        if (bgra.byteSize() < required) {
            throw new IllegalArgumentException("bgra segment too small: need " + required
                + " bytes for " + mapping.logicalWidth() + "x" + mapping.logicalHeight()
                + " BGRA input, got " + bgra.byteSize());
        }

        return switch (format) {
            case MONOCHROME -> packMonochrome(bgra, strategy, mapping);
            case FOUR_GRAY -> packFourGray(bgra, mapping, fourGrayBinBoundaries);
            case RGB565, SEVEN_COLOR -> throw new IllegalArgumentException(
                "PixelFormat " + format + " is not supported by pux4j-transform");
        };
    }

    private static void requireCompatible(PixelFormat format, TransformStrategy strategy) {
        boolean monoStrategy = strategy == TransformStrategy.THRESHOLD
            || strategy == TransformStrategy.FLOYD_STEINBERG
            || strategy == TransformStrategy.ORDERED_2X2;
        if (format == PixelFormat.MONOCHROME && !monoStrategy) {
            throw new IllegalArgumentException(
                "Strategy " + strategy + " requires PixelFormat.FOUR_GRAY, not MONOCHROME");
        }
        if (format == PixelFormat.FOUR_GRAY && strategy != TransformStrategy.FOUR_GRAY_BINNING) {
            throw new IllegalArgumentException(
                "FOUR_GRAY_BINNING is the only strategy for PixelFormat.FOUR_GRAY (got " + strategy + ")");
        }
    }

    /**
     * Integer luminance weights matching Canvas.packMonochrome / Canvas.drawImage exactly, so
     * the emulator, the validation app and this module can never disagree about "black".
     */
    private static int luminance(MemorySegment bgra, long pixelByteOffset) {
        int b = bgra.get(ValueLayout.JAVA_BYTE, pixelByteOffset) & 0xFF;
        int g = bgra.get(ValueLayout.JAVA_BYTE, pixelByteOffset + 1) & 0xFF;
        int r = bgra.get(ValueLayout.JAVA_BYTE, pixelByteOffset + 2) & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static byte[] newWhiteFrame(int fbRowBytes, int fbH) {
        byte[] out = new byte[fbRowBytes * fbH];
        Arrays.fill(out, (byte) 0xFF);
        return out;
    }

    private static void clearBit(byte[] out, int fbRowBytes, int fx, int fy) {
        int idx = fy * fbRowBytes + (fx / 8);
        int bit = 7 - (fx % 8);
        out[idx] = (byte) (out[idx] & ~(1 << bit));
    }

    private static MonochromeFrame packMonochrome(MemorySegment bgra, TransformStrategy strategy,
                                                    OrientationMapping mapping) {
        int fbRowBytes = (mapping.framebufferWidth() + 7) / 8;
        byte[] out = newWhiteFrame(fbRowBytes, mapping.framebufferHeight());

        switch (strategy) {
            case THRESHOLD -> thresholdPack(bgra, mapping, out, fbRowBytes);
            case ORDERED_2X2 -> orderedPack(bgra, mapping, out, fbRowBytes);
            case FLOYD_STEINBERG -> floydSteinbergPack(bgra, mapping, out, fbRowBytes);
            case FOUR_GRAY_BINNING -> throw new IllegalStateException(
                "unreachable — requireCompatible rejects FOUR_GRAY_BINNING for MONOCHROME");
        }
        return new MonochromeFrame(out, RefreshMode.FULL);
    }

    private static void thresholdPack(MemorySegment bgra, OrientationMapping mapping,
                                       byte[] out, int fbRowBytes) {
        int logicalW = mapping.logicalWidth();
        int logicalH = mapping.logicalHeight();
        for (int ly = 0; ly < logicalH; ly++) {
            for (int lx = 0; lx < logicalW; lx++) {
                int lum = luminance(bgra, (long) (ly * logicalW + lx) * 4L);
                if (lum < 128) {
                    clearBit(out, fbRowBytes, mapping.nativeX(lx, ly), mapping.nativeY(lx, ly));
                }
            }
        }
    }

    /**
     * 2x2 Bayer ordered dither. Thresholds are a pure function of logical pixel position, so
     * (unlike FLOYD_STEINBERG) the same physical pixel gets the same threshold on every frame —
     * a static image dithers identically frame to frame and produces an empty diff.
     */
    private static final int[][] BAYER_2X2 = {{0, 2}, {3, 1}};

    private static void orderedPack(MemorySegment bgra, OrientationMapping mapping,
                                     byte[] out, int fbRowBytes) {
        int logicalW = mapping.logicalWidth();
        int logicalH = mapping.logicalHeight();
        for (int ly = 0; ly < logicalH; ly++) {
            for (int lx = 0; lx < logicalW; lx++) {
                int lum = luminance(bgra, (long) (ly * logicalW + lx) * 4L);
                float threshold = (BAYER_2X2[ly & 1][lx & 1] + 0.5f) * 255f / 4f;
                if (lum < threshold) {
                    clearBit(out, fbRowBytes, mapping.nativeX(lx, ly), mapping.nativeY(lx, ly));
                }
            }
        }
    }

    /**
     * Floyd-Steinberg error diffusion, in logical scan order (the rotation only affects where
     * the final quantised pixel is written, not the error-diffusion neighbourhood).
     */
    private static void floydSteinbergPack(MemorySegment bgra, OrientationMapping mapping,
                                            byte[] out, int fbRowBytes) {
        int logicalW = mapping.logicalWidth();
        int logicalH = mapping.logicalHeight();
        // Index 0 and logicalW+1 are padding so x-1/x/x+1 never need bounds checks.
        double[] currentRow = new double[logicalW + 2];
        double[] nextRow = new double[logicalW + 2];

        for (int ly = 0; ly < logicalH; ly++) {
            for (int lx = 0; lx < logicalW; lx++) {
                int lum = luminance(bgra, (long) (ly * logicalW + lx) * 4L);
                double old = lum + currentRow[lx + 1];
                double quantised = old < 128 ? 0 : 255;
                double error = old - quantised;
                if (quantised == 0) {
                    clearBit(out, fbRowBytes, mapping.nativeX(lx, ly), mapping.nativeY(lx, ly));
                }
                currentRow[lx + 2] += error * 7.0 / 16.0;
                nextRow[lx]         += error * 3.0 / 16.0;
                nextRow[lx + 1]     += error * 5.0 / 16.0;
                nextRow[lx + 2]     += error * 1.0 / 16.0;
            }
            double[] swap = currentRow;
            currentRow = nextRow;
            nextRow = swap;
            Arrays.fill(nextRow, 0);
        }
    }

    private static FourGrayFrame packFourGray(MemorySegment bgra, OrientationMapping mapping,
                                               float[] boundaries) {
        if (boundaries.length != 3) {
            throw new IllegalArgumentException(
                "fourGrayBinBoundaries must have exactly 3 values [lo, mid, hi], got " + boundaries.length);
        }
        int fbRowBytes = (mapping.framebufferWidth() + 7) / 8;
        byte[] bwPlane = newWhiteFrame(fbRowBytes, mapping.framebufferHeight());
        byte[] redPlane = newWhiteFrame(fbRowBytes, mapping.framebufferHeight());

        int logicalW = mapping.logicalWidth();
        int logicalH = mapping.logicalHeight();
        for (int ly = 0; ly < logicalH; ly++) {
            for (int lx = 0; lx < logicalW; lx++) {
                int lum = luminance(bgra, (long) (ly * logicalW + lx) * 4L);
                int fx = mapping.nativeX(lx, ly);
                int fy = mapping.nativeY(lx, ly);
                // 3 = white (bw=1,red=1), 2 = light grey (bw=1,red=0),
                // 1 = dark grey (bw=0,red=1), 0 = black (bw=0,red=0).
                int level = lum >= boundaries[2] ? 3 : lum >= boundaries[1] ? 2 : lum >= boundaries[0] ? 1 : 0;
                if (level == 0 || level == 1) {
                    clearBit(bwPlane, fbRowBytes, fx, fy);
                }
                if (level == 0 || level == 2) {
                    clearBit(redPlane, fbRowBytes, fx, fy);
                }
            }
        }
        return new FourGrayFrame(bwPlane, redPlane);
    }
}
