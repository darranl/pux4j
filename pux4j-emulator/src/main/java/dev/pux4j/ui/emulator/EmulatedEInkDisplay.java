// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.internal.FrameRenderSupport;
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
    private final OrientationMapping orientationMapping;
    private final DisplayCapabilities capabilities;

    private volatile Canvas canvas;

    public EmulatedEInkDisplay(int nativeWidth, int nativeHeight, Orientation orientation,
                                EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes,
                                int scaleFactor) {
        this.nativeWidth  = nativeWidth;
        this.nativeHeight = nativeHeight;
        this.scaleFactor  = scaleFactor;
        this.orientation  = orientation;
        this.orientationMapping = OrientationMapping.of(nativeWidth, nativeHeight, orientation);
        this.screenWidth  = orientationMapping.logicalWidth();
        this.screenHeight = orientationMapping.logicalHeight();
        // Both real ICs this emulator can stand in for (SSD1675A, SSD1680) require partial
        // region X coordinates to be 8-px aligned; reporting Optional.empty() here would let
        // emulator-only code paths silently skip the snapping real hardware enforces.
        this.capabilities = new DisplayCapabilities(
            formats, modes, false, Optional.of(new AlignmentConstraints(8)));
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
        int[] nativePixels = FrameRenderSupport.decode(frame, nativeWidth, nativeHeight);
        FrameRenderSupport.RotatedRegion screen = FrameRenderSupport.rotateToLogical(
            orientationMapping, nativePixels, 0, 0, nativeWidth, nativeHeight);
        drawScaled(target, screen.argb(), screen.x(), screen.y(), screen.width(), screen.height());
    }

    private void renderRegion(Canvas target, int rx, int ry, int rw, int rh,
                               MonochromeFrame frame) {
        int[] nativePixels = FrameRenderSupport.decodeMonochrome(frame.data(), rw, rh);
        FrameRenderSupport.RotatedRegion screen = FrameRenderSupport.rotateToLogical(
            orientationMapping, nativePixels, rx, ry, rw, rh);
        drawScaled(target, screen.argb(), screen.x(), screen.y(), screen.width(), screen.height());
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
