// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import com.pi4j.Pi4J;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.RefreshMode;
import jakarta.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.ServiceLoader;

/**
 * Phase 1a hardware smoke test: exercises all three refresh modes
 * (FULL, FAST, PARTIAL) across a 7-step sequence.
 *
 * Run on Pi 500+ with the 2.9" V2 HAT attached.
 *
 * Usage: java -ea -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest [driver]
 *   driver defaults to "ssd1675a"
 *
 * Panel orientation note: the framebuffer is 128 wide × 296 tall (native IC
 * portrait layout). When the HAT is held in landscape (long edge horizontal),
 * framebuffer Y maps to the panel's horizontal axis and X to its vertical axis.
 * Observe annotations below describe what you see when holding it in landscape.
 */
public final class DisplaySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(DisplaySmokeTest.class);

    private static final int WIDTH      = 128;
    private static final int HEIGHT     = 296;
    private static final int ROW_BYTES  = WIDTH / 8;
    private static final int FRAME_BYTES = ROW_BYTES * HEIGHT; // 4736

    public static void main(String[] args) throws Exception {
        String driverName = args.length > 0 ? args[0] : "ssd1675a";
        banner("DisplaySmokeTest starting");
        log.info("driver = {}", driverName);
        log.info("display dimensions = {}x{} ({} bytes per frame)", WIDTH, HEIGHT, FRAME_BYTES);

        var factory = findFactory(driverName);
        log.info("found DisplayDriverFactory: {}", factory.getClass().getName());

        var pi4j = Pi4J.newAutoContext();
        EInkDisplayDriver driver = null;
        try {
            var json = Json.createObjectBuilder()
                .add("orientation", "PORTRAIT")
                .build();
            var config = DriverConfig.ofHardware(pi4j, json);
            driver = factory.create(config);

            banner("INIT");
            long initStart = System.nanoTime();
            driver.initialize();
            log.info("initialize complete in {} ms", elapsedMs(initStart));

            // ── Step 1: FULL slow (shape set A) ──────────────────────────────
            // Shape set A: left quarter black, right three-quarters white,
            // with a horizontal midline separator in the middle third.
            banner("Step 1: FULL refresh — shape set A");
            log.info("OBSERVE (landscape): leftmost quarter of panel black, rest white, dark band through centre");
            doFull(driver, shapeA(), "shape set A");
            sleepWithProgress(4_000, "observe step 1");

            // ── Step 2: FAST full (shape set B) ──────────────────────────────
            // Shape set B: 8-pixel horizontal stripes.
            // Fast full clears with no visible flash; result should be clean stripes.
            banner("Step 2: FAST refresh — shape set B (stripes)");
            log.info("OBSERVE (landscape): alternating 8-pixel black/white vertical bands, no flash");
            doFast(driver, shapeB(), "shape set B — stripes");
            sleepWithProgress(4_000, "observe step 2");

            // ── Step 3: PARTIAL overlay on B ─────────────────────────────────
            // Overlay the same stripes with the top 32 rows driven black.
            // Both 0x24 (new) and 0x26 (previous = shape B) are set, so the
            // IC computes the diff correctly.
            banner("Step 3: PARTIAL refresh — overlay: top band black");
            log.info("OBSERVE (landscape): rightmost band (top 32 fb-rows) now solid black; stripes elsewhere");
            byte[] overlayB1 = shapeB();
            solidRows(overlayB1, 0, 32);
            doPartial(driver, overlayB1, "overlay B1 — top band black");
            sleepWithProgress(4_000, "observe step 3");

            // ── Step 4: second PARTIAL (update overlay) ───────────────────────
            // Drive the next 32 rows also black.
            banner("Step 4: PARTIAL refresh — overlay: next band also black");
            log.info("OBSERVE (landscape): two rightmost bands now solid black");
            byte[] overlayB2 = overlayB1.clone();
            solidRows(overlayB2, 32, 32);
            doPartial(driver, overlayB2, "overlay B2 — two top bands black");
            sleepWithProgress(4_000, "observe step 4");

            // ── Step 5: FULL slow — shape set A again ────────────────────────
            // Confirms partial→full transition works correctly (no inversion,
            // no residual ghosting from the partial overlays).
            banner("Step 5: FULL refresh — shape set A again (partial→full recovery)");
            log.info("OBSERVE (landscape): same as step 1 — leftmost quarter black, band through centre");
            log.info("  If the panel shows all-black or inverted output, partial→full transition is broken");
            doFull(driver, shapeA(), "shape set A again — recovery check");
            sleepWithProgress(4_000, "observe step 5");

            // ── Step 6: FAST full — shape set C ──────────────────────────────
            // Shape set C: 8×8 checker pattern. FAST after a slow FULL.
            banner("Step 6: FAST refresh — shape set C (8×8 checker)");
            log.info("OBSERVE (landscape): fine 8×8 checker pattern, no flash");
            doFast(driver, shapeC(), "shape set C — checker");
            sleepWithProgress(4_000, "observe step 6");

            // ── Step 7: PARTIAL overlay on C ─────────────────────────────────
            // Drive the bottom 48 rows white to create a clear "label area"
            // on the checker background.
            banner("Step 7: PARTIAL refresh — overlay: bottom band white");
            log.info("OBSERVE (landscape): leftmost band (~48 fb-rows) now solid white, checker elsewhere");
            byte[] overlayC = shapeC();
            whiteRows(overlayC, HEIGHT - 48, 48);
            doPartial(driver, overlayC, "overlay C — bottom band white");
            sleepWithProgress(4_000, "observe step 7");

            banner("SLEEP & SHUTDOWN");
            driver.sleep();
            log.info("DisplaySmokeTest: PASS — all 7 steps completed without exception");
        } catch (Exception e) {
            log.error("DisplaySmokeTest: FAILED with exception", e);
            throw e;
        } finally {
            log.info("shutting down Pi4J context");
            pi4j.shutdown();
            log.info("DisplaySmokeTest: end of run");
        }
    }

    // ── Frame patterns ────────────────────────────────────────────────────────

    /**
     * Shape set A: leftmost quarter of the fb rows black (rows 0..73), a
     * 16-pixel-wide black band through the middle third (rows 118..133),
     * remainder white.
     */
    private static byte[] shapeA() {
        byte[] f = allWhite();
        solidRows(f, 0,   HEIGHT / 4);
        solidRows(f, (HEIGHT / 2) - 8, 16);
        return f;
    }

    /** Shape set B: repeating 8-pixel black/white rows. */
    private static byte[] shapeB() {
        byte[] f = new byte[FRAME_BYTES];
        for (int row = 0; row < HEIGHT; row++) {
            byte fill = ((row / 8) % 2 == 0) ? (byte) 0x00 : (byte) 0xFF;
            Arrays.fill(f, row * ROW_BYTES, (row + 1) * ROW_BYTES, fill);
        }
        return f;
    }

    /** Shape set C: 8×8 checker pattern (fb-pixel granularity). */
    private static byte[] shapeC() {
        byte[] f = new byte[FRAME_BYTES];
        for (int row = 0; row < HEIGHT; row++) {
            for (int byteCol = 0; byteCol < ROW_BYTES; byteCol++) {
                // Each byte covers 8 pixels; checker is 8-pixel squares so
                // one byte alternates in block-phase depending on column index.
                byte val = (((row / 8) + byteCol) % 2 == 0) ? (byte) 0x00 : (byte) 0xFF;
                f[row * ROW_BYTES + byteCol] = val;
            }
        }
        return f;
    }

    private static byte[] allWhite() {
        byte[] f = new byte[FRAME_BYTES];
        Arrays.fill(f, (byte) 0xFF);
        return f;
    }

    /** Fill {@code count} rows starting at {@code startRow} with black (0x00). */
    private static void solidRows(byte[] f, int startRow, int count) {
        Arrays.fill(f, startRow * ROW_BYTES, (startRow + count) * ROW_BYTES, (byte) 0x00);
    }

    /** Fill {@code count} rows starting at {@code startRow} with white (0xFF). */
    private static void whiteRows(byte[] f, int startRow, int count) {
        Arrays.fill(f, startRow * ROW_BYTES, (startRow + count) * ROW_BYTES, (byte) 0xFF);
    }

    // ── Refresh helpers ───────────────────────────────────────────────────────

    private static void doFull(EInkDisplayDriver driver, byte[] frame, String label) throws Exception {
        log.info("→ writeFrame FULL '{}' starting", label);
        long start = System.nanoTime();
        driver.writeFrame(new MonochromeFrame(frame, RefreshMode.FULL)).get();
        log.info("← writeFrame FULL '{}' complete in {} ms", label, elapsedMs(start));
    }

    private static void doFast(EInkDisplayDriver driver, byte[] frame, String label) throws Exception {
        log.info("→ writeFrame FAST '{}' starting", label);
        long start = System.nanoTime();
        driver.writeFrame(new MonochromeFrame(frame, RefreshMode.FAST)).get();
        log.info("← writeFrame FAST '{}' complete in {} ms", label, elapsedMs(start));
    }

    private static void doPartial(EInkDisplayDriver driver, byte[] frame, String label) throws Exception {
        log.info("→ writeFrame PARTIAL '{}' starting", label);
        long start = System.nanoTime();
        driver.writeFrame(new MonochromeFrame(frame, RefreshMode.PARTIAL)).get();
        log.info("← writeFrame PARTIAL '{}' complete in {} ms", label, elapsedMs(start));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void sleepWithProgress(long totalMs, String reason) throws InterruptedException {
        log.info("... pausing {} ms ({})", totalMs, reason);
        long step      = 1_000;
        long remaining = totalMs;
        while (remaining > 0) {
            long sleep = Math.min(step, remaining);
            Thread.sleep(sleep);
            remaining -= sleep;
            if (remaining > 0) {
                log.info("...   {} ms remaining", remaining);
            }
        }
        log.info("... pause complete");
    }

    private static void banner(String title) {
        log.info("");
        log.info("============================================================");
        log.info("== {}", title);
        log.info("============================================================");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static DisplayDriverFactory findFactory(String name) {
        return ServiceLoader.load(DisplayDriverFactory.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(f -> f.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No DisplayDriverFactory named '" + name + "' found via ServiceLoader"));
    }
}
