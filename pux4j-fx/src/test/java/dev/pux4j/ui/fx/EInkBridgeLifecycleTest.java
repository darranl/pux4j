// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the CP3 review findings (threading and lifecycle) that can be exercised
 * without the JavaFX toolkit (see {@code EInkBridgeDisplayThreadTest}'s javadoc for why none is
 * available). With no toolkit running, {@code Platform.runLater} throws
 * {@code IllegalStateException}. These tests instead drive {@link EInkBridge#runTouchLoop}
 * directly on a plain thread through the bridge's executor test seam, so a "busy FX thread" can
 * be simulated by holding tasks back.
 */
class EInkBridgeLifecycleTest {

    @TempDir
    Path outputDir;

    private EInkBridge.Builder builderWithPngDriver() {
        EInkDisplayDriver display = DisplayDriverFactory.select("png").create(
                null, DriverConfig.builder().property("outputDir", outputDir.toString()).build());
        display.initialize();
        return EInkBridge.builder().display(display);
    }

    /** A driver that reports one held contact on every poll and never blocks. */
    private static final class AlwaysTouchedDriver implements TouchDriver {
        final AtomicInteger reads = new AtomicInteger();
        @Override public void initialize() {}
        @Override public void reset() {}
        @Override public List<TouchPoint> readTouches() {
            reads.incrementAndGet();
            return List.of(new TouchPoint(0, 10, 10, true));
        }
    }

    /**
     * Finding 3: with three capture buffers, a capture taken while one frame is being written
     * and another is queued still finds a free buffer and displaces the stale queued frame
     * (its buffer is recycled). With two, the third capture's poll would return null.
     */
    @Test
    void capture_whileWritingAndQueued_stillGetsBufferAndDisplacesStaleFrame() {
        EInkBridge bridge = builderWithPngDriver().build();

        EInkBridge.CaptureBuffer inFlight = bridge.pollFreeBufferForTest();
        EInkBridge.CaptureBuffer queued = bridge.pollFreeBufferForTest();
        assertNotNull(inFlight);
        assertNotNull(queued);
        bridge.submitRenderWork(new EInkBridge.RenderWork(queued.segment(), queued));

        EInkBridge.CaptureBuffer fresh = bridge.pollFreeBufferForTest();
        assertNotNull(fresh, "a third buffer must be available for the next capture");
        EInkBridge.RenderWork displaced =
                bridge.submitRenderWork(new EInkBridge.RenderWork(fresh.segment(), fresh));

        assertSame(queued, displaced.buffer(), "the stale queued frame is the one displaced");
        assertEquals(1, bridge.freeBufferCount(), "displaced buffer was recycled");
    }

    /**
     * Finding 1: only one hand-off to the FX thread may be outstanding. The executor holds
     * tasks instead of running them (like a busy FX thread); the driver must not be polled
     * again until the held task runs, and must then be polled and dispatched again.
     */
    @Test
    void touchLoop_allowsOnlyOneOutstandingFxDispatch() throws Exception {
        AlwaysTouchedDriver driver = new AlwaysTouchedDriver();
        EInkBridge bridge = builderWithPngDriver().touchInLogicalSpace(driver).build();
        LinkedBlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        bridge.setFxExecutorForTest(held::add);

        Thread t = Thread.ofVirtual().start(bridge::runTouchLoop);
        try {
            Runnable first = held.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "first poll is dispatched");

            Thread.sleep(200); // many poll intervals while the "FX thread" is busy
            assertEquals(1, driver.reads.get(), "no further reads while a dispatch is outstanding");
            assertNull(held.peek(), "no second dispatch queued behind the first");

            first.run(); // FX thread catches up -> releases the permit
            assertNotNull(held.poll(5, TimeUnit.SECONDS), "next poll dispatched after release");
        } finally {
            t.interrupt();
        }
        t.join(2_000);
        assertFalse(t.isAlive());
    }

    /**
     * Finding 2: a touch thread blocked waiting for the FX thread (held touch, busy FX thread)
     * must still exit when interrupted, or {@code stop()} would hang on it.
     */
    @Test
    void touchLoop_blockedOnOutstandingDispatch_exitsOnInterrupt() throws Exception {
        AlwaysTouchedDriver driver = new AlwaysTouchedDriver();
        EInkBridge bridge = builderWithPngDriver().touchInLogicalSpace(driver).build();
        LinkedBlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        bridge.setFxExecutorForTest(held::add);

        Thread t = Thread.ofVirtual().start(bridge::runTouchLoop);
        assertNotNull(held.poll(5, TimeUnit.SECONDS));
        Thread.sleep(100); // thread is now blocked in acquire() on its second dispatch

        t.interrupt();
        t.join(2_000);
        assertFalse(t.isAlive(), "touch loop must exit promptly when interrupted");
    }

    /**
     * A rejected hand-off (executor throws, as {@code Platform.runLater} does with no toolkit)
     * must release the permit, or the loop would block forever on its next dispatch.
     */
    @Test
    void touchLoop_rejectedDispatch_releasesPermit() throws Exception {
        AlwaysTouchedDriver driver = new AlwaysTouchedDriver();
        EInkBridge bridge = builderWithPngDriver().touchInLogicalSpace(driver).build();
        AtomicInteger attempts = new AtomicInteger();
        bridge.setFxExecutorForTest(task -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Toolkit not initialized");
        });

        Thread t = Thread.ofVirtual().start(bridge::runTouchLoop);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (attempts.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        t.interrupt();
        t.join(2_000);
        assertTrue(attempts.get() >= 3, "loop kept dispatching after rejected hand-offs");
        assertFalse(t.isAlive());
    }
}
