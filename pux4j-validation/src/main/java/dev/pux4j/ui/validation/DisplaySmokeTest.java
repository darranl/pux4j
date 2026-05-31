// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.RefreshMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Hardware smoke test that exercises all three refresh modes (FULL, FAST, PARTIAL)
 * across a 7-step sequence to confirm basic display operation.
 *
 * Supports both 2.9" V2 (SSD1675A) and 2.13" V4 (SSD1680) displays.
 * Display dimensions are queried from the driver at runtime.
 *
 * Usage: java -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest [driver]
 *   driver: "ssd1675a" (2.9" V2) or "ssd1680" (2.13" V4)
 *
 * <p><strong>Framebuffer coordinate system (landscape viewing):</strong><br>
 * The framebuffer is natively portrait (narrow × tall). When viewed in landscape:
 * <ul>
 *   <li>Framebuffer <em>row</em> → left-to-right axis on the panel.</li>
 *   <li>Framebuffer <em>column byte</em> (each covering 8 pixels) → top-to-bottom axis.</li>
 * </ul>
 */
public final class DisplaySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(DisplaySmokeTest.class);

    // Display dimensions — populated from driver after initialization
    private static int WIDTH;
    private static int HEIGHT;
    private static int ROW_BYTES;
    private static int FRAME_BYTES;
    private static int CENTER_ROW;
    private static int CENTER_BYTE;
    private static int BLOCK_HALF_ROWS;
    private static int BLOCK_HALF_BYTES;
    private static int INNER_HALF_ROWS;
    private static int INNER_HALF_BYTES;

    public static void main(String[] args) throws Exception {
        String driverName = args.length > 0 ? args[0] : null;
        banner("DisplaySmokeTest starting");
        DisplayDriverFactory factory = findFactory(driverName);
        log.info("driver = {}", factory.name());
        log.info("display dimensions = {}x{} native ({} bytes per frame)", WIDTH, HEIGHT, FRAME_BYTES);
        log.info("landscape view: {}px wide x {}px tall", HEIGHT, WIDTH);

        DriverConfig config = DriverConfig.builder()
            .property("orientation", "LANDSCAPE")
                .build();
        try (Pux4jContext ctx = Pux4jContext.managed()) {
            EInkDisplayDriver driver = factory.create(ctx, config);

            banner("INIT");
            long initStart = System.nanoTime();
            driver.initialize();
            log.info("initialize complete in {} ms", elapsedMs(initStart));

            // Query display dimensions and compute layout constants
            WIDTH = driver.getWidth();
            HEIGHT = driver.getHeight();
            ROW_BYTES = (WIDTH + 7) / 8;  // Ceiling division: ceil(WIDTH / 8)
            FRAME_BYTES = ROW_BYTES * HEIGHT;
            CENTER_ROW = HEIGHT / 2;
            CENTER_BYTE = (WIDTH / 2) / 8;  // Center pixel position converted to byte index
            
            // Scale block sizes proportionally to display height
            // Outer block should be visually prominent, inner creates border effect
            BLOCK_HALF_ROWS = (int)(HEIGHT * 0.16);  // 16% of height on each side = 32% total
            BLOCK_HALF_BYTES = (int)(ROW_BYTES * 0.19); // ~19% of width on each side
            
            // Inner block: inset by 1 byte (8 pixels) on each side to create visible border
            INNER_HALF_ROWS = BLOCK_HALF_ROWS - (BLOCK_HALF_ROWS / 4);  // ~75% of outer block height
            INNER_HALF_BYTES = Math.max(1, BLOCK_HALF_BYTES - 1);  // 1 byte inset from outer block
            
            log.info("display dimensions = {}x{} native ({} bytes per frame)", WIDTH, HEIGHT, FRAME_BYTES);
            log.info("landscape view: {}px wide x {}px tall", HEIGHT, WIDTH);
            log.info("block dimensions: {}x{} px, inner: {}x{} px",
                BLOCK_HALF_ROWS * 2, BLOCK_HALF_BYTES * 8,
                INNER_HALF_ROWS * 2, INNER_HALF_BYTES * 8);

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
            
            // Log block dimensions and positions for verification
            int blockStartRow = CENTER_ROW - BLOCK_HALF_ROWS;
            int blockNumRows = BLOCK_HALF_ROWS * 2;
            int blockStartByte = CENTER_BYTE - BLOCK_HALF_BYTES;
            int blockNumBytes = BLOCK_HALF_BYTES * 2;
            log.info("Step 3 block: rows [{}, {}), bytes [{}, {})",
                blockStartRow, blockStartRow + blockNumRows,
                blockStartByte, blockStartByte + blockNumBytes);
            
            solidBlock(overlayB1, blockStartRow, blockNumRows, blockStartByte, blockNumBytes);
            
            // Verify the block was filled correctly - check center row, center byte
            int centerRowOffset = CENTER_ROW * ROW_BYTES + CENTER_BYTE;
            byte centerValue = overlayB1[centerRowOffset];
            log.info("Step 3 verification: center byte (row={}, byte={}, offset={}) = 0x{} (expect 0x00)",
                CENTER_ROW, CENTER_BYTE, centerRowOffset, String.format("%02X", centerValue & 0xFF));
            
            // Check a few bytes around center to see the pattern
            StringBuilder hexDump = new StringBuilder();
            for (int i = -2; i <= 2; i++) {
                int offset = centerRowOffset + i;
                if (offset >= 0 && offset < overlayB1.length) {
                    hexDump.append(String.format("%02X ", overlayB1[offset] & 0xFF));
                }
            }
            log.info("Step 3 center bytes [{}-2..{}+2]: {}", CENTER_BYTE, CENTER_BYTE, hexDump);
            
            if (centerValue != (byte)0x00) {
                throw new IllegalStateException(
                    "Frame generation error: center of block should be black (0x00), was: 0x"
                    + String.format("%02X", centerValue & 0xFF));
            }
            
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
            
            int whiteBlockStartRow = CENTER_ROW - BLOCK_HALF_ROWS;
            int whiteBlockNumRows = BLOCK_HALF_ROWS * 2;
            int whiteBlockStartByte = CENTER_BYTE - BLOCK_HALF_BYTES;
            int whiteBlockNumBytes = BLOCK_HALF_BYTES * 2;
            log.info("Step 6 white block: rows [{}, {}), bytes [{}, {})",
                whiteBlockStartRow, whiteBlockStartRow + whiteBlockNumRows,
                whiteBlockStartByte, whiteBlockStartByte + whiteBlockNumBytes);
            
            whiteBlock(overlayC1, whiteBlockStartRow, whiteBlockNumRows, 
                       whiteBlockStartByte, whiteBlockNumBytes);
            
            // Verify the white block was filled correctly
            int whiteCenterOffset = CENTER_ROW * ROW_BYTES + CENTER_BYTE;
            byte whiteCenterValue = overlayC1[whiteCenterOffset];
            log.info("Step 6 verification: center byte (row={}, byte={}, offset={}) = 0x{} (expect 0xFF)",
                CENTER_ROW, CENTER_BYTE, whiteCenterOffset, String.format("%02X", whiteCenterValue & 0xFF));
            if (whiteCenterValue != (byte)0xFF) {
                throw new IllegalStateException(
                    "Frame generation error: center of white block should be white (0xFF), was: 0x"
                    + String.format("%02X", whiteCenterValue & 0xFF));
            }
            
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
        }
        log.info("DisplaySmokeTest: end of run");
    }

    // ── Frame patterns ────────────────────────────────────────────────────────

    /**
     * Three evenly-spaced 16-pixel-wide black vertical bars on a white background,
     * as seen in landscape. Scaled to display height.
     */
    private static byte[] threeBarMarker() {
        byte[] f = allWhite();
        int barWidth = Math.max(16, HEIGHT / 18); // ~16px on 2.9", ~14px on 2.13"
        solidRows(f, 0, barWidth);
        solidRows(f, CENTER_ROW - barWidth/2, barWidth);
        solidRows(f, HEIGHT - barWidth, barWidth);
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
        int endByte = Math.min(startByte + numBytes, ROW_BYTES);  // Don't overflow into next row
        for (int row = startRow; row < startRow + numRows; row++) {
            Arrays.fill(f, row * ROW_BYTES + startByte, row * ROW_BYTES + endByte, (byte) 0x00);
        }
    }

    /**
     * Fill a rectangular block with white (0xFF).
     * Parameters as per {@link #solidBlock}.
     */
    private static void whiteBlock(byte[] f, int startRow, int numRows, int startByte, int numBytes) {
        int endByte = Math.min(startByte + numBytes, ROW_BYTES);  // Don't overflow into next row
        for (int row = startRow; row < startRow + numRows; row++) {
            Arrays.fill(f, row * ROW_BYTES + startByte, row * ROW_BYTES + endByte, (byte) 0xFF);
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
        return ServiceLoaderUtil.selectProvider(
                DisplayDriverFactory.class,
                DisplayDriverFactory::name,
                name,
                f -> f.name().equals("png"),  // exclude headless PNG driver from auto-selection
                "display driver factory");
    }
}
