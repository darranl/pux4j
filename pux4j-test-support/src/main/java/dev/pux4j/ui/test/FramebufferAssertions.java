// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.test;

import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;

/**
 * Pixel-level assertions and golden-file PNG comparison for FrameData.
 * Stub implementation — to be completed in Phase 3.
 */
public final class FramebufferAssertions {

    private FramebufferAssertions() {}

    public static void assertPixelWhite(FrameData frame, int x, int y, int width) {
        throw new UnsupportedOperationException("FramebufferAssertions not yet implemented — Phase 3");
    }

    public static void assertPixelBlack(FrameData frame, int x, int y, int width) {
        throw new UnsupportedOperationException("FramebufferAssertions not yet implemented — Phase 3");
    }

    public static void assertMatchesGoldenFile(FrameData frame, int width, int height,
                                               String goldenPath) {
        throw new UnsupportedOperationException("FramebufferAssertions not yet implemented — Phase 3");
    }
}
