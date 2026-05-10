// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX canvas-based eInk emulator. Renders each frame to a scaled canvas
 * with greyscale eInk-like appearance.
 * Stub implementation — to be completed in Phase 3.
 */
public final class EmulatedEInkDisplay implements EInkDisplayDriver {

    private final int width;
    private final int height;
    private final Orientation orientation;
    private final DisplayCapabilities capabilities;

    public EmulatedEInkDisplay(int width, int height, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this.width       = width;
        this.height      = height;
        this.orientation = orientation;
        this.capabilities = new DisplayCapabilities(
            formats, modes, false, Optional.of(new AlignmentConstraints(8)));
    }

    @Override public int getWidth()  { return width;  }
    @Override public int getHeight() { return height; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return capabilities; }

    @Override public void initialize() {}
    @Override public void reset()      {}
    @Override public void sleep()      {}
    @Override public void wake()       {}

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        throw new UnsupportedOperationException("EmulatedEInkDisplay not yet implemented — Phase 3");
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        throw new UnsupportedOperationException("EmulatedEInkDisplay not yet implemented — Phase 3");
    }
}
