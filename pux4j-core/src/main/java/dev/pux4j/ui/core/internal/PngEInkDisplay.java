// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Headless display driver for CI and testing.
 * Each {@link #writeFrame} call renders to a PNG file.
 * Stub implementation — full PNG rendering to be added in Phase 3.
 */
final class PngEInkDisplay implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(PngEInkDisplay.class);

    private static final DisplayCapabilities CAPABILITIES = new DisplayCapabilities(
        EnumSet.of(PixelFormat.MONOCHROME),
        EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
        false,
        Optional.of(new AlignmentConstraints(8))
    );

    private final int width;
    private final int height;
    private final Orientation orientation;
    private final String outputDir;
    private int frameCount;

    PngEInkDisplay(int width, int height, Orientation orientation, String outputDir) {
        this.width       = width;
        this.height      = height;
        this.orientation = orientation;
        this.outputDir   = outputDir;
    }

    @Override public int getWidth()  { return width;  }
    @Override public int getHeight() { return height; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return CAPABILITIES; }

    @Override public void initialize() { log.debug("PngEInkDisplay initialized ({}x{})", width, height); }
    @Override public void reset()      { log.debug("PngEInkDisplay reset"); }
    @Override public void sleep()      { log.debug("PngEInkDisplay sleep"); }
    @Override public void wake()       { log.debug("PngEInkDisplay wake"); }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        frameCount++;
        log.debug("PngEInkDisplay writeFrame #{} ({})", frameCount, frame.getClass().getSimpleName());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        frameCount++;
        log.debug("PngEInkDisplay writeRegion #{} ({},{} {}x{})", frameCount, x, y, width, height);
        return CompletableFuture.completedFuture(null);
    }
}
