// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
