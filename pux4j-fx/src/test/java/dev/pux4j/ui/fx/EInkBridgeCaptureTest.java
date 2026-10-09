// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshPolicy;
import dev.pux4j.ui.transform.RenderOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for Phase 6.4 part C's capture timer / free-list / forced-reset machinery,
 * added after the 2026-09-24 Opus implementation review found that none of it was exercised by
 * any test — which is how {@code captureIntervalElapsed}'s overflow (finding I1) and the
 * poison-pill displacement race (finding I3) both shipped with a fully green {@code mvn test}.
 * Like {@link EInkBridgeDisplayThreadTest}, this drives the package-private seams directly
 * rather than through {@link EInkBridge#start(javafx.scene.Scene)} — no {@code Scene}/toolkit
 * needed (see that class's javadoc for why).
 */
class EInkBridgeCaptureTest {

    @TempDir
    Path outputDir;

    private EInkBridge bridgeWithPngDriver() {
        DisplayDriverFactory factory = DisplayDriverFactory.select("png");
        DriverConfig config = DriverConfig.builder()
                .property("outputDir", outputDir.toString())
                .build();
        EInkDisplayDriver display = factory.create(null, config);
        display.initialize();
        return EInkBridge.builder().display(display).build();
    }

    private static MemorySegment solidBgra(int width, int height, int grayLevel) {
        return MemorySegment.ofArray(solidBgraArray(width, height, grayLevel));
    }

    private static byte[] solidBgraArray(int width, int height, int grayLevel) {
        byte gray = (byte) grayLevel;
        byte[] buf = new byte[width * height * 4];
        for (int i = 0; i < buf.length; i += 4) {
            buf[i] = gray;
            buf[i + 1] = gray;
            buf[i + 2] = gray;
            buf[i + 3] = (byte) 0xFF;
        }
        return buf;
    }

    /** Fills an already-allocated {@link EInkBridge.CaptureBuffer}'s pixels in place, mirroring
     * what {@link EInkBridge#newCaptureBuffer} hands {@code maybeCapture}. */
    private static EInkBridge.CaptureBuffer solidCaptureBuffer(int width, int height, int grayLevel) {
        EInkBridge.CaptureBuffer buffer = EInkBridge.newCaptureBuffer(width * height * 4);
        byte[] solid = solidBgraArray(width, height, grayLevel);
        System.arraycopy(solid, 0, buffer.pixels(), 0, solid.length);
        return buffer;
    }

    /**
     * Finding I1: the original {@code captureIntervalElapsed} used {@code Long.MIN_VALUE} as a
     * "never captured yet" sentinel and computed {@code now - lastCaptureNanos} directly, which
     * overflows a signed {@code long} for any positive {@code now} (i.e. always, since {@code
     * System.nanoTime()} is JVM-uptime-relative and positive) — wrapping to a large negative
     * number and making the interval check permanently false. On a fresh bridge (no capture
     * yet), a real {@code System.nanoTime()} value must report the interval as elapsed.
     */
    @Test
    void captureIntervalElapsed_beforeAnyCapture_isTrueForARealTimestamp() {
        EInkBridge bridge = bridgeWithPngDriver();

        assertTrue(bridge.captureIntervalElapsed(System.nanoTime()),
                "the very first capture attempt must not be blocked by the interval gate");
    }

    /**
     * Finding C6 (verified still correct — this pins it against regression): the buffer
     * belonging to a {@link EInkBridge.RenderWork} must be returned to the free list even when
     * {@code RenderSession.prepare()} finds the frame unchanged — the common case for an idle
     * UI, and one of the two early-return paths a {@code finally} scoped only around the driver
     * write (an earlier draft) would have missed.
     */
    @Test
    void processRenderWork_returnsBufferToFreeList_evenWhenFrameUnchanged() {
        EInkBridge bridge = bridgeWithPngDriver();
        bridge.drainFreeBuffersForTest();
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();

        EInkBridge.CaptureBuffer first = solidCaptureBuffer(w, h, 0x80);
        bridge.processRenderWork(new EInkBridge.RenderWork(first.segment(), first, 1));
        assertEquals(1, bridge.freeBufferCount(), "buffer must be returned after a written frame");

        EInkBridge.CaptureBuffer second = solidCaptureBuffer(w, h, 0x80);
        bridge.processRenderWork(new EInkBridge.RenderWork(second.segment(), second, 2));

        assertEquals(2, bridge.freeBufferCount(),
                "buffer must also be returned on the unchanged-frame early-return path (finding C6)");
    }

    /**
     * Finding C5/C6 (verified still correct — pinned against regression): a displaced item's
     * buffer must be recycled immediately by {@link EInkBridge#offerReplacing}, not only ever by
     * {@link EInkBridge#processRenderWork} — otherwise a capture that never gets consumed
     * because a newer one immediately replaces it would leak its buffer permanently.
     */
    @Test
    void offerReplacing_recyclesDisplacedItemsBufferImmediately() {
        EInkBridge bridge = bridgeWithPngDriver();
        bridge.drainFreeBuffersForTest();
        int size = bridge.displayWidth() * bridge.displayHeight() * 4;
        EInkBridge.CaptureBuffer first = EInkBridge.newCaptureBuffer(size);
        EInkBridge.CaptureBuffer second = EInkBridge.newCaptureBuffer(size);

        bridge.submitRenderWork(new EInkBridge.RenderWork(first.segment(), first, 1));
        assertEquals(0, bridge.freeBufferCount());

        bridge.submitRenderWork(new EInkBridge.RenderWork(second.segment(), second, 2));

        assertEquals(1, bridge.freeBufferCount(),
                "the displaced first item's buffer must come back, not be discarded");
    }

    /**
     * Finding I3: {@code synchronized} on {@link EInkBridge#offerReplacing} fixes a torn
     * poll()/offer() pair between its two producers, but does nothing to stop an <em>ordinary</em>
     * capture from legitimately displacing an already-queued poison pill — which is exactly what
     * "latest wins" is supposed to do. Once the pill is queued, any other submission must be
     * refused outright (its buffer recycled, not queued) rather than bumping the pill out, or
     * {@link EInkBridge#stop()} would block its full drain timeout waiting for a pill that was
     * silently dropped.
     */
    @Test
    void offerReplacing_refusesToDisplaceAQueuedPoisonPill() {
        EInkBridge bridge = bridgeWithPngDriver();
        bridge.queuePoisonPillForTest();

        EInkBridge.RenderWork displaced = bridge.submitRenderWork(
                solidBgra(bridge.displayWidth(), bridge.displayHeight(), 0x00));

        assertNull(displaced, "a capture must not be accepted once the poison pill is queued");
        EInkBridge.RenderWork stillQueued = bridge.peekRenderWork();
        assertNull(stillQueued.bgra(), "the poison pill (bgra == null) must still be the queued item");
        assertNull(stillQueued.buffer());
    }

    /**
     * Finding I4: a forced reset must apply to the first frame captured <em>at or after</em>
     * {@code forceRefresh()} was called, not to whichever frame the display thread happens to
     * process next — a frame already in flight or queued when the request arrived must not
     * consume it. Modeled here via {@link EInkBridge#armForcedReset} and explicit
     * {@code captureSeq} values rather than a live {@code forceRefresh()} call, since the latter
     * needs a {@code Scene}/toolkit this suite deliberately avoids requiring.
     *
     * <p>Under the original (boolean, unconditionally-consumed) implementation this test fails:
     * the {@code captureSeq == 2} frame — content-identical to the baseline, and captured
     * "before" the request — would wrongly consume the reset and, because {@code
     * session.reset()} discards frame history unconditionally, still produce a spurious
     * {@code FULL} write despite being unchanged.
     */
    @Test
    void processRenderWork_forcedResetOnlyAppliesAtOrAfterRequestedSeq() {
        EInkBridge bridge = bridgeWithPngDriver();
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();

        // Baseline: FULL (first frame ever), content A.
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x80), null, 1));
        assertTrue(Files.exists(outputDir.resolve("frame-0001.png")));

        // A forceRefresh() arrives "now" — only a capture from seq 5 onward may honour it.
        bridge.armForcedReset(5);

        // A frame captured before the request, same content as baseline: must be treated as a
        // genuinely unchanged frame — no reset, no write.
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x80), null, 2));
        assertFalse(Files.exists(outputDir.resolve("frame-0002.png")),
                "a frame captured before forceRefresh() must not consume the forced reset (finding I4)");

        // A frame at/after the requested seq, same content: the reset must now apply, forcing a
        // FULL write despite the content being unchanged.
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x80), null, 5));
        assertTrue(Files.exists(outputDir.resolve("frame-0002.png")),
                "a frame at/after the requested seq must get the forced FULL write");
    }

    /**
     * Wraps a real driver but fails the Nth write with a synchronous
     * {@code RuntimeException} (mirrors {@code Ssd1680DisplayDriver.writeRegion}'s "bad
     * coordinates" case — a driver rejecting a frame before ever returning a future) or, at the
     * caller's choice, an exceptionally-completed future ({@code future.get()} throwing
     * {@code ExecutionException} — a write that reached hardware and failed there). Every other
     * write delegates untouched, so frame numbering and PNG content stay meaningful for
     * assertions.
     */
    private static final class FailingWriteDriver implements EInkDisplayDriver {
        private final EInkDisplayDriver delegate;
        private final int failOnCall;
        private final boolean failSynchronously;
        private final AtomicInteger callCount = new AtomicInteger();

        /** Records whether each successful (non-failed) write was a full frame or a region —
         * lets a test assert the recovery write after a failure is a FULL {@code writeFrame},
         * not a {@code writeRegion} (Part D implementation review finding I1's regression). */
        private final java.util.List<String> callLog = new java.util.concurrent.CopyOnWriteArrayList<>();

        FailingWriteDriver(EInkDisplayDriver delegate, int failOnCall, boolean failSynchronously) {
            this.delegate = delegate;
            this.failOnCall = failOnCall;
            this.failSynchronously = failSynchronously;
        }

        java.util.List<String> callLog() {
            return java.util.List.copyOf(callLog);
        }

        private CompletableFuture<Void> maybeFail(
                String kind, java.util.function.Supplier<CompletableFuture<Void>> real) {
            if (callCount.incrementAndGet() == failOnCall) {
                if (failSynchronously) {
                    throw new RuntimeException("simulated synchronous write rejection");
                }
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("simulated hardware write failure"));
                return failed;
            }
            callLog.add(kind);
            return real.get();
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
            return maybeFail("FRAME", () -> delegate.writeFrame(frame));
        }

        @Override
        public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
            return maybeFail("REGION", () -> delegate.writeRegion(x, y, width, height, frame));
        }
    }

    private EInkBridge bridgeWithFailingPngDriver(int failOnCall, boolean failSynchronously) {
        return bridgeWithFailingPngDriver(failOnCall, failSynchronously, null);
    }

    /** {@code renderOptions == null} keeps the builder's own default (FOUR_GRAY for the PNG
     * driver — see {@link DisplayCapabilities#preferredFormat()}), which never reaches the
     * PARTIAL/{@code writeRegion} path at all; pass {@link RenderOptions#monochromeDefaults()}
     * explicitly to exercise that path (Part D implementation review finding M6 — the original
     * write-failure tests only ever covered FOUR_GRAY/writeFrame). */
    private FailingWriteDriver failingDriver;

    private EInkBridge bridgeWithFailingPngDriver(
            int failOnCall, boolean failSynchronously, RenderOptions renderOptions) {
        DisplayDriverFactory factory = DisplayDriverFactory.select("png");
        DriverConfig config = DriverConfig.builder()
                .property("outputDir", outputDir.toString())
                .build();
        EInkDisplayDriver real = factory.create(null, config);
        real.initialize();
        failingDriver = new FailingWriteDriver(real, failOnCall, failSynchronously);
        EInkBridge.Builder builder = EInkBridge.builder().display(failingDriver);
        if (renderOptions != null) {
            builder.renderOptions(renderOptions);
        }
        return builder.build();
    }

    /**
     * Part D / Opus plan review finding I9: a failed write must not silently desynchronise
     * {@code RenderSession}'s idea of the previous frame from what the panel actually shows.
     *
     * <p>{@code RenderSession.prepareMonochrome} sets {@code previous = next} before the driver
     * write is attempted. Without {@link EInkBridge#armResetForNextCapture()} (wired into
     * {@link EInkBridge#processRenderWork}'s failure catch clauses), a later capture of that
     * same content — which the panel never actually received — would diff as "unchanged" and be
     * dropped forever, permanently desyncing the panel from {@code RenderSession}'s belief about
     * it. This test drives that exact sequence: frame 1 (content A) writes successfully; frame 2
     * (content B) is captured but its write fails synchronously; frame 3, captured with the same
     * content B, must still reach the panel as a (forced) write rather than being silently
     * dropped as "unchanged since frame 2".
     */
    @Test
    void processRenderWork_recoversAfterSynchronousWriteFailure_insteadOfDesyncingPreviousFrame() {
        EInkBridge bridge = bridgeWithFailingPngDriver(2, true);
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();

        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x00), null, 1));
        assertTrue(Files.exists(outputDir.resolve("frame-0001.png")), "baseline frame must write");

        // This write is rejected synchronously by the driver (call #2) — content B never
        // actually reaches the panel, but without the fix RenderSession would now believe it did.
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0xFF), null, 2));
        assertFalse(Files.exists(outputDir.resolve("frame-0002.png")),
                "the rejected write must not itself produce a PNG");

        // Same content as the failed attempt — RenderSession must not treat this as "unchanged
        // since the last (failed) write" and drop it; the reset armed by the failure forces it
        // through as a genuine (FULL) write instead.
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0xFF), null, 3));
        assertTrue(Files.exists(outputDir.resolve("frame-0002.png")),
                "content the panel never actually received must not be silently dropped as "
                + "\"unchanged\" after a failed write (finding I9)");
    }

    /** As above, but for the asynchronous failure path ({@code future.get()} throwing {@code
     * ExecutionException} — a write that reached the driver and failed after the fact), to pin
     * both of {@link EInkBridge#processRenderWork}'s failure catch clauses. */
    @Test
    void processRenderWork_recoversAfterAsynchronousWriteFailure_insteadOfDesyncingPreviousFrame() {
        EInkBridge bridge = bridgeWithFailingPngDriver(2, false);
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();

        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x00), null, 1));
        assertTrue(Files.exists(outputDir.resolve("frame-0001.png")));

        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0xFF), null, 2));
        assertFalse(Files.exists(outputDir.resolve("frame-0002.png")));

        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0xFF), null, 3));
        assertTrue(Files.exists(outputDir.resolve("frame-0002.png")),
                "content the panel never actually received must not be silently dropped as "
                + "\"unchanged\" after a failed write (finding I9)");
    }

    /** As {@link #solidBgraArray}, but with an {@code (patchX, patchY, patchSize)} square set
     * to {@code patchGray} instead of the base colour — produces a small, localised diff so
     * {@code RenderSession} picks {@code PARTIAL} rather than upgrading to {@code FULL} on a
     * whole-frame change. */
    private static MemorySegment patchedBgra(
            int width, int height, int baseGray, int patchGray, int patchX, int patchY, int patchSize) {
        byte[] buf = solidBgraArray(width, height, baseGray);
        byte gray = (byte) patchGray;
        for (int y = patchY; y < patchY + patchSize && y < height; y++) {
            for (int x = patchX; x < patchX + patchSize && x < width; x++) {
                int i = (y * width + x) * 4;
                buf[i] = gray;
                buf[i + 1] = gray;
                buf[i + 2] = gray;
                buf[i + 3] = (byte) 0xFF;
            }
        }
        return MemorySegment.ofArray(buf);
    }

    /**
     * Part D implementation review finding I1 — a regression pinned directly against what the
     * {@link #processRenderWork_recoversAfterSynchronousWriteFailure_insteadOfDesyncingPreviousFrame}
     * test above could not catch: the <em>first</em> version of the write-failure fix only
     * armed {@link EInkBridge#resetRequestedSeq} for a future capture (reusing
     * {@code forceRefresh()}'s generation-number mechanism), which left a gap for a frame that
     * was captured <em>before</em> the failure but is still processed <em>after</em> it (e.g.
     * queued behind the failed one, or handed to {@link EInkBridge#processRenderWork} out of
     * capture order, as this test does explicitly via a lower {@code captureSeq}). Under that
     * first version, such a frame's own {@code captureSeq} would not satisfy the armed
     * threshold, so it would still diff against {@code RenderSession}'s now-stale {@code
     * previous} and could go out as an ordinary {@code PARTIAL} composited against content the
     * panel never actually received — exactly the desync this recovery path exists to rule out,
     * just delayed by one frame instead of prevented. The fix ({@code EInkBridge.recoverFromWriteFailure})
     * instead calls {@code session.reset()} directly, synchronously, on the display thread
     * right where the failure is caught — so it applies to the very next
     * {@link EInkBridge#processRenderWork} call regardless of that work item's {@code
     * captureSeq}, forcing an unconditional {@code FULL} {@code writeFrame} (not a {@code
     * writeRegion}), which this test asserts via {@link FailingWriteDriver#callLog()}.
     */
    @Test
    void processRenderWork_recoversWithFullFrame_evenForAFrameCapturedBeforeTheFailure() {
        EInkBridge bridge = bridgeWithFailingPngDriver(2, true, RenderOptions.monochromeDefaults());
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();
        int patch = Math.max(4, Math.min(w, h) / 8);

        // Baseline: FULL (first frame ever).
        bridge.processRenderWork(new EInkBridge.RenderWork(
                patchedBgra(w, h, 0x00, 0x00, 0, 0, patch), null, 1));
        assertEquals(java.util.List.of("FRAME"), failingDriver.callLog());

        // A small patch toggled on — would ordinarily be PARTIAL — but this is call #2, which
        // FailingWriteDriver rejects synchronously. Nothing is recorded in the call log.
        bridge.processRenderWork(new EInkBridge.RenderWork(
                patchedBgra(w, h, 0x00, 0xFF, 10, 10, patch), null, 2));
        assertEquals(java.util.List.of("FRAME"), failingDriver.callLog(),
                "the rejected write must not itself be recorded");

        // A frame with a DIFFERENT small patch, deliberately given a captureSeq lower than the
        // failure's own — modelling one that was captured before the failure but is only
        // processed after it. Under the seq-arming approach this predates the armed threshold
        // and would wrongly diff as an ordinary (small) PARTIAL against the stale `previous`;
        // the direct-reset fix instead forces it through as a FULL, regardless of captureSeq.
        bridge.processRenderWork(new EInkBridge.RenderWork(
                patchedBgra(w, h, 0x00, 0xFF, 20, 20, patch), null, 0));
        assertEquals(java.util.List.of("FRAME", "FRAME"), failingDriver.callLog(),
                "the recovery write must be a full writeFrame, not a writeRegion — even for a "
                + "frame whose captureSeq predates the failure (finding I1)");
    }
}
