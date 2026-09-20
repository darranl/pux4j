// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.OrientationMapping;

/**
 * Decodes packed {@link FrameData} into ARGB pixels and rotates a native-framebuffer-space
 * rectangle into logical (on-screen) space via {@link OrientationMapping}. Shared by every
 * renderer that needs to show a driver's native framebuffer content to a human — currently
 * {@code pux4j-emulator}'s JavaFX window and {@code PngEInkDisplay}'s frame-snapshot PNGs —
 * so the decode/rotate logic exists exactly once rather than drifting between two copies.
 *
 * <p>Package-private and reached by {@code pux4j-emulator} only via this module's qualified
 * export of {@code dev.pux4j.ui.core.internal} to {@code dev.pux4j.ui.emulator} (see
 * {@code module-info.java}) — it is implementation detail, not published API.
 */
public final class FrameRenderSupport {

    // Four-gray ARGB lookup: index = bwBit * 2 + redBit
    // (0,0)=black, (0,1)=dark grey, (1,0)=light grey, (1,1)=white
    private static final int[] FOUR_GRAY_ARGB = {
        0xFF000000,  // 0: black
        0xFF505050,  // 1: dark grey   rgb(80,80,80)
        0xFFB4B4B4,  // 2: light grey  rgb(180,180,180)
        0xFFFFFFFF,  // 3: white
    };

    private FrameRenderSupport() {}

    /** A rotated ARGB region plus its absolute placement in logical space. */
    public record RotatedRegion(int[] argb, int x, int y, int width, int height) {}

    /** Decodes {@code frame} (native framebuffer space, {@code width}x{@code height}) to ARGB. */
    public static int[] decode(FrameData frame, int width, int height) {
        return switch (frame) {
            case MonochromeFrame mf -> decodeMonochrome(mf.data(), width, height);
            case FourGrayFrame   fg -> decodeFourGray(fg.bwPlane(), fg.redPlane(), width, height);
        };
    }

    // Decodes a row-padded 1-bit framebuffer into ARGB pixels.
    // Each row occupies ceil(width/8) bytes; padding bits at the end of each row are skipped.
    public static int[] decodeMonochrome(byte[] data, int width, int height) {
        int rowBytes = (width + 7) / 8;
        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int bit = (data[row * rowBytes + col / 8] >> (7 - (col % 8))) & 1;
                argb[row * width + col] = bit == 1 ? 0xFFFFFFFF : 0xFF000000;
            }
        }
        return argb;
    }

    public static int[] decodeFourGray(byte[] bwPlane, byte[] redPlane, int width, int height) {
        int rowBytes = (width + 7) / 8;
        int[] argb = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int shift = 7 - (col % 8);
                int bw  = (bwPlane [row * rowBytes + col / 8] >> shift) & 1;
                int red = (redPlane[row * rowBytes + col / 8] >> shift) & 1;
                argb[row * width + col] = FOUR_GRAY_ARGB[bw * 2 + red];
            }
        }
        return argb;
    }

    /**
     * Rotates a native-space rectangle (absolute offset {@code rx,ry}; {@code nativeArgb} is
     * {@code rw*rh}, region-local) into logical space via {@code mapping}. Computed generically
     * from the 4 corner mappings rather than per-orientation closed forms, so it works
     * unchanged for all four {@code Orientation} values — including the whole-frame case
     * ({@code rx=0, ry=0, rw=}native width, {@code rh=}native height).
     */
    public static RotatedRegion rotateToLogical(OrientationMapping mapping, int[] nativeArgb,
                                                 int rx, int ry, int rw, int rh) {
        int x0 = mapping.logicalX(rx,          ry);
        int y0 = mapping.logicalY(rx,          ry);
        int x1 = mapping.logicalX(rx + rw - 1, ry);
        int y1 = mapping.logicalY(rx + rw - 1, ry);
        int x2 = mapping.logicalX(rx,          ry + rh - 1);
        int y2 = mapping.logicalY(rx,          ry + rh - 1);
        int x3 = mapping.logicalX(rx + rw - 1, ry + rh - 1);
        int y3 = mapping.logicalY(rx + rw - 1, ry + rh - 1);
        int minX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
        int minY = Math.min(Math.min(y0, y1), Math.min(y2, y3));
        int maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        int maxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        int sw = maxX - minX + 1;
        int sh = maxY - minY + 1;

        int[] logicalArgb = new int[sw * sh];
        for (int ly = 0; ly < rh; ly++) {
            for (int lx = 0; lx < rw; lx++) {
                int sx = mapping.logicalX(rx + lx, ry + ly);
                int sy = mapping.logicalY(rx + lx, ry + ly);
                logicalArgb[(sy - minY) * sw + (sx - minX)] = nativeArgb[ly * rw + lx];
            }
        }
        return new RotatedRegion(logicalArgb, minX, minY, sw, sh);
    }
}
