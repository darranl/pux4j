// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Byte-for-byte replica of WaveShare's EPD_2IN9_V2 reference partial-refresh
 * sequence, using Pi4J FFM SPI directly with no driver abstraction.
 *
 * Diagnostic test for the round-13..17 partial-refresh investigation:
 *
 *   - Our driver-based partial refresh produces pale-grey output, not the
 *     clean blacks the WaveShare demo produces on the same hardware.
 *   - Our driver-based partial→full transition produces all-black instead
 *     of all-white.
 *
 * This replica strips out all driver abstraction and issues the exact byte
 * sequence WaveShare's C reference issues. It mirrors three reference
 * functions (see /home/darranl/development/waveshare_screen/Touch_e-Paper_Code/c/lib/EPD/EPD_2in9_V2.c):
 *
 *   - EPD_2IN9_V2_Init()                (lines 293-318)
 *   - EPD_2IN9_V2_Display_Base()        (lines 420-437)
 *   - EPD_2IN9_V2_Display_Partial_Wait() (lines 439-481)
 *
 * Interpretation of results:
 *
 *   - Clean black partials + cleanup full restores white: our Pi4J/SPI
 *     layer is fine and the bug is in our driver's higher-level structure
 *     (probably the partialInit register re-init, RST sequence, or some
 *     other ordering choice). Fix is then to refactor the driver to match
 *     this replica.
 *   - Pale-grey partials / cleanup full produces black: a real difference
 *     between Pi4J FFM SPI and WaveShare's lgpio/bcm2835 SPI at the bus
 *     level (CS timing, transfer chunking, GPIO timing). Investigation
 *     pivots to instrumenting that.
 */
public final class WaveShareReplicaTest {

    private static final Logger log = LoggerFactory.getLogger(WaveShareReplicaTest.class);

    private static final int WIDTH       = 128;
    private static final int HEIGHT      = 296;
    private static final int FRAME_BYTES = (WIDTH / 8) * HEIGHT; // 4736

    private static final int DC_PIN   = 25;
    private static final int RST_PIN  = 17;
    private static final int BUSY_PIN = 24;

    // Verbatim from WaveShare's _WF_PARTIAL_2IN9_Wait (EPD_2in9_V2.c lines 57-78).
    private static final byte[] WF_PARTIAL_2IN9_WAIT = {
        0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        (byte) 0x80, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x40, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x00, 0x00, 0x00,
        0x22, 0x17, 0x41, (byte) 0xB0, 0x32, 0x36
    };

    public static void main(String[] args) throws Exception {
        banner("WaveShareReplicaTest starting");
        log.info("Byte-for-byte replica of WaveShare EPD_2IN9_V2 reference flow via Pi4J FFM SPI.");
        log.info("Hardware pins: DC=BCM{}, RST=BCM{}, BUSY=BCM{}", DC_PIN, RST_PIN, BUSY_PIN);
        log.info("SPI: bus=0, channel=0, mode=0, baud=10 MHz (matches WaveShare's lgSpiOpen settings).");

        Context pi4j = Pi4J.newAutoContext();
        try {
            Spi spi = pi4j.create(Spi.newConfigBuilder(pi4j)
                .id("replica-spi").name("Replica SPI")
                .bus(SpiBus.BUS_0).channel(0)
                .mode(SpiMode.MODE_0).baud(10_000_000)
                .build());
            DigitalOutput dc = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("replica-dc").name("DC").bcm(DC_PIN)
                .initial(DigitalState.LOW).shutdown(DigitalState.LOW).build());
            DigitalOutput rst = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("replica-rst").name("RST").bcm(RST_PIN)
                .initial(DigitalState.HIGH).shutdown(DigitalState.HIGH).build());
            DigitalInput busy = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("replica-busy").name("BUSY").bcm(BUSY_PIN)
                .pull(PullResistance.PULL_DOWN).build());

            byte[] allWhite = new byte[FRAME_BYTES];
            Arrays.fill(allWhite, (byte) 0xFF);

            int rowBytes = WIDTH / 8;
            int midRow = HEIGHT / 2;
            byte[] halfBlack = new byte[FRAME_BYTES];
            for (int row = 0; row < HEIGHT; row++) {
                byte fill = (row < midRow) ? (byte) 0x00 : (byte) 0xFF;
                for (int col = 0; col < rowBytes; col++) {
                    halfBlack[row * rowBytes + col] = fill;
                }
            }

            banner("INIT — replicates EPD_2IN9_V2_Init()");
            log.info("Hardware reset (20/2/20 ms) → 100 ms delay → ReadBusy → SW reset → register init.");
            initSequence(spi, dc, rst, busy);

            banner("DISPLAY_BASE — all white baseline (replicates EPD_2IN9_V2_Display_Base)");
            log.info("OBSERVE: panel should go cleanly all-white (flashing full refresh).");
            displayBase(spi, dc, busy, allWhite);
            sleepWithProgress(5_000, "settle after baseline");

            banner("DISPLAY_PARTIAL_WAIT #1 — half-and-half via partial path");
            log.info("OBSERVE (landscape): right half (framebuffer rows 0..147 = 0x00) should drive");
            log.info("CLEANLY BLACK (NOT pale grey). Left half should remain cleanly white.");
            log.info("This is the key visual test — clean black means our driver bug is upstream of SPI.");
            displayPartialWait(spi, dc, rst, busy, halfBlack);
            sleepWithProgress(5_000, "observe partial #1 result");

            banner("DISPLAY_PARTIAL_WAIT #2 — identical content (back-to-back partial)");
            log.info("OBSERVE: panel state should remain the same (IC sees null delta on identical content).");
            log.info("If it hangs or visibly changes, multi-partial behaviour differs from WaveShare's reference.");
            displayPartialWait(spi, dc, rst, busy, halfBlack);
            sleepWithProgress(5_000, "observe partial #2 result");

            banner("DISPLAY_BASE — cleanup all white (partial→full transition)");
            log.info("OBSERVE: panel should return cleanly to all WHITE.");
            log.info("If it produces black (as our driver's round-16 cleanup did), the partial→full");
            log.info("transition issue is independent of any reset sequencing we control.");
            displayBase(spi, dc, busy, allWhite);
            sleepWithProgress(5_000, "observe cleanup full result");

            banner("SLEEP");
            sendCommand(spi, dc, (byte) 0x10, (byte) 0x01); // deep sleep mode 1
            Thread.sleep(100);
            log.info("WaveShareReplicaTest: PASS — completed without exception");
        } catch (Exception e) {
            log.error("WaveShareReplicaTest: FAILED with exception", e);
            throw e;
        } finally {
            log.info("shutting down Pi4J context");
            pi4j.shutdown();
        }
    }

    // -------- Replicated WaveShare sequences --------

    private static void initSequence(Spi spi, DigitalOutput dc, DigitalOutput rst, DigitalInput busy) {
        log.debug("init: hardware reset 20/2/20 ms");
        rst.state(DigitalState.HIGH); delayMs(20);
        rst.state(DigitalState.LOW);  delayMs(2);
        rst.state(DigitalState.HIGH); delayMs(20);
        delayMs(100);
        waitBusy(busy);
        log.debug("init: SW reset (0x12)");
        sendCommand(spi, dc, (byte) 0x12);
        waitBusy(busy);
        log.debug("init: driver output / data entry / window / disp update 1 / cursor");
        sendCommand(spi, dc, (byte) 0x01, (byte) 0x27, (byte) 0x01, (byte) 0x00);
        sendCommand(spi, dc, (byte) 0x11, (byte) 0x03);
        setWindows(spi, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        sendCommand(spi, dc, (byte) 0x21, (byte) 0x00, (byte) 0x80);
        setCursor(spi, dc, 0, 0);
        waitBusy(busy);
        log.debug("init: complete");
    }

    private static void displayBase(Spi spi, DigitalOutput dc, DigitalInput busy, byte[] image) {
        log.debug("displayBase: write 0x24 BW RAM ({} bytes)", image.length);
        sendCommand(spi, dc, (byte) 0x24);
        sendData(spi, dc, image, 0, image.length);
        log.debug("displayBase: write 0x26 RED RAM ({} bytes)", image.length);
        sendCommand(spi, dc, (byte) 0x26);
        sendData(spi, dc, image, 0, image.length);
        log.debug("displayBase: activate (DUC2=0xF7)");
        sendCommand(spi, dc, (byte) 0x22, (byte) 0xF7);
        sendCommand(spi, dc, (byte) 0x20);
        waitBusy(busy);
        log.debug("displayBase: complete");
    }

    private static void displayPartialWait(Spi spi, DigitalOutput dc, DigitalOutput rst,
                                           DigitalInput busy, byte[] image) {
        log.debug("partialWait: brief RST (0.2 ms LOW pulse, no delay or waitBusy after)");
        rst.state(DigitalState.LOW);
        delayMicros(200);
        rst.state(DigitalState.HIGH);

        log.debug("partialWait: load partial LUT (CMD 0x32 + 159 bytes)");
        sendCommand(spi, dc, (byte) 0x32);
        sendData(spi, dc, WF_PARTIAL_2IN9_WAIT, 0, 159);
        waitBusy(busy);

        log.debug("partialWait: 0x37 disp option (10 bytes, byte 5 = 0x40)");
        sendCommand(spi, dc, (byte) 0x37,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);

        log.debug("partialWait: 0x3C border = 0x80");
        sendCommand(spi, dc, (byte) 0x3C, (byte) 0x80);

        log.debug("partialWait: activate clock+analog (DUC2=0xC0)");
        sendCommand(spi, dc, (byte) 0x22, (byte) 0xC0);
        sendCommand(spi, dc, (byte) 0x20);
        waitBusy(busy);

        log.debug("partialWait: setWindow + setCursor");
        setWindows(spi, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(spi, dc, 0, 0);

        log.debug("partialWait: write 0x24 (2500 + 2236 chunks, matches WaveShare exactly)");
        sendCommand(spi, dc, (byte) 0x24);
        sendData(spi, dc, image, 0, 2500);
        sendData(spi, dc, image, 2500, 2236);

        log.debug("partialWait: activate partial display (DUC2=0x0F)");
        sendCommand(spi, dc, (byte) 0x22, (byte) 0x0F);
        sendCommand(spi, dc, (byte) 0x20);
        waitBusy(busy);
        log.debug("partialWait: complete");
    }

    // -------- Low-level helpers --------

    private static void setWindows(Spi spi, DigitalOutput dc, int xStart, int yStart, int xEnd, int yEnd) {
        sendCommand(spi, dc, (byte) 0x44,
            (byte) ((xStart >> 3) & 0xFF),
            (byte) ((xEnd >> 3) & 0xFF));
        sendCommand(spi, dc, (byte) 0x45,
            (byte) (yStart & 0xFF), (byte) ((yStart >> 8) & 0xFF),
            (byte) (yEnd & 0xFF),   (byte) ((yEnd >> 8) & 0xFF));
    }

    private static void setCursor(Spi spi, DigitalOutput dc, int x, int y) {
        sendCommand(spi, dc, (byte) 0x4E, (byte) ((x >> 3) & 0xFF));
        sendCommand(spi, dc, (byte) 0x4F, (byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF));
    }

    private static void sendCommand(Spi spi, DigitalOutput dc, byte cmd, byte... data) {
        dc.state(DigitalState.LOW);
        spi.transfer(new byte[]{cmd});
        if (data.length > 0) {
            dc.state(DigitalState.HIGH);
            spi.transfer(data);
        }
    }

    // Linux spidev default bufsiz is 4096 bytes; chunk larger transfers to stay
    // within it. WaveShare's reference avoids this by sending Display_Base
    // byte-by-byte and splitting Display_Partial_Wait writes into 2500 + 2236
    // chunks. Chunking at 4096 here matches our production driver's behaviour
    // and keeps Display_Partial_Wait's two-chunk split intact (both 2500 and
    // 2236 are under the limit so they remain single transfers).
    private static final int SPI_CHUNK = 4096;

    private static void sendData(Spi spi, DigitalOutput dc, byte[] data, int offset, int length) {
        dc.state(DigitalState.HIGH);
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, SPI_CHUNK);
            spi.transfer(data, pos, chunk);
            pos       += chunk;
            remaining -= chunk;
        }
    }

    private static void waitBusy(DigitalInput busy) {
        long start = System.currentTimeMillis();
        long deadline = start + 10_000;
        while (busy.isHigh()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("SSD1675A BUSY pin stuck HIGH after 10s");
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= 5) {
            log.debug("waitBusy: BUSY released after {} ms", elapsed);
        }
    }

    private static void delayMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // Busy-spin micro delay. Thread.sleep's granularity is too coarse for the
    // 0.2 ms RST pulse WaveShare's partial sequence uses.
    private static void delayMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
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
    }

    private static void banner(String title) {
        log.info("");
        log.info("============================================================");
        log.info("== {}", title);
        log.info("============================================================");
    }
}
