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

import java.util.ServiceLoader;

/**
 * Phase 1a hardware smoke test: initialise the SSD1675A and write a test pattern.
 * Run on Pi 500+ with the 2.9" V2 HAT attached.
 *
 * Usage: java -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest [driver]
 *   driver defaults to "ssd1675a"
 */
public final class DisplaySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(DisplaySmokeTest.class);

    private static final int WIDTH  = 128;
    private static final int HEIGHT = 296;
    private static final int FRAME_BYTES = (WIDTH / 8) * HEIGHT; // 4736

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
            log.info("calling driver.initialize() — runs configureFullRefreshMode (hardware reset + SW reset + register init)");
            long initStart = System.nanoTime();
            driver.initialize();
            log.info("initialize complete in {} ms", elapsedMs(initStart));

            int rowBytes = WIDTH / 8;

            // ============================================================
            // FULL REFRESH PHASE — exercises full-refresh path 3 times.
            // Both 0x24 and 0x26 RAM are populated by each full refresh, so
            // by the end of this phase the IC has a clean all-white baseline.
            //
            // Panel orientation: the framebuffer is 128 wide × 296 tall (native
            // portrait IC layout). When held in landscape (long edge horizontal),
            // framebuffer Y is the panel's HORIZONTAL axis and framebuffer X is
            // the panel's VERTICAL axis. OBSERVE: lines below describe what the
            // user sees on the panel held in landscape.
            // ============================================================
            banner("FULL REFRESH #1 — half black / half white split");
            log.info("OBSERVE (landscape view): left half of panel black, right half white, clean midline boundary");
            byte[] halfAndHalf = new byte[FRAME_BYTES];
            int midRow = HEIGHT / 2;
            for (int row = 0; row < HEIGHT; row++) {
                byte fill = (row < midRow) ? (byte)0x00 : (byte)0xFF;
                for (int col = 0; col < rowBytes; col++) {
                    halfAndHalf[row * rowBytes + col] = fill;
                }
            }
            doFull(driver, halfAndHalf, "half black / half white");
            sleepWithProgress(5_000, "settling after half-and-half");

            banner("FULL REFRESH #2 — 8-pixel stripes");
            log.info("OBSERVE (landscape view): alternating 8-pixel-wide vertical black/white stripes across the panel");
            byte[] stripes = new byte[FRAME_BYTES];
            for (int row = 0; row < HEIGHT; row++) {
                byte fill = ((row / 8) % 2 == 0) ? (byte)0x00 : (byte)0xFF;
                for (int col = 0; col < rowBytes; col++) {
                    stripes[row * rowBytes + col] = fill;
                }
            }
            doFull(driver, stripes, "8-pixel stripes");
            sleepWithProgress(5_000, "settling after stripes");

            banner("FULL REFRESH #3 — all white (partial-refresh baseline)");
            log.info("OBSERVE: panel goes fully white. After this both 0x24 and 0x26 hold all-white.");
            byte[] allWhite = new byte[FRAME_BYTES];
            java.util.Arrays.fill(allWhite, (byte)0xFF);
            doFull(driver, allWhite, "all-white baseline");
            sleepWithProgress(3_000, "settling before partial sequence");

            // ============================================================
            // PARTIAL→FULL TRANSITION FIX VERIFICATION — round 17b.
            //
            // Round 16 (with hardware reset on partial→full): cleanup full
            // produced all-black instead of all-white.
            // Round 17a (no cleanup): confirmed partial path is solid.
            // Round 17b (this round): driver changed to skip hardware reset
            // on partial→full transition (matching WaveShare's Display_Base).
            // If the fix is right, the cleanup full should now correctly
            // produce all-white.
            //
            // Orientation reminder: framebuffer rows 0..147 (black) appear
            // on panel RIGHT half in landscape, rows 148..295 (white) appear
            // on panel LEFT half.
            // ============================================================
            byte[] halfPartialFrame = new byte[FRAME_BYTES];
            for (int row = 0; row < HEIGHT; row++) {
                byte fill = (row < midRow) ? (byte) 0x00 : (byte) 0xFF;
                for (int col = 0; col < rowBytes; col++) {
                    halfPartialFrame[row * rowBytes + col] = fill;
                }
            }

            banner("PARTIAL REFRESH — half black / half white via partial path");
            log.info("OBSERVE (landscape): right half drives toward black (pale grey at our drive level),");
            log.info("left half holds cleanly white. Same as round 16 partial result.");
            doPartial(driver, halfPartialFrame, "half black / half white via partial");
            sleepWithProgress(5_000, "observe partial result");

            // Cleanup full refresh — under the round 17b driver change this
            // path no longer does a hardware reset; it just writes 0x24+0x26
            // and activates 0xF7. Expected result: panel cleanly all-white.
            banner("CLEANUP FULL REFRESH — all white (partial→full transition test)");
            log.info("OBSERVE: panel should return to fully white. With the round-17b driver fix,");
            log.info("this transition no longer does a hardware reset, matching WaveShare's Display_Base flow.");
            log.info("If panel ends up black again (as in round 16), the hardware reset wasn't the root cause.");
            doFull(driver, allWhite, "cleanup all-white");
            sleepWithProgress(5_000, "observe cleanup full refresh result");

            banner("SLEEP & SHUTDOWN");
            log.info("calling driver.sleep()");
            driver.sleep();
            log.info("DisplaySmokeTest: PASS — completed without exception");
        } catch (Exception e) {
            log.error("DisplaySmokeTest: FAILED with exception", e);
            throw e;
        } finally {
            log.info("shutting down Pi4J context");
            pi4j.shutdown();
            log.info("DisplaySmokeTest: end of run");
        }
    }

    private static void doFull(EInkDisplayDriver driver, byte[] frame, String label) throws Exception {
        log.info("→ writeFrame FULL '{}' starting", label);
        long start = System.nanoTime();
        driver.writeFrame(new MonochromeFrame(frame, RefreshMode.FULL)).get();
        log.info("← writeFrame FULL '{}' complete in {} ms", label, elapsedMs(start));
    }

    private static void doPartial(EInkDisplayDriver driver, byte[] frame, String label) throws Exception {
        log.info("→ writeFrame PARTIAL '{}' starting", label);
        long start = System.nanoTime();
        driver.writeFrame(new MonochromeFrame(frame, RefreshMode.PARTIAL)).get();
        log.info("← writeFrame PARTIAL '{}' complete in {} ms", label, elapsedMs(start));
    }

    private static void sleepWithProgress(long totalMs, String reason) throws InterruptedException {
        log.info("... pausing {} ms ({})", totalMs, reason);
        long step = 1_000;
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
