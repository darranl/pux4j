// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX canvas-based eInk emulator. Renders each frame to a scaled canvas using
 * nearest-neighbour pixel scaling. Requires a running JavaFX application.
 *
 * <p>Call {@link EInkEmulatorWindow#create} to wire the canvas before writing frames.
 */
public final class EmulatedEInkDisplay implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(EmulatedEInkDisplay.class);

    // Four-gray ARGB lookup: index = bwBit * 2 + redBit
    // (0,0)=black, (0,1)=dark grey, (1,0)=light grey, (1,1)=white
    private static final int[] FOUR_GRAY_ARGB = {
        0xFF000000,  // 0: black
        0xFF505050,  // 1: dark grey   rgb(80,80,80)
        0xFFB4B4B4,  // 2: light grey  rgb(180,180,180)
        0xFFFFFFFF,  // 3: white
    };

    private final int width;
    private final int height;
    private final int scaleFactor;
    private final Orientation orientation;
    private final DisplayCapabilities capabilities;

    private volatile Canvas canvas;

    public EmulatedEInkDisplay(int width, int height, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes,
                                int scaleFactor) {
        this.width       = width;
        this.height      = height;
        this.scaleFactor = scaleFactor;
        this.orientation = orientation;
        this.capabilities = new DisplayCapabilities(
            formats, modes, false, Optional.empty());
    }

    public EmulatedEInkDisplay(int width, int height, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this(width, height, orientation, formats, modes, 3);
    }

    @Override public int getWidth()  { return width;  }
    @Override public int getHeight() { return height; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return capabilities; }

    public int scaleFactor() { return scaleFactor; }

    @Override public void initialize() {}
    @Override public void reset()      {}
    @Override public void sleep()      {}
    @Override public void wake()       {}

    /** Called by {@link EInkEmulatorWindow} after the canvas is constructed. */
    void attachCanvas(Canvas c) {
        this.canvas = Objects.requireNonNull(c);
    }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        Canvas target = requireCanvas();
        long start = System.nanoTime();
        var future = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                renderFull(target, frame);
                log.debug("writeFrame rendered in {}ms", (System.nanoTime() - start) / 1_000_000);
                future.complete(null);
            } catch (Exception ex) {
                log.error("writeFrame render failed", ex);
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        Canvas target = requireCanvas();
        var future = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                renderRegion(target, x, y, w, h, (MonochromeFrame) frame);
                log.debug("writeRegion x={} y={} w={} h={}", x, y, w, h);
                future.complete(null);
            } catch (Exception ex) {
                log.error("writeRegion render failed", ex);
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private Canvas requireCanvas() {
        Canvas c = canvas;
        if (c == null) {
            throw new IllegalStateException(
                "Canvas not attached — call EInkEmulatorWindow.create() before writing frames");
        }
        return c;
    }

    private void renderFull(Canvas target, FrameData frame) {
        int[] pixels = switch (frame) {
            case MonochromeFrame mf -> decodeMonochrome(mf.data(), width, height);
            case FourGrayFrame   fg -> decodeFourGray(fg.bwPlane(), fg.redPlane(), width, height);
        };
        drawScaled(target, pixels, 0, 0, width, height);
    }

    private void renderRegion(Canvas target, int rx, int ry, int rw, int rh,
                               MonochromeFrame frame) {
        int[] pixels = decodeMonochrome(frame.data(), rw, rh);
        drawScaled(target, pixels, rx, ry, rw, rh);
    }

    // Decodes a row-padded 1-bit framebuffer into ARGB pixels.
    // Each row occupies ceil(width/8) bytes; padding bits at the end of each row are skipped.
    private static int[] decodeMonochrome(byte[] data, int width, int height) {
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

    private static int[] decodeFourGray(byte[] bwPlane, byte[] redPlane, int width, int height) {
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

    private void drawScaled(Canvas target, int[] argb, int destX, int destY,
                             int srcWidth, int srcHeight) {
        var img = new WritableImage(srcWidth, srcHeight);
        img.getPixelWriter().setPixels(
            0, 0, srcWidth, srcHeight,
            javafx.scene.image.PixelFormat.getIntArgbInstance(),
            argb, 0, srcWidth);
        var gc = target.getGraphicsContext2D();
        gc.setImageSmoothing(false);
        gc.drawImage(img,
            destX * scaleFactor, destY * scaleFactor,
            srcWidth * scaleFactor, srcHeight * scaleFactor);
    }
}
