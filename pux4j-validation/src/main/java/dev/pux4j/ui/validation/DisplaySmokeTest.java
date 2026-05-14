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
 * Run on Pi 500+ with the 2.9" V2 HAT attached in <em>landscape</em> orientation
 * (long edge horizontal, USB ports on the right).
 *
 * Usage: java -ea -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest [driver]
 *   driver defaults to "ssd1675a"
 *
 * <p><strong>Framebuffer coordinate system (landscape viewing):</strong><br>
 * The SSD1675A framebuffer is natively 128 wide × 296 tall (portrait IC layout).
 * When the HAT is held landscape (long edge horizontal):
 * <ul>
 *   <li>Framebuffer <em>row</em> (0..295) → left-to-right axis on the panel.</li>
 *   <li>Framebuffer <em>column byte</em> (0..15, each covering 8 pixels) → top-to-bottom axis.</li>
 * </ul>
 * So {@code solidRows(f, 100, 96)} paints a 96-pixel-wide vertical band centred
 * on the panel when viewed in landscape, and {@code solidBlock(f, r, h, 6, 4)}
 * paints a rectangle 32 px tall positioned in the vertical centre.
 */
public final class DisplaySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(DisplaySmokeTest.class);

    // Native IC framebuffer dimensions (portrait layout).
    // In landscape view: HEIGHT pixels wide, WIDTH pixels tall.
    private static final int WIDTH       = 128;
    private static final int HEIGHT      = 296;
    private static final int ROW_BYTES   = WIDTH / 8;   // 16 bytes per row
    private static final int FRAME_BYTES = ROW_BYTES * HEIGHT; // 4736

    // Centre of the frame in landscape coordinates.
    private static final int CENTER_ROW      = HEIGHT / 2;          // 148
    private static final int CENTER_BYTE     = ROW_BYTES / 2;       //   8
    // Dimensions for centre overlay blocks (landscape pixels).
    private static final int BLOCK_HALF_ROWS = 48;                  // ±48 → 96 px wide
    private static final int BLOCK_HALF_BYTES = 3;                  // ±3  → 6 bytes = 48 px tall
    private static final int INNER_HALF_ROWS = 32;                  // hollow-box interior
    private static final int INNER_HALF_BYTES = 2;

    public static void main(String[] args) throws Exception {
        String driverName = args.length > 0 ? args[0] : "ssd1675a";
        banner("DisplaySmokeTest starting");
        log.info("driver = {}", driverName);
        log.info("display dimensions = {}x{} native ({} bytes per frame)", WIDTH, HEIGHT, FRAME_BYTES);
        log.info("landscape view: {}px wide x {}px tall", HEIGHT, WIDTH);

        var factory = findFactory(driverName);
        log.info("found DisplayDriverFactory: {}", factory.getClass().getName());

        var pi4j = Pi4J.newAutoContext();
        try {
            var json = Json.createObjectBuilder()
                .add("orientation", "LANDSCAPE")
                .build();
            var config = DriverConfig.ofHardware(pi4j, json);
            var driver = factory.create(config);

            banner("INIT");
            long initStart = System.nanoTime();
            driver.initialize();
            log.info("initialize complete in {} ms", elapsedMs(initStart));

            // ── Step 1: FULL slow — three-bar marker ──────────────────────────
            // Three evenly-spaced vertical bars in landscape: left edge, centre,
            // right edge. Unmistakably intentional; confirms full-refresh polarity.
            banner("Step 1: FULL refresh — three-bar marker");
            log.info("OBSERVE (landscape): 3 black vertical bars — left edge, centre, right edge; white gaps");
            doFull(driver, threeBarMarker(), "three-bar marker");
            sleepWithProgress(4_000, "observe step 1");

            // ── Step 2: FAST — stripes ────────────────────────────────────────
            // 8-pixel alternating black/white stripes cover the whole panel
            // without a white flash. If a flash is visible, FAST LUT is broken.
            banner("Step 2: FAST refresh — shape B (8px stripes)");
            log.info("OBSERVE (landscape): alternating 8px black/white vertical bands, no white flash");
            byte[] stripes = shapeB();
            doFast(driver, stripes, "shape B — stripes");
            sleepWithProgress(4_000, "observe step 2");

            // ── Step 3: PARTIAL — solid centre block ──────────────────────────
            // Paint a solid black rectangle in the centre of the stripe background.
            // Partial waveform should update only the block area; stripes unchanged.
            banner("Step 3: PARTIAL refresh — solid black centre block");
            log.info("OBSERVE (landscape): solid black rectangle appears in centre; stripes unchanged around it");
            byte[] overlayB1 = stripes.clone();
            solidBlock(overlayB1, CENTER_ROW - BLOCK_HALF_ROWS, BLOCK_HALF_ROWS * 2,
                                  CENTER_BYTE - BLOCK_HALF_BYTES, BLOCK_HALF_BYTES * 2);
            doPartial(driver, overlayB1, "centre block — solid black");
            sleepWithProgress(4_000, "observe step 3");

            // ── Step 4: PARTIAL — hollow the block (create a frame) ──────────
            // Erase the interior of the block to white, leaving a black border.
            // Tests a second consecutive partial update on the same region.
            banner("Step 4: PARTIAL refresh — hollow centre block (black border, white interior)");
            log.info("OBSERVE (landscape): centre block becomes a black-bordered rectangle with white interior");
            byte[] overlayB2 = overlayB1.clone();
            whiteBlock(overlayB2, CENTER_ROW - INNER_HALF_ROWS, INNER_HALF_ROWS * 2,
                                  CENTER_BYTE - INNER_HALF_BYTES, INNER_HALF_BYTES * 2);
            doPartial(driver, overlayB2, "centre block — hollow (frame)");
            sleepWithProgress(4_000, "observe step 4");

            // ── Step 5: FAST — checker ────────────────────────────────────────
            // Switch to 8×8 checker without a white flash. Tests FAST after
            // two consecutive PARTIAL refreshes.
            banner("Step 5: FAST refresh — shape C (8×8 checker)");
            log.info("OBSERVE (landscape): fine 8×8 checker pattern replaces stripes, no white flash");
            byte[] checker = shapeC();
            doFast(driver, checker, "shape C — checker");
            sleepWithProgress(4_000, "observe step 5");

            // ── Step 6: PARTIAL — solid white centre window ───────────────────
            // Paint a solid white rectangle in the centre of the checker.
            // The white "window" on the checker background is clearly visible.
            banner("Step 6: PARTIAL refresh — white centre window on checker");
            log.info("OBSERVE (landscape): solid white rectangle appears in centre; checker unchanged around it");
            byte[] overlayC1 = checker.clone();
            whiteBlock(overlayC1, CENTER_ROW - BLOCK_HALF_ROWS, BLOCK_HALF_ROWS * 2,
                                  CENTER_BYTE - BLOCK_HALF_BYTES, BLOCK_HALF_BYTES * 2);
            doPartial(driver, overlayC1, "centre window — white on checker");
            sleepWithProgress(4_000, "observe step 6");

            // ── Step 7: PARTIAL — black icon inside white window ──────────────
            // Draw a smaller solid black rectangle inside the white window.
            // Demonstrates a second partial update composing on top of step 6.
            banner("Step 7: PARTIAL refresh — black icon inside white window");
            log.info("OBSERVE (landscape): small solid black rectangle appears inside the white window");
            byte[] overlayC2 = overlayC1.clone();
            solidBlock(overlayC2, CENTER_ROW - INNER_HALF_ROWS, INNER_HALF_ROWS * 2,
                                  CENTER_BYTE - INNER_HALF_BYTES, INNER_HALF_BYTES * 2);
            doPartial(driver, overlayC2, "icon — black inside white window");
            sleepWithProgress(4_000, "observe step 7");

            // ── Final: FULL clear — all white ────────────────────────────────
            // WaveShare recommend not leaving pixels set when the display goes
            // to sleep, as sustained pixel states can cause long-term burn-in.
            banner("Final: FULL refresh — all white (screen clear before sleep)");
            log.info("OBSERVE (landscape): panel flashes and clears to solid white");
            doFull(driver, allWhite(), "all-white screen clear");
            sleepWithProgress(2_000, "observe final clear");

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
     * Three evenly-spaced 16-pixel-wide black vertical bars on a white background,
     * as seen in landscape. Left edge bar: rows 0..15; centre bar: rows 140..155;
     * right edge bar: rows 280..295.
     */
    private static byte[] threeBarMarker() {
        byte[] f = allWhite();
        solidRows(f, 0,           16);
        solidRows(f, CENTER_ROW - 8, 16);
        solidRows(f, HEIGHT - 16, 16);
        return f;
    }

    /** Shape set B: repeating 8-pixel black/white rows (= vertical stripes in landscape). */
    private static byte[] shapeB() {
        byte[] f = new byte[FRAME_BYTES];
        for (int row = 0; row < HEIGHT; row++) {
            byte fill = ((row / 8) % 2 == 0) ? (byte) 0x00 : (byte) 0xFF;
            Arrays.fill(f, row * ROW_BYTES, (row + 1) * ROW_BYTES, fill);
        }
        return f;
    }

    /** Shape set C: 8×8 checker pattern. */
    private static byte[] shapeC() {
        byte[] f = new byte[FRAME_BYTES];
        for (int row = 0; row < HEIGHT; row++) {
            for (int byteCol = 0; byteCol < ROW_BYTES; byteCol++) {
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

    /** Fill {@code numRows} rows starting at {@code startRow} with black (0x00). */
    private static void solidRows(byte[] f, int startRow, int numRows) {
        Arrays.fill(f, startRow * ROW_BYTES, (startRow + numRows) * ROW_BYTES, (byte) 0x00);
    }

    /**
     * Fill a rectangular block with black (0x00).
     * {@code startRow}/{@code numRows} select fb rows (landscape left-right axis);
     * {@code startByte}/{@code numBytes} select byte columns within each row
     * (landscape top-bottom axis, each byte = 8 pixels).
     */
    private static void solidBlock(byte[] f, int startRow, int numRows, int startByte, int numBytes) {
        for (int row = startRow; row < startRow + numRows; row++) {
            Arrays.fill(f, row * ROW_BYTES + startByte, row * ROW_BYTES + startByte + numBytes, (byte) 0x00);
        }
    }

    /**
     * Fill a rectangular block with white (0xFF).
     * Parameters as per {@link #solidBlock}.
     */
    private static void whiteBlock(byte[] f, int startRow, int numRows, int startByte, int numBytes) {
        for (int row = startRow; row < startRow + numRows; row++) {
            Arrays.fill(f, row * ROW_BYTES + startByte, row * ROW_BYTES + startByte + numBytes, (byte) 0xFF);
        }
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
