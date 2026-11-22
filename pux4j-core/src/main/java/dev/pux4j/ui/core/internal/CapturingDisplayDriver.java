// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.RefreshPolicy;
import dev.pux4j.ui.core.RefreshStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Decorator that forwards to a real driver and writes each distinct displayed frame as a PNG.
 * See {@link dev.pux4j.ui.core.ScreenshotCapture}.
 *
 * <p>The frame is composited into a retained native-space buffer on the calling thread (cheap,
 * and it means the caller may reuse its frame arrays); rotation, encoding and the file write run
 * on one worker thread, so display timing is not affected. Capture errors are logged, never thrown.
 */
public final class CapturingDisplayDriver implements EInkDisplayDriver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CapturingDisplayDriver.class);

    private final EInkDisplayDriver delegate;
    private final Path outputDir;
    private final String prefix;
    private final int width;
    private final int height;
    private final OrientationMapping mapping;
    private final int[] buffer;
    /** System property: how many captured frames may wait for the writer (default 16). */
    public static final String QUEUE_PROPERTY = "pux4j.screenshots.queue";
    private static final int DEFAULT_PENDING = 16;

    // At most this many snapshots wait for the writer (about 150 KiB each on the 2.9" panel). When the
    // output is slower than the display, further captures are dropped and logged, never blocking or
    // failing the display; a gap in the file numbers shows where.
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(pendingLimit()),
        Thread.ofVirtual().name("screenshot-writer-", 0).factory(),
        (task, executor) -> log.warn("Screenshot writer is behind; dropped a capture"
            + " (raise -D" + QUEUE_PROPERTY + " to queue more)"));

    private static int pendingLimit() {
        String value = System.getProperty(QUEUE_PROPERTY);
        if (value == null) {
            return DEFAULT_PENDING;
        }
        try {
            int n = Integer.parseInt(value.trim());
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through to the warning
        }
        log.warn("Ignoring {}={}: expected a positive integer; using {}", QUEUE_PROPERTY, value, DEFAULT_PENDING);
        return DEFAULT_PENDING;
    }
    private int[] lastWritten;
    private int count;

    public CapturingDisplayDriver(EInkDisplayDriver delegate, Path outputDir, String prefix) {
        this.delegate = delegate;
        this.outputDir = outputDir;
        this.prefix = prefix;
        this.width = delegate.getWidth();
        this.height = delegate.getHeight();
        this.mapping = OrientationMapping.of(width, height, delegate.getOrientation());
        this.buffer = new int[width * height];
        Arrays.fill(buffer, 0xFFFFFFFF);
        log.info("Capturing screenshots to {} as {}-NNNN.png", outputDir, prefix);
    }

    @Override public int getWidth() { return delegate.getWidth(); }
    @Override public int getHeight() { return delegate.getHeight(); }
    @Override public Orientation getOrientation() { return delegate.getOrientation(); }
    @Override public DisplayCapabilities getCapabilities() { return delegate.getCapabilities(); }
    @Override public void initialize() { delegate.initialize(); }
    // reset() deliberately leaves the captured pixels alone: the emulator and the HAT drivers keep
    // showing (and retaining) the last frame across a reset, so the next region write is composited
    // onto that frame, not onto white.
    @Override public void reset() { delegate.reset(); }
    @Override public void sleep() { delegate.sleep(); }
    @Override public void wake() { delegate.wake(); }
    @Override public RefreshStats getRefreshStats() { return delegate.getRefreshStats(); }
    @Override public void setRefreshPolicy(RefreshPolicy policy) { delegate.setRefreshPolicy(policy); }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        capture(() -> {
            int[] decoded = FrameRenderSupport.decode(frame, width, height);
            System.arraycopy(decoded, 0, buffer, 0, buffer.length);
        });
        return delegate.writeFrame(frame);
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData frame) {
        // An invalid rectangle is rejected by the real driver; capture nothing for it, so a rejected
        // write cannot leave pixels in the buffer that later screenshots would show.
        boolean valid = x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height;
        if (valid && !(frame instanceof FourGrayFrame)) {
            capture(() -> {
                int[] region = FrameRenderSupport.decodeMonochrome(((MonochromeFrame) frame).data(), w, h);
                for (int row = 0; row < h; row++) {
                    System.arraycopy(region, row * w, buffer, (y + row) * width + x, w);
                }
            });
        }
        return delegate.writeRegion(x, y, w, h, frame);
    }

    private synchronized void capture(Runnable composite) {
        try {
            composite.run();
            if (lastWritten != null && Arrays.equals(lastWritten, buffer)) {
                return;
            }
            int[] snapshot = buffer.clone();
            lastWritten = snapshot;
            int sequence = ++count;
            worker.execute(() -> write(snapshot, sequence));
        } catch (RuntimeException e) {
            log.warn("Screenshot capture failed; the display is unaffected", e);
        }
    }

    private void write(int[] snapshot, int sequence) {
        try {
            var logical = FrameRenderSupport.rotateToLogical(mapping, snapshot, 0, 0, width, height);
            byte[] gray = new byte[logical.argb().length];
            for (int i = 0; i < gray.length; i++) {
                gray[i] = (byte) ((logical.argb()[i] >>> 16) & 0xFF);
            }
            byte[] png = PngEncoder.encodeGrayscale(gray, logical.width(), logical.height());
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve(String.format(Locale.ROOT, "%s-%04d.png", prefix, sequence)), png);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write screenshot {}", sequence, e);
        }
    }

    /** Lets queued screenshots finish writing (up to 10 s). */
    @Override
    public void close() {
        worker.shutdown();
        try {
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Closing the wrapped display driver failed", e);
            }
        }
    }
}
