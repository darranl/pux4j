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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the display thread's consume/prepare/write pipeline — the part of Phase 6.4 part B
 * that doesn't require a live JavaFX {@code Scene}. Drives {@link EInkBridge#processRenderWork}
 * directly rather than through {@link EInkBridge#start(javafx.scene.Scene)}: even bare
 * {@code Scene} construction requires the JavaFX toolkit to be started (confirmed empirically —
 * {@code new Scene(root, w, h)} NPEs in {@code QuantumToolkit} without a prior
 * {@code Platform.startup()}), and JavaFX 23 has no headless Glass platform to start it with
 * (Q7 in the design doc — that arrives in JavaFX 26). Requiring a live display server in this
 * test suite would undo exactly what Phase 6.3's PNG/programmatic drivers exist to avoid.
 * {@code ScenePicker}'s node-level traversal doesn't need a {@code Scene} at all and is
 * covered separately in {@code ScenePickerTest}; actual touch event injection and
 * {@link EInkBridge#start}/{@link EInkBridge#stop}'s threaded lifecycle remain untested here —
 * see {@code EInkBridgeTest}'s class javadoc and the design doc's testing-strategy section,
 * which explicitly doesn't require FX-thread/capture tests for Phase 6.
 *
 * <p>Uses the real {@code png} driver (Phase 6.3), configured with a per-test temp
 * {@code outputDir}, so a written frame is an observable file on disk rather than an assertion
 * against internal state.
 */
class EInkBridgeDisplayThreadTest {

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
        byte gray = (byte) grayLevel;
        byte[] buf = new byte[width * height * 4];
        for (int i = 0; i < buf.length; i += 4) {
            buf[i] = gray;
            buf[i + 1] = gray;
            buf[i + 2] = gray;
            buf[i + 3] = (byte) 0xFF;
        }
        return MemorySegment.ofArray(buf);
    }

    /**
     * {@code RenderSession.prepare()} treats the first call as an unconditional FULL refresh
     * (no previous frame to diff against) — this is the whole pipeline (prepare → decide →
     * write) working end to end, evidenced by the PNG driver's output file actually landing on
     * disk (see this class's javadoc for why {@code future.get()} completing means the file
     * write has already happened, not just been queued).
     */
    @Test
    void processRenderWork_firstFrame_writesToDisk() {
        EInkBridge bridge = bridgeWithPngDriver();
        MemorySegment bgra = solidBgra(bridge.displayWidth(), bridge.displayHeight(), 0x80);

        bridge.processRenderWork(new EInkBridge.RenderWork(bgra));

        assertTrue(Files.exists(outputDir.resolve("frame-0001.png")));
    }

    /**
     * The second call with byte-identical content must be a no-op all the way through —
     * {@code RenderSession.prepare()} returns empty, so {@code processRenderWork} never calls
     * the driver — proving the diff-as-dirty-check design (Scene Capture section of the
     * design doc) actually works, not just that unchanged input happens not to crash anything.
     * Uses a freshly-allocated (not reused) {@link MemorySegment} with the same pixel values,
     * since the check must be content equality, not object identity.
     */
    @Test
    void processRenderWork_secondCallWithUnchangedFrame_writesNothing() {
        EInkBridge bridge = bridgeWithPngDriver();
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();

        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x80)));
        bridge.processRenderWork(new EInkBridge.RenderWork(solidBgra(w, h, 0x80)));

        assertTrue(Files.exists(outputDir.resolve("frame-0001.png")));
        assertFalse(Files.exists(outputDir.resolve("frame-0002.png")),
                "unchanged frame must not reach the driver as a second write");
    }

    /**
     * {@code isWriteInFlight()} is the backpressure flag Phase 6.4 part C's capture timer
     * will poll; {@code processRenderWork} blocks on the write's future, so by the time it
     * returns the flag must already be back to false — never left stuck true.
     */
    @Test
    void processRenderWork_clearsWriteInFlightBeforeReturning() {
        EInkBridge bridge = bridgeWithPngDriver();
        MemorySegment bgra = solidBgra(bridge.displayWidth(), bridge.displayHeight(), 0x00);

        bridge.processRenderWork(new EInkBridge.RenderWork(bgra));

        assertFalse(bridge.isWriteInFlight());
    }

    /**
     * Pins the "latest wins" queue policy in isolation from the display thread: two submits
     * before anything is taken must leave only the second item behind, not both (which would
     * make the display thread write a stale frame first) and not neither (which would drop
     * real work). Uses {@link EInkBridge#peekRenderWork()} rather than starting the display
     * thread, so this doesn't need {@link EInkBridge#start} (and therefore no {@code Scene} —
     * see this class's javadoc). Also pins {@code submitRenderWork}'s return value — the
     * displaced item on the second call, {@code null} on the first — since a future free-list
     * caller (Phase 6.4 part C) depends on it to recycle a displaced buffer instead of losing
     * track of it.
     */
    @Test
    void submitRenderWork_secondSubmitBeforeConsumption_replacesFirst() {
        EInkBridge bridge = bridgeWithPngDriver();
        int w = bridge.displayWidth();
        int h = bridge.displayHeight();
        MemorySegment first = solidBgra(w, h, 0x00);
        MemorySegment second = solidBgra(w, h, 0xFF);

        EInkBridge.RenderWork displacedByFirst = bridge.submitRenderWork(first);
        EInkBridge.RenderWork displacedBySecond = bridge.submitRenderWork(second);

        assertNull(displacedByFirst, "nothing was queued yet for the first submit to displace");
        assertSame(first, displacedBySecond.bgra(), "second submit must displace exactly the first");
        assertSame(second, bridge.peekRenderWork().bgra());
    }

    /**
     * Regression test for a finding from this phase's review: {@code stop()} used to be a
     * pure no-op whenever {@link EInkBridge#start} had never been called, even though
     * {@code builder().build()}/{@code fromConfig()} already construct and initialise the
     * display driver (and, for {@code fromConfig()}, open a managed {@code Pux4jContext}) —
     * both need releasing regardless of whether the threaded lifecycle ever started. This
     * doesn't need a {@code Scene} at all, unlike testing {@code start()} itself.
     */
    @Test
    void stop_withoutEverStarting_doesNotThrowAndIsIdempotent() {
        EInkBridge bridge = bridgeWithPngDriver();

        bridge.stop();
        bridge.stop(); // must not double-release or throw on the second call
    }
}
