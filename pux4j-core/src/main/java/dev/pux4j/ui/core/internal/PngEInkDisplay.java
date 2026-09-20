// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Headless display driver for CI and the Phase 6.3 headless dev-loop. Each {@link #writeFrame}
 * or {@link #writeRegion} call composites into a retained native-framebuffer-space buffer,
 * rotates the whole buffer to logical (on-screen) space via {@link OrientationMapping} — the
 * same transform a real panel's physical mounting applies for free — and writes it as a
 * greyscale PNG (see {@link PngEncoder}), so the output looks the way a human viewing the
 * physical panel would see it, not the raw native framebuffer layout.
 *
 * <p>Frames are written as {@code frame-0001.png}, {@code frame-0002.png}, … in
 * {@code outputDir}, so a later session (or CI) can diff them frame to frame.
 *
 * <h2>Threading</h2>
 * <p>{@link EInkDisplayDriver}'s contract only guarantees one write in flight at a time — a
 * caller must await each returned future before issuing the next — but unlike a real hardware
 * driver (where an overlapping call just risks malformed SPI traffic on hardware nothing here
 * can accidentally touch), this driver retains an in-process {@code int[]} buffer that a
 * violation of that contract could genuinely corrupt with a torn read/write. Every
 * buffer-touching step (composite, rotate, encode, file write, and the sequence-number
 * assignment that names the output file) therefore always runs on this instance's own
 * single-thread {@link ExecutorService} — never on the caller's thread — so calls are safe to
 * issue concurrently: they simply queue and execute one at a time, in submission order, with
 * no possibility of a torn frame. Call {@link #close()} to flush and stop that thread.
 */
final class PngEInkDisplay implements EInkDisplayDriver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PngEInkDisplay.class);

    private static final DisplayCapabilities CAPABILITIES = new DisplayCapabilities(
        EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
        EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
        false,
        Optional.of(new AlignmentConstraints(8))
    );

    private final int width;
    private final int height;
    private final Orientation orientation;
    private final OrientationMapping orientationMapping;
    private final Path outputDir;
    private final ExecutorService worker;

    // Retained native-framebuffer-space buffer that writeRegion composites into; writeFrame
    // replaces it wholesale. Initialised to white (0xFFFFFFFF) — a bare int[] defaults to
    // 0x00000000 (transparent black), which would render the very first frame as solid black
    // instead of matching an eInk panel's actual white power-on/clear state. Only ever touched
    // from {@link #worker}'s single thread — see this class's Threading javadoc.
    private final int[] nativeBuffer;

    // Only ever incremented from within a task run on worker — see this class's Threading
    // javadoc — so the sequence a frame is numbered with always matches the order it was
    // actually composited in, regardless of which caller thread issued which call first.
    private int frameCount;

    PngEInkDisplay(int width, int height, Orientation orientation, String outputDir) {
        this.width       = width;
        this.height      = height;
        this.orientation = orientation;
        this.orientationMapping = OrientationMapping.of(width, height, orientation);
        this.outputDir   = Path.of(outputDir);
        this.nativeBuffer = new int[width * height];
        Arrays.fill(nativeBuffer, 0xFFFFFFFF);
        this.worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("png-eink-writer-", 0).factory());
    }

    @Override public int getWidth()  { return width;  }
    @Override public int getHeight() { return height; }
    @Override public Orientation getOrientation() { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return CAPABILITIES; }

    @Override public void initialize() { log.debug("PngEInkDisplay initialized ({}x{})", width, height); }
    @Override public void sleep()      { log.debug("PngEInkDisplay sleep"); }
    @Override public void wake()       { log.debug("PngEInkDisplay wake"); }

    @Override
    public void reset() {
        // reset() is documented as synchronous, so this blocks — but still runs the actual
        // mutation on worker (see Threading javadoc) rather than the caller's thread, so it
        // serialises correctly against any write already queued or in flight.
        runOnWorkerAndAwait(() -> Arrays.fill(nativeBuffer, 0xFFFFFFFF), "reset");
        log.debug("PngEInkDisplay reset");
    }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        RefreshMode mode = frame instanceof MonochromeFrame mf ? mf.mode() : null;
        String modeDescription = mode != null ? mode.toString() : "FOUR_GRAY";
        return submitWrite(modeDescription, 0, 0, width, height, () -> {
            int[] decoded = FrameRenderSupport.decode(frame, width, height);
            System.arraycopy(decoded, 0, nativeBuffer, 0, nativeBuffer.length);
        });
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        validateRegion(x, y, w, h);
        MonochromeFrame mf = (MonochromeFrame) frame;
        return submitWrite(mf.mode().toString(), x, y, w, h, () -> {
            int[] regionPixels = FrameRenderSupport.decodeMonochrome(mf.data(), w, h);
            for (int ry = 0; ry < h; ry++) {
                System.arraycopy(regionPixels, ry * w, nativeBuffer, (y + ry) * width + x, w);
            }
        });
    }

    // Synchronous, fail-fast, on the caller's thread, before any work is queued — mirrors
    // Ssd1680DisplayDriver.writeRegion's own validation exactly, so a bad region is rejected
    // the same way whether the target is real hardware or this headless driver. Reads the
    // alignment step from CAPABILITIES rather than hardcoding 8, so the check can never drift
    // from what this driver actually declares supporting.
    private void validateRegion(int x, int y, int w, int h) {
        if (x < 0 || x + w > width) {
            throw new IllegalArgumentException("x + width must be <= " + width + " (x=" + x + ", width=" + w + ")");
        }
        if (y < 0 || y + h > height) {
            throw new IllegalArgumentException("y + height must be <= " + height + " (y=" + y + ", height=" + h + ")");
        }
        int xStep = CAPABILITIES.partialAlignment().orElseThrow().xStepPx();
        if (x % xStep != 0) {
            throw new IllegalArgumentException("x must be a multiple of " + xStep + " (x=" + x + ")");
        }
    }

    // Composites (via `compositeIntoNativeBuffer`, which the caller supplies as the bit that
    // differs between writeFrame/writeRegion), rotates, encodes, and writes a PNG — always on
    // worker, per this class's Threading javadoc. sequence is assigned inside the task itself
    // so the file name always matches actual composite order, not submission-attempt order.
    private CompletableFuture<Void> submitWrite(String modeDescription, int x, int y, int w, int h,
                                                 Runnable compositeIntoNativeBuffer) {
        return CompletableFuture.runAsync(() -> {
            compositeIntoNativeBuffer.run();
            int sequence = ++frameCount;
            try {
                FrameRenderSupport.RotatedRegion logical = FrameRenderSupport.rotateToLogical(
                    orientationMapping, nativeBuffer, 0, 0, width, height);
                byte[] gray = toGrayscale(logical.argb());
                byte[] png = PngEncoder.encodeGrayscale(gray, logical.width(), logical.height());
                Path target = outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", sequence));
                Files.createDirectories(outputDir);
                Files.write(target, png);
                log.debug("PngEInkDisplay wrote {} (mode={}, region={},{} {}x{})",
                    target, modeDescription, x, y, w, h);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, worker);
    }

    private void runOnWorkerAndAwait(Runnable task, String description) {
        try {
            worker.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + description, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(description + " failed", e.getCause());
        }
    }

    /**
     * Shuts down this driver's write thread, first letting any already-queued writes (from
     * calls made before this method was invoked) complete — see this class's Threading
     * javadoc. Waits up to 30 seconds; logs a warning rather than throwing if writes are still
     * pending after that, since dropping frames at shutdown is recoverable in a way a thrown
     * exception from {@code close()} typically isn't for a caller using try-with-resources.
     */
    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("PngEInkDisplay: worker did not finish pending writes within 30s at close()");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ARGB samples from FrameRenderSupport are always R=G=B (black/white/the two four-gray
    // shades), so reading the red channel is an exact, lossless greyscale conversion — not an
    // approximation that would need a luminance formula.
    private static byte[] toGrayscale(int[] argb) {
        byte[] gray = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            gray[i] = (byte) ((argb[i] >>> 16) & 0xFF);
        }
        return gray;
    }
}
