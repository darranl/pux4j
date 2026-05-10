package dev.pux4j.ui.test;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Records all writes for assertion in tests. Supports MonochromeFrame and FourGrayFrame.
 * Stub implementation — to be completed in Phase 3.
 */
public final class RecordingEInkDisplay implements EInkDisplayDriver {

    private final int width;
    private final int height;
    private final Orientation orientation;
    private final DisplayCapabilities capabilities;
    private final List<FrameData> frames = new ArrayList<>();

    public RecordingEInkDisplay(int width, int height, Orientation orientation,
                                 EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this.width       = width;
        this.height      = height;
        this.orientation = orientation;
        this.capabilities = new DisplayCapabilities(
            formats, modes, true, Optional.of(new AlignmentConstraints(8)));
    }

    @Override public int getWidth()  { return width;  }
    @Override public int getHeight() { return height; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return capabilities; }

    @Override public void initialize() {}
    @Override public void reset()      { frames.clear(); }
    @Override public void sleep()      {}
    @Override public void wake()       {}

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        frames.add(frame);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        frames.add(frame);
        return CompletableFuture.completedFuture(null);
    }

    public List<FrameData> frames() { return List.copyOf(frames); }
    public int frameCount()         { return frames.size(); }
    public FrameData lastFrame()    { return frames.getLast(); }
}
