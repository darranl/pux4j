// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.test;

import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pixel-level assertions and golden-file PNG comparison for FrameData.
 *
 * <p>Bit encoding (MonochromeFrame): 0 = black, 1 = white; MSB of each byte is the leftmost pixel.
 * Four-gray shade lookup: (bwPlane=1, redPlane=1)=white, (1,0)=light, (0,1)=dark, (0,0)=black.
 */
public final class FramebufferAssertions {

    private FramebufferAssertions() {}

    // ── MonochromeFrame assertions ────────────────────────────────────────────

    /**
     * Asserts that the pixel at {@code (x, y)} in a MonochromeFrame is white (bit = 1).
     *
     * @param frame  the monochrome frame to inspect
     * @param x      logical pixel column (0-based)
     * @param y      logical pixel row (0-based)
     * @param width  logical display width used to compute the byte offset
     */
    public static void assertPixelWhite(FrameData frame, int x, int y, int width) {
        int bit = extractMonochromeBit(requireMonochrome(frame), x, y, width);
        if (bit != 1) {
            throw new AssertionError(
                "Expected WHITE (bit=1) at (" + x + ", " + y + ") but was BLACK (bit=0)");
        }
    }

    /**
     * Asserts that the pixel at {@code (x, y)} in a MonochromeFrame is black (bit = 0).
     */
    public static void assertPixelBlack(FrameData frame, int x, int y, int width) {
        int bit = extractMonochromeBit(requireMonochrome(frame), x, y, width);
        if (bit != 0) {
            throw new AssertionError(
                "Expected BLACK (bit=0) at (" + x + ", " + y + ") but was WHITE (bit=1)");
        }
    }

    /**
     * Asserts that a rectangular region of a MonochromeFrame matches an expected pattern.
     * {@code expected[row][col]} uses {@code 1} for white and {@code 0} for black.
     */
    public static void assertRegionMatches(FrameData frame, int x, int y,
                                            int regionWidth, int regionHeight,
                                            int width, int[][] expected) {
        MonochromeFrame mf = requireMonochrome(frame);
        for (int row = 0; row < regionHeight; row++) {
            for (int col = 0; col < regionWidth; col++) {
                int bit = extractMonochromeBit(mf, x + col, y + row, width);
                int exp = expected[row][col];
                if (bit != exp) {
                    throw new AssertionError(
                        "Region mismatch at (" + (x + col) + ", " + (y + row) + "): "
                        + "expected " + (exp == 1 ? "WHITE" : "BLACK")
                        + " but was " + (bit == 1 ? "WHITE" : "BLACK"));
                }
            }
        }
    }

    // ── FourGrayFrame assertions ──────────────────────────────────────────────

    /**
     * Shade constants: {@code 0}=black, {@code 1}=dark grey, {@code 2}=light grey, {@code 3}=white.
     */
    public static final int SHADE_BLACK      = 0;
    public static final int SHADE_DARK_GREY  = 1;
    public static final int SHADE_LIGHT_GREY = 2;
    public static final int SHADE_WHITE      = 3;

    /**
     * Asserts that the pixel at {@code (x, y)} in a FourGrayFrame has the expected shade.
     * Use the {@code SHADE_*} constants defined here.
     */
    public static void assertPixelShade(FrameData frame, int x, int y, int width, int expectedShade) {
        FourGrayFrame fg = requireFourGray(frame);
        int shade = extractFourGrayShade(fg, x, y, width);
        if (shade != expectedShade) {
            throw new AssertionError(
                "Expected shade " + shadeName(expectedShade) + " at (" + x + ", " + y + ")"
                + " but was " + shadeName(shade));
        }
    }

    // ── Golden-file PNG comparison ────────────────────────────────────────────

    /**
     * Asserts that {@code frame} matches a reference PNG image loaded from {@code goldenPng}.
     * PNG pixels are compared by luminance threshold: &gt;127 = white, &le;127 = black.
     * For FourGrayFrame, four luminance buckets (0–63, 64–127, 128–191, 192–255) are used.
     *
     * @param frame     the frame to compare
     * @param width     logical display width
     * @param height    logical display height
     * @param goldenPng open stream for the reference PNG (closed after reading)
     */
    public static void assertMatchesGoldenFile(FrameData frame, int width, int height,
                                                InputStream goldenPng) {
        BufferedImage img;
        try (goldenPng) {
            img = ImageIO.read(goldenPng);
        } catch (IOException e) {
            throw new AssertionError("Failed to read golden PNG: " + e.getMessage(), e);
        }
        if (img == null) {
            throw new AssertionError("Golden PNG could not be decoded (null result from ImageIO)");
        }
        if (img.getWidth() != width || img.getHeight() != height) {
            throw new AssertionError(
                "Golden PNG dimensions " + img.getWidth() + "×" + img.getHeight()
                + " do not match frame dimensions " + width + "×" + height);
        }

        switch (frame) {
            case MonochromeFrame mf -> compareMonochromeToImage(mf, img, width, height);
            case FourGrayFrame   fg -> compareFourGrayToImage(fg, img, width, height);
        }
    }

    /**
     * Asserts that {@code frame} matches a reference PNG loaded as a classpath resource
     * from within the {@code pux4j-test-support} module.
     */
    public static void assertMatchesGoldenFile(FrameData frame, int width, int height,
                                                String resourcePath) {
        try (InputStream in = FramebufferAssertions.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new AssertionError("Golden PNG resource not found: " + resourcePath);
            }
            assertMatchesGoldenFile(frame, width, height, in);
        } catch (IOException e) {
            throw new AssertionError("Failed to close golden PNG stream: " + e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static MonochromeFrame requireMonochrome(FrameData frame) {
        if (frame instanceof MonochromeFrame mf) return mf;
        throw new IllegalArgumentException(
            "Expected MonochromeFrame but got " + frame.getClass().getSimpleName());
    }

    private static FourGrayFrame requireFourGray(FrameData frame) {
        if (frame instanceof FourGrayFrame fg) return fg;
        throw new IllegalArgumentException(
            "Expected FourGrayFrame but got " + frame.getClass().getSimpleName());
    }

    private static int extractMonochromeBit(MonochromeFrame frame, int x, int y, int width) {
        int bitIndex  = y * width + x;
        int byteIndex = bitIndex / 8;
        int bitShift  = 7 - (bitIndex % 8);  // MSB is leftmost pixel
        return (frame.data()[byteIndex] >> bitShift) & 1;
    }

    private static int extractFourGrayShade(FourGrayFrame frame, int x, int y, int width) {
        int bitIndex  = y * width + x;
        int byteIndex = bitIndex / 8;
        int bitShift  = 7 - (bitIndex % 8);
        int bw  = (frame.bwPlane() [byteIndex] >> bitShift) & 1;
        int red = (frame.redPlane()[byteIndex] >> bitShift) & 1;
        return bw * 2 + red;  // (0,0)=0=black, (0,1)=1=dark, (1,0)=2=light, (1,1)=3=white
    }

    private static void compareMonochromeToImage(MonochromeFrame mf, BufferedImage img,
                                                  int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8)  & 0xFF;
                int b = rgb         & 0xFF;
                int luma = (r + g + b) / 3;
                int expectedBit = luma > 127 ? 1 : 0;
                int actualBit = extractMonochromeBit(mf, x, y, width);
                if (actualBit != expectedBit) {
                    throw new AssertionError(
                        "Golden file mismatch at (" + x + ", " + y + "): "
                        + "PNG luma=" + luma + " expects " + (expectedBit == 1 ? "WHITE" : "BLACK")
                        + " but frame has " + (actualBit == 1 ? "WHITE" : "BLACK"));
                }
            }
        }
    }

    private static void compareFourGrayToImage(FourGrayFrame fg, BufferedImage img,
                                                int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8)  & 0xFF;
                int b = rgb         & 0xFF;
                int luma = (r + g + b) / 3;
                int expectedShade = luma < 64 ? SHADE_BLACK
                                  : luma < 128 ? SHADE_DARK_GREY
                                  : luma < 192 ? SHADE_LIGHT_GREY
                                  : SHADE_WHITE;
                int actualShade = extractFourGrayShade(fg, x, y, width);
                if (actualShade != expectedShade) {
                    throw new AssertionError(
                        "Golden file mismatch at (" + x + ", " + y + "): "
                        + "PNG luma=" + luma + " expects " + shadeName(expectedShade)
                        + " but frame has " + shadeName(actualShade));
                }
            }
        }
    }

    private static String shadeName(int shade) {
        return switch (shade) {
            case SHADE_BLACK      -> "BLACK";
            case SHADE_DARK_GREY  -> "DARK_GREY";
            case SHADE_LIGHT_GREY -> "LIGHT_GREY";
            case SHADE_WHITE      -> "WHITE";
            default               -> "SHADE(" + shade + ")";
        };
    }
}
