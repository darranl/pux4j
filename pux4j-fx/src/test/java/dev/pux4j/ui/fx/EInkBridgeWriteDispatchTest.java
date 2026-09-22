// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.transform.RenderOptions;
import dev.pux4j.ui.transform.TransformStrategy;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers two parts of {@link EInkBridge#processRenderWork} that {@code
 * EInkBridgeDisplayThreadTest} (built around the real PNG driver) doesn't reach: the {@code
 * writeRegion} dispatch branch (a PARTIAL decision must hand the driver the cropped region
 * frame at the region's own coordinates, not the full frame — the design doc calls this out as
 * the landmine case, since passing the full frame "produces garbage on hardware"), and
 * observing {@link EInkBridge#isWriteInFlight()} while a write is genuinely still outstanding
 * rather than only after {@code processRenderWork} has already returned (which the PNG-driver
 * tests can only do, since that driver's write completes synchronously within the call).
 * Uses a small hand-built recording {@link EInkDisplayDriver} fake (this codebase's established
 * style — no mocking library in any module) whose futures the test completes on its own
 * schedule.
 */
class EInkBridgeWriteDispatchTest {

    private static final int SIZE = 16; // matches RenderSessionTest's fixture, so the known-good PARTIAL case ports directly

    /** Records every writeFrame()/writeRegion() call; each write's future is controlled
     * explicitly by the test via {@link #completePendingWrite()} rather than completing
     * itself, so a test can observe {@code isWriteInFlight()} while one is outstanding. */
    private static final class RecordingDisplayDriver implements EInkDisplayDriver {
        private static final DisplayCapabilities CAPS = new DisplayCapabilities(
                EnumSet.of(PixelFormat.MONOCHROME),
                EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
                true,
                Optional.of(new AlignmentConstraints(8)));

        record WriteRegionCall(int x, int y, int width, int height, FrameData frame) {}

        final List<FrameData> writeFrameCalls = new ArrayList<>();
        final List<WriteRegionCall> writeRegionCalls = new ArrayList<>();
        private volatile CompletableFuture<Void> pending;

        @Override public int getWidth() { return SIZE; }
        @Override public int getHeight() { return SIZE; }
        @Override public Orientation getOrientation() { return Orientation.PORTRAIT; }
        @Override public DisplayCapabilities getCapabilities() { return CAPS; }
        @Override public void initialize() {}
        @Override public void reset() {}
        @Override public void sleep() {}
        @Override public void wake() {}

        @Override
        public CompletableFuture<Void> writeFrame(FrameData frame) {
            writeFrameCalls.add(frame);
            pending = new CompletableFuture<>();
            return pending;
        }

        @Override
        public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
            writeRegionCalls.add(new WriteRegionCall(x, y, width, height, frame));
            pending = new CompletableFuture<>();
            return pending;
        }

        void completePendingWrite() {
            CompletableFuture<Void> f = pending;
            if (f != null) {
                f.complete(null);
            }
        }
    }

    private static MemorySegment allWhiteBgra(int size) {
        byte[] buf = new byte[size * size * 4];
        Arrays.fill(buf, (byte) 0xFF);
        return MemorySegment.ofArray(buf);
    }

    /** Blackens one pixel — same coordinates and expected resulting Region(0,5,8,1) as
     * RenderSessionTest.smallChangeProducesPartialWithCorrectCroppedRegion, so this test's
     * PARTIAL trigger is known-good, not something this test discovers for itself. */
    private static void blacken(MemorySegment bgra, int size, int x, int y) {
        long off = ((long) y * size + x) * 4;
        bgra.set(ValueLayout.JAVA_BYTE, off, (byte) 0);
        bgra.set(ValueLayout.JAVA_BYTE, off + 1, (byte) 0);
        bgra.set(ValueLayout.JAVA_BYTE, off + 2, (byte) 0);
    }

    private static EInkBridge bridgeWithRecordingDriver(RecordingDisplayDriver driver) {
        return EInkBridge.builder()
                .display(driver)
                .renderOptions(RenderOptions.builder()
                        .pixelFormat(PixelFormat.MONOCHROME)
                        .strategy(TransformStrategy.THRESHOLD)
                        .maxPartials(10)
                        .mergeThresholdPx(0)
                        .build())
                .build();
    }

    @Test
    void processRenderWork_partialDecision_dispatchesWriteRegionWithCroppedFrameAtExactCoordinates() throws Exception {
        RecordingDisplayDriver driver = new RecordingDisplayDriver();
        EInkBridge bridge = bridgeWithRecordingDriver(driver);
        MemorySegment bgra = allWhiteBgra(SIZE);

        // processRenderWork() blocks on the driver's future until it completes — this test's
        // driver deliberately doesn't auto-complete (see RecordingDisplayDriver's javadoc), so
        // each call has to run on its own thread while the test completes the future from here;
        // calling processRenderWork() directly on this thread would deadlock against itself.
        runAndAwaitCompletion(bridge, new EInkBridge.RenderWork(bgra), driver); // FULL: establishes the baseline

        blacken(bgra, SIZE, 4, 5);
        runAndAwaitCompletion(bridge, new EInkBridge.RenderWork(bgra), driver); // PARTIAL

        assertEquals(1, driver.writeFrameCalls.size(), "only the first (FULL) call should use writeFrame");
        assertEquals(1, driver.writeRegionCalls.size(), "the second (PARTIAL) call must use writeRegion, not writeFrame again");

        RecordingDisplayDriver.WriteRegionCall call = driver.writeRegionCalls.get(0);
        assertEquals(0, call.x());
        assertEquals(5, call.y());
        assertEquals(8, call.width());
        assertEquals(1, call.height());

        MonochromeFrame frame = (MonochromeFrame) call.frame();
        assertEquals(1, frame.data().length,
                "the frame handed to writeRegion must be cropped to the region's own stride "
                        + "(ceil(8/8)*1 = 1 byte) — the full 16x16 frame would be 32 bytes and would "
                        + "\"produce garbage on hardware\" per the design doc's Threading section");
    }

    /**
     * Unlike the PNG-driver tests (whose write completes synchronously inside {@code
     * processRenderWork}, so {@code isWriteInFlight()} can only ever be observed as {@code
     * false} from outside the call), this drives {@code processRenderWork} on a background
     * thread against a driver whose future the test controls, so {@code isWriteInFlight()} can
     * genuinely be caught {@code true} mid-write — proving the flag is actually set around the
     * write, not just correctly cleared afterwards (a bug that set it too late, or never at
     * all, would still pass a "false after the call returns" assertion).
     */
    @Test
    void processRenderWork_whileWriteIsOutstanding_isWriteInFlightIsTrue_thenFalseAfterCompletion() throws Exception {
        RecordingDisplayDriver driver = new RecordingDisplayDriver();
        EInkBridge bridge = bridgeWithRecordingDriver(driver);
        MemorySegment bgra = allWhiteBgra(SIZE);

        Thread worker = new Thread(() -> bridge.processRenderWork(new EInkBridge.RenderWork(bgra)));
        worker.start();
        try {
            awaitTrue(() -> !driver.writeFrameCalls.isEmpty(), 2_000);
            awaitTrue(bridge::isWriteInFlight, 2_000);

            driver.completePendingWrite();
            worker.join(2_000);
            assertFalse(worker.isAlive(), "processRenderWork should have returned once the write completed");
            assertFalse(bridge.isWriteInFlight());
        } finally {
            worker.interrupt();
        }
    }

    /**
     * Runs {@code bridge.processRenderWork(work)} on a background thread, waits for it to
     * actually call the driver (not just for the thread to start), completes that write, and
     * joins — i.e. drives exactly one write through {@link RecordingDisplayDriver}'s
     * manually-controlled futures without deadlocking the calling thread against itself.
     */
    private static void runAndAwaitCompletion(
            EInkBridge bridge, EInkBridge.RenderWork work, RecordingDisplayDriver driver) throws Exception {
        int callsBefore = driver.writeFrameCalls.size() + driver.writeRegionCalls.size();
        Thread worker = new Thread(() -> bridge.processRenderWork(work));
        worker.start();
        try {
            awaitTrue(() -> driver.writeFrameCalls.size() + driver.writeRegionCalls.size() > callsBefore, 2_000);
            driver.completePendingWrite();
            worker.join(2_000);
            assertFalse(worker.isAlive(), "processRenderWork should have returned once the write completed");
        } finally {
            worker.interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within " + timeoutMs + " ms");
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}
