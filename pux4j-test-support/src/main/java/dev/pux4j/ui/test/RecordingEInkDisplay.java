// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.test;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records all writes for assertion in tests. Thread-safe.
 * Write operations complete immediately with no simulated delay.
 */
public final class RecordingEInkDisplay implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(RecordingEInkDisplay.class);

    /** A single recorded display write — either a full-frame or a partial-region write. */
    public sealed interface WriteRecord {
        FrameData frame();
        record Full(FrameData frame) implements WriteRecord {}
        record Region(int x, int y, int width, int height, FrameData frame) implements WriteRecord {}
    }

    private final int width;
    private final int height;
    private final Orientation orientation;
    private final DisplayCapabilities capabilities;
    private final List<WriteRecord> history = new CopyOnWriteArrayList<>();

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
    @Override public void reset()      { history.clear(); }
    @Override public void sleep()      {}
    @Override public void wake()       {}

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        log.debug("writeFrame mode={} bytes={}", modeLabel(frame), dataSize(frame));
        history.add(new WriteRecord.Full(frame));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        log.debug("writeRegion x={} y={} w={} h={} bytes={}", x, y, w, h, dataSize(frame));
        history.add(new WriteRecord.Region(x, y, w, h, frame));
        return CompletableFuture.completedFuture(null);
    }

    /** All writes in insertion order. */
    public List<WriteRecord> history() { return List.copyOf(history); }

    /** All frame data in insertion order (full frames and region frames interleaved). */
    public List<FrameData> frames() {
        return history.stream().map(WriteRecord::frame).toList();
    }

    /** Total number of writes (full + region). */
    public int frameCount() { return history.size(); }

    /** Frame data from the most recent write. */
    public FrameData lastFrame() { return history.getLast().frame(); }

    /** Count of full-frame MonochromeFrame writes with the given refresh mode. */
    public long refreshCount(RefreshMode mode) {
        return history.stream()
            .filter(r -> r instanceof WriteRecord.Full f
                      && f.frame() instanceof MonochromeFrame mf
                      && mf.mode() == mode)
            .count();
    }

    /** All region writes in insertion order. */
    public List<WriteRecord.Region> regions() {
        return history.stream()
            .filter(WriteRecord.Region.class::isInstance)
            .map(WriteRecord.Region.class::cast)
            .toList();
    }

    private static String modeLabel(FrameData frame) {
        return switch (frame) {
            case MonochromeFrame mf  -> mf.mode().name();
            case FourGrayFrame  ignored -> "FOUR_GRAY";
        };
    }

    private static int dataSize(FrameData frame) {
        return switch (frame) {
            case MonochromeFrame mf -> mf.data().length;
            case FourGrayFrame   fg -> fg.bwPlane().length + fg.redPlane().length;
        };
    }
}
