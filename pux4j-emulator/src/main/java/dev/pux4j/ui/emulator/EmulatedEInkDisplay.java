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

    // Native chip framebuffer dimensions — always portrait-shaped (narrow x tall), matching
    // every real EInkDisplayDriver's getWidth()/getHeight() contract. writeFrame/writeRegion
    // data is always in this native, un-rotated coordinate space.
    private final int nativeWidth;
    private final int nativeHeight;
    // On-screen (logical/landscape) dimensions — what the emulator window actually shows,
    // after applying the same rotation a real panel's physical mounting provides for free.
    private final int screenWidth;
    private final int screenHeight;
    private final int scaleFactor;
    private final Orientation orientation;
    private final DisplayCapabilities capabilities;

    private volatile Canvas canvas;

    public EmulatedEInkDisplay(int nativeWidth, int nativeHeight, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes,
                                int scaleFactor) {
        this.nativeWidth  = nativeWidth;
        this.nativeHeight = nativeHeight;
        this.scaleFactor  = scaleFactor;
        this.orientation  = orientation;
        this.screenWidth  = switch (orientation) {
            case LANDSCAPE, LANDSCAPE_INVERTED -> nativeHeight;
            case PORTRAIT, PORTRAIT_INVERTED   -> nativeWidth;
        };
        this.screenHeight = switch (orientation) {
            case LANDSCAPE, LANDSCAPE_INVERTED -> nativeWidth;
            case PORTRAIT, PORTRAIT_INVERTED   -> nativeHeight;
        };
        this.capabilities = new DisplayCapabilities(
            formats, modes, false, Optional.empty());
    }

    public EmulatedEInkDisplay(int nativeWidth, int nativeHeight, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this(nativeWidth, nativeHeight, orientation, formats, modes, 3);
    }

    @Override public int getWidth()  { return nativeWidth;  }
    @Override public int getHeight() { return nativeHeight; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return capabilities; }

    /** On-screen window width, in eInk pixels before {@link #scaleFactor()} is applied. */
    public int screenWidth()  { return screenWidth; }
    /** On-screen window height, in eInk pixels before {@link #scaleFactor()} is applied. */
    public int screenHeight() { return screenHeight; }

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
        int[] nativePixels = switch (frame) {
            case MonochromeFrame mf -> decodeMonochrome(mf.data(), nativeWidth, nativeHeight);
            case FourGrayFrame   fg -> decodeFourGray(fg.bwPlane(), fg.redPlane(), nativeWidth, nativeHeight);
        };
        RotatedRegion screen = rotateToScreen(nativePixels, 0, 0, nativeWidth, nativeHeight);
        drawScaled(target, screen.argb(), screen.x(), screen.y(), screen.width(), screen.height());
    }

    private void renderRegion(Canvas target, int rx, int ry, int rw, int rh,
                               MonochromeFrame frame) {
        int[] nativePixels = decodeMonochrome(frame.data(), rw, rh);
        RotatedRegion screen = rotateToScreen(nativePixels, rx, ry, rw, rh);
        drawScaled(target, screen.argb(), screen.x(), screen.y(), screen.width(), screen.height());
    }

    private record RotatedRegion(int[] argb, int x, int y, int width, int height) {}

    // Maps a point in the native (portrait-shaped, un-rotated) framebuffer to its position in
    // the on-screen (logical/landscape) view — the inverse of the transform a real panel's
    // physical mounting performs for free. Mirrors (inverted) Canvas.mapToFramebuffer in
    // pux4j-validation, which cannot be shared directly: different module, and that method
    // maps the opposite direction (logical -> native, for writing).
    private int[] mapNativeToScreen(int nx, int ny) {
        return switch (orientation) {
            case LANDSCAPE          -> new int[]{ nativeHeight - 1 - ny, nx };
            case LANDSCAPE_INVERTED -> new int[]{ ny, nativeWidth - 1 - nx };
            case PORTRAIT           -> new int[]{ nx, ny };
            case PORTRAIT_INVERTED  -> new int[]{ nativeWidth - 1 - nx, nativeHeight - 1 - ny };
        };
    }

    // Rotates a native-space rectangle (absolute offset rx,ry; nativeArgb is rw*rh, region-
    // local) into screen space. Computed generically from the 4 corner mappings rather than
    // per-orientation closed forms, so it works unchanged for all four Orientation values —
    // including the whole-frame case (rx=0, ry=0, rw=nativeWidth, rh=nativeHeight).
    private RotatedRegion rotateToScreen(int[] nativeArgb, int rx, int ry, int rw, int rh) {
        int[] c00 = mapNativeToScreen(rx,        ry);
        int[] c10 = mapNativeToScreen(rx + rw - 1, ry);
        int[] c01 = mapNativeToScreen(rx,        ry + rh - 1);
        int[] c11 = mapNativeToScreen(rx + rw - 1, ry + rh - 1);
        int minX = Math.min(Math.min(c00[0], c10[0]), Math.min(c01[0], c11[0]));
        int minY = Math.min(Math.min(c00[1], c10[1]), Math.min(c01[1], c11[1]));
        int maxX = Math.max(Math.max(c00[0], c10[0]), Math.max(c01[0], c11[0]));
        int maxY = Math.max(Math.max(c00[1], c10[1]), Math.max(c01[1], c11[1]));
        int sw = maxX - minX + 1;
        int sh = maxY - minY + 1;

        int[] screenArgb = new int[sw * sh];
        for (int ly = 0; ly < rh; ly++) {
            for (int lx = 0; lx < rw; lx++) {
                int[] s = mapNativeToScreen(rx + lx, ry + ly);
                screenArgb[(s[1] - minY) * sw + (s[0] - minX)] = nativeArgb[ly * rw + lx];
            }
        }
        return new RotatedRegion(screenArgb, minX, minY, sw, sh);
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
