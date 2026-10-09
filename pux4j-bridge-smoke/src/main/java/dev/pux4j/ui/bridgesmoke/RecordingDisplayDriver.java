// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.RefreshPolicy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Wraps a real {@link EInkDisplayDriver} so {@link BridgeSmokeTest} can observe exactly which
 * writes reach the panel, without watching the PNG output directory directly.
 *
 * <p>Directory polling (watching the output directory for new files) was rejected in favour of
 * this decorator: {@code PngEInkDisplay.submitWrite} does a plain, non-atomic {@code
 * Files.write}, so a directory listing or {@code WatchService} can observe a 0-byte or partial
 * file mid-write. Recording the completed-write event here instead means every assertion reads
 * a PNG only after the driver's own future has completed.
 *
 * <p>Implements {@link AutoCloseable} and delegates {@link #close()} — required, not optional:
 * {@code EInkBridge.stop()} only closes the driver it holds via {@code instanceof
 * AutoCloseable} (see {@code EInkBridge.java}'s {@code stop()}), so a non-delegating wrapper
 * would silently leak the real driver's worker thread on every {@code stop()}.
 *
 * <p>Thread-safe: every completed-write append happens off the FX thread, on
 * {@code EInkBridge}'s single display thread (per {@code EInkDisplayDriver}'s "at most one
 * write in flight" contract), but this class is read from the smoke test's main thread
 * concurrently, hence {@link CopyOnWriteArrayList}.
 *
 * <p>A simulated failure (see {@link #failNextWrite}) never calls the delegate at all, so the
 * wrapped driver's own internal frame numbering (e.g. {@code PngEInkDisplay}'s {@code
 * frameCount}) stays in lock-step with {@link #writeCount()} here. A <em>real</em> write
 * failure on a real driver would not have this property — the delegate's own counter could
 * still advance on a failure this class never recorded — so callers matching a
 * {@link WriteEvent#sequence()} against an output file name should keep that in mind if this
 * class is ever pointed at a driver whose writes can fail for real rather than only by
 * simulation.
 */
final class RecordingDisplayDriver implements EInkDisplayDriver, AutoCloseable {

    /** One completed write, in the order the driver actually finished it. */
    record WriteEvent(int sequence, RefreshMode mode, Optional<Region> region) {}

    record Region(int x, int y, int width, int height) {}

    private final EInkDisplayDriver delegate;
    private final List<WriteEvent> events = new CopyOnWriteArrayList<>();
    private volatile int failOnSequence = -1;
    private volatile boolean failSynchronously;

    RecordingDisplayDriver(EInkDisplayDriver delegate) {
        this.delegate = delegate;
    }

    /**
     * Makes the Nth write (1-based, counting both {@link #writeFrame}/{@link #writeRegion}
     * calls) fail instead of delegating — used to drive and verify {@code EInkBridge}'s
     * write-failure recovery path (a failed write must not leave {@code RenderSession}
     * believing the panel shows content it never actually received — see
     * {@code EInkBridge.recoverFromWriteFailure}'s javadoc). {@code synchronously} {@code true}
     * throws before ever returning a future (mirrors a driver rejecting an out-of-range region,
     * e.g. {@code Ssd1680DisplayDriver.writeRegion}'s validation); {@code false} returns a
     * future that completes exceptionally (mirrors a write that reached hardware and failed
     * there). Call before the write you want to fail; harmless if the target sequence has
     * already passed. Writes {@code failSynchronously} before {@code failOnSequence} so a write
     * racing this call (there should be none — the display thread is the only caller — but the
     * ordering costs nothing) can never observe the new sequence paired with the old mode.
     */
    void failNextWrite(int sequence, boolean synchronously) {
        this.failSynchronously = synchronously;
        this.failOnSequence = sequence;
    }

    List<WriteEvent> events() {
        return List.copyOf(events);
    }

    int writeCount() {
        return events.size();
    }

    /** Blocks (bounded, polling) until {@link #writeCount()} satisfies {@code predicate}, or
     * {@code timeoutMillis} elapses. Returns {@code true} if the condition was met. */
    boolean awaitWriteCount(Predicate<Integer> predicate, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (predicate.test(writeCount())) {
                return true;
            }
            Thread.sleep(5);
        }
        return predicate.test(writeCount());
    }

    @Override public int getWidth() { return delegate.getWidth(); }
    @Override public int getHeight() { return delegate.getHeight(); }
    @Override public Orientation getOrientation() { return delegate.getOrientation(); }
    @Override public DisplayCapabilities getCapabilities() { return delegate.getCapabilities(); }
    @Override public void initialize() { delegate.initialize(); }
    @Override public void reset() { delegate.reset(); }
    @Override public void sleep() { delegate.sleep(); }
    @Override public void wake() { delegate.wake(); }
    @Override public void setRefreshPolicy(RefreshPolicy policy) { delegate.setRefreshPolicy(policy); }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        RefreshMode mode = frame instanceof MonochromeFrame mf ? mf.mode() : RefreshMode.FULL;
        return recordingWrite(mode, Optional.empty(), () -> delegate.writeFrame(frame));
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
        RefreshMode mode = frame instanceof MonochromeFrame mf ? mf.mode() : RefreshMode.PARTIAL;
        Region region = new Region(x, y, width, height);
        return recordingWrite(mode, Optional.of(region), () -> delegate.writeRegion(x, y, width, height, frame));
    }

    private CompletableFuture<Void> recordingWrite(
            RefreshMode mode, Optional<Region> region, java.util.function.Supplier<CompletableFuture<Void>> real) {
        int sequence = events.size() + 1;
        if (sequence == failOnSequence) {
            failOnSequence = -1; // one-shot
            if (failSynchronously) {
                throw new RuntimeException("RecordingDisplayDriver: simulated synchronous write rejection (seq " + sequence + ")");
            }
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new RuntimeException("RecordingDisplayDriver: simulated hardware write failure (seq " + sequence + ")"));
            return failed;
        }
        CompletableFuture<Void> future = real.get();
        return future.whenComplete((v, ex) -> {
            if (ex == null) {
                events.add(new WriteEvent(sequence, mode, region));
            }
        });
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
