// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRenderSupportTest {

    // 5x3 native framebuffer, width not a multiple of 8 — deliberately, to prove the row
    // padding math (ceil(width/8) bytes/row) is exercised, matching this project's convention
    // of never testing byte-packing logic only at multiple-of-8 sizes.
    private static final int NATIVE_W = 5;
    private static final int NATIVE_H = 3;

    // Row-major, MSB-leftmost, 0=black/1=white — see MonochromeFrame's javadoc. Pattern
    // (top row white, rest black) makes a wrong row-vs-column read visibly obvious in a
    // failing assertion rather than producing a value that could pass by symmetry.
    private static byte[] fivePixelWideMonochromeData() {
        return new byte[]{
            (byte) 0b11111000, // row 0: all white (padding bits don't matter)
            (byte) 0b00000000, // row 1: all black
            (byte) 0b00000000, // row 2: all black
        };
    }

    @Test
    void decodeMonochromeHandlesNonByteMultipleWidthPadding() {
        int[] argb = FrameRenderSupport.decodeMonochrome(fivePixelWideMonochromeData(), NATIVE_W, NATIVE_H);

        for (int col = 0; col < NATIVE_W; col++) {
            assertEquals(0xFFFFFFFF, argb[col], "row 0 col " + col + " must be white");
        }
        for (int row = 1; row < NATIVE_H; row++) {
            for (int col = 0; col < NATIVE_W; col++) {
                assertEquals(0xFF000000, argb[row * NATIVE_W + col], "row " + row + " col " + col + " must be black");
            }
        }
    }

    @Test
    void decodeFourGrayMapsAllFourShadesInBitOrder() {
        // 1 native pixel wide, 4 tall — one row per shade, each byte's MSB is the only bit
        // that matters at width 1. bwPlane/redPlane pair encodes (bw,red): (0,0)=black,
        // (0,1)=dark grey, (1,0)=light grey, (1,1)=white — the exact lookup FOUR_GRAY_ARGB
        // implements; this pins that mapping against a bit-order mistake (e.g. red-then-bw).
        byte[] bwPlane  = { (byte) 0b00000000, (byte) 0b00000000, (byte) 0b10000000, (byte) 0b10000000 };
        byte[] redPlane = { (byte) 0b00000000, (byte) 0b10000000, (byte) 0b00000000, (byte) 0b10000000 };

        int[] argb = FrameRenderSupport.decodeFourGray(bwPlane, redPlane, 1, 4);

        assertEquals(0xFF000000, argb[0], "(bw=0,red=0) must be black");
        assertEquals(0xFF505050, argb[1], "(bw=0,red=1) must be dark grey");
        assertEquals(0xFFB4B4B4, argb[2], "(bw=1,red=0) must be light grey");
        assertEquals(0xFFFFFFFF, argb[3], "(bw=1,red=1) must be white");
    }

    @Test
    void rotateToLogicalIdentityForPortraitLeavesPixelsUnchanged() {
        int[] nativeArgb = FrameRenderSupport.decode(
            new MonochromeFrame(fivePixelWideMonochromeData(), RefreshMode.FULL), NATIVE_W, NATIVE_H);
        OrientationMapping mapping = OrientationMapping.of(NATIVE_W, NATIVE_H, Orientation.PORTRAIT);

        FrameRenderSupport.RotatedRegion region = FrameRenderSupport.rotateToLogical(
            mapping, nativeArgb, 0, 0, NATIVE_W, NATIVE_H);

        assertEquals(0, region.x());
        assertEquals(0, region.y());
        assertEquals(NATIVE_W, region.width());
        assertEquals(NATIVE_H, region.height());
        assertArrayEquals(nativeArgb, region.argb(), "PORTRAIT is the identity transform");
    }

    @Test
    void rotateToLogicalForLandscapeMatchesHandComputedCornerMapping() {
        // Native (row-major) pixel at (col=0,row=0) — top-left of the native buffer — must
        // land at logical (x=NATIVE_H-1, y=0) under LANDSCAPE, per OrientationMapping's own
        // documented coefficients (nativeX=ly, nativeY=fbH-1-lx inverted). This is the same
        // hand-derivation style OrientationMappingTest uses, applied here to prove
        // rotateToLogical actually calls the mapping correctly end-to-end (not just that
        // OrientationMapping itself is correct, which is covered separately).
        int[] nativeArgb = FrameRenderSupport.decode(
            new MonochromeFrame(fivePixelWideMonochromeData(), RefreshMode.FULL), NATIVE_W, NATIVE_H);
        OrientationMapping mapping = OrientationMapping.of(NATIVE_W, NATIVE_H, Orientation.LANDSCAPE);

        FrameRenderSupport.RotatedRegion region = FrameRenderSupport.rotateToLogical(
            mapping, nativeArgb, 0, 0, NATIVE_W, NATIVE_H);

        assertEquals(mapping.logicalWidth(), region.width());
        assertEquals(mapping.logicalHeight(), region.height());

        int logicalX = mapping.logicalX(0, 0);
        int logicalY = mapping.logicalY(0, 0);
        int pixelAtOrigin = nativeArgb[0]; // native (0,0) is white per the fixture above
        int placedPixel = region.argb()[(logicalY - region.y()) * region.width() + (logicalX - region.x())];
        assertEquals(pixelAtOrigin, placedPixel, "native (0,0) must land exactly where OrientationMapping says");
    }
}
