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
 * Manual-CS replica of WaveShare's EPD_2IN9_V2 reference partial-refresh
 * sequence. Pure Pi4J — no FFM, no /dev/mem.
 *
 * Requires that `/boot/firmware/config.txt` contains:
 *   dtoverlay=spi0-2cs,cs0_pin=26,cs1_pin=23
 *
 * which moves the kernel SPI controller's CE0/CE1 pins off BCM 8/7 so we
 * can claim BCM 8 as a regular Pi4J `DigitalOutput`. After this change,
 * BCM 8 shows as "unused input" in `gpioinfo gpiochip0` and BCM 26/23
 * show as "spi0 CS0/CS1" (kernel-owned but unconnected to the panel).
 *
 * Differs from {@link WaveShareReplicaTest} in ONE place: CS is held LOW
 * across each command+data sequence (via the manual {@code cs} GPIO),
 * whereas in WaveShareReplicaTest the kernel toggles CE0 per
 * {@code spi.transfer()} ioctl, so CS pulses HIGH momentarily between
 * the cmd byte and the data bytes.
 *
 * If this produces clean black partials and clean white cleanup full,
 * CS toggling within a logical command was the root cause and the path
 * forward is to either (a) document the device tree overlay as a project
 * setup requirement and ship a manual-CS driver, or (b) propose a Pi4J
 * upstream change that wraps this pattern in a clean API.
 *
 * If it still produces pale grey, CS within-logical-command toggling was
 * not the root cause and we have to look elsewhere.
 */
public final class ManualCsReplicaTest {

    private static final Logger log = LoggerFactory.getLogger(ManualCsReplicaTest.class);

    private static final int WIDTH       = 128;
    private static final int HEIGHT      = 296;
    private static final int FRAME_BYTES = (WIDTH / 8) * HEIGHT; // 4736

    private static final int DC_PIN   = 25;
    private static final int RST_PIN  = 17;
    private static final int BUSY_PIN = 24;
    private static final int CS_PIN   = 8;  // Manual CS — requires the dtoverlay above

    // Linux spidev default bufsiz; chunk larger transfers within this limit.
    private static final int SPI_CHUNK = 4096;

    // Verbatim from WaveShare _WF_PARTIAL_2IN9_Wait.
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
        banner("ManualCsReplicaTest starting");
        log.info("WaveShare reference flow via Pi4J FFM SPI with MANUAL CS on BCM {}.", CS_PIN);
        log.info("Requires dtoverlay=spi0-2cs,cs0_pin=26,cs1_pin=23 in /boot/firmware/config.txt.");

        Context pi4j = Pi4J.newAutoContext();
        try {
            Spi spi = pi4j.create(Spi.newConfigBuilder(pi4j)
                .id("manualcs-spi").name("Manual-CS SPI")
                .bus(SpiBus.BUS_0).channel(0)
                .mode(SpiMode.MODE_0).baud(10_000_000)
                .build());
            DigitalOutput dc = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("manualcs-dc").name("DC").bcm(DC_PIN)
                .initial(DigitalState.LOW).shutdown(DigitalState.LOW).build());
            DigitalOutput rst = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("manualcs-rst").name("RST").bcm(RST_PIN)
                .initial(DigitalState.HIGH).shutdown(DigitalState.HIGH).build());
            DigitalInput busy = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("manualcs-busy").name("BUSY").bcm(BUSY_PIN)
                .pull(PullResistance.PULL_DOWN).build());
            DigitalOutput cs = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("manualcs-cs").name("Manual CS").bcm(CS_PIN)
                .initial(DigitalState.HIGH).shutdown(DigitalState.HIGH).build());

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
            initSequence(spi, cs, dc, rst, busy);

            banner("DISPLAY_BASE — all white baseline");
            log.info("OBSERVE: panel should go cleanly all-white.");
            displayBase(spi, cs, dc, busy, allWhite);
            sleepWithProgress(5_000, "settle after baseline");

            banner("DISPLAY_PARTIAL_WAIT #1 — half-and-half");
            log.info("OBSERVE (landscape): right half of panel should drive CLEAN BLACK (not pale grey).");
            log.info("Left half should remain cleanly white.");
            log.info("This is the key test for CS-toggling hypothesis.");
            displayPartialWait(spi, cs, dc, rst, busy, halfBlack);
            sleepWithProgress(5_000, "observe partial #1");

            banner("DISPLAY_PARTIAL_WAIT #2 — identical content (back-to-back partial)");
            log.info("OBSERVE: panel state should remain the same (IC sees null delta).");
            displayPartialWait(spi, cs, dc, rst, busy, halfBlack);
            sleepWithProgress(5_000, "observe partial #2");

            banner("DISPLAY_BASE — cleanup all white (partial→full transition)");
            log.info("OBSERVE: panel should return cleanly to all WHITE (not black).");
            displayBase(spi, cs, dc, busy, allWhite);
            sleepWithProgress(5_000, "observe cleanup");

            banner("SLEEP");
            sendCommand(spi, cs, dc, (byte) 0x10, (byte) 0x01);
            Thread.sleep(100);
            log.info("ManualCsReplicaTest: PASS — completed without exception");
        } catch (Exception e) {
            log.error("ManualCsReplicaTest: FAILED with exception", e);
            throw e;
        } finally {
            pi4j.shutdown();
        }
    }

    // ---- WaveShare-equivalent sequences ----

    private static void initSequence(Spi spi, DigitalOutput cs, DigitalOutput dc,
                                     DigitalOutput rst, DigitalInput busy) throws Exception {
        log.debug("init: hardware reset 20/2/20 ms");
        rst.state(DigitalState.HIGH); Thread.sleep(20);
        rst.state(DigitalState.LOW);  Thread.sleep(2);
        rst.state(DigitalState.HIGH); Thread.sleep(20);
        Thread.sleep(100);
        waitBusy(busy);
        log.debug("init: SW reset");
        sendCommand(spi, cs, dc, (byte) 0x12);
        waitBusy(busy);
        log.debug("init: driver output / data entry / window / disp update 1 / cursor");
        sendCommand(spi, cs, dc, (byte) 0x01, (byte) 0x27, (byte) 0x01, (byte) 0x00);
        sendCommand(spi, cs, dc, (byte) 0x11, (byte) 0x03);
        setWindows(spi, cs, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        sendCommand(spi, cs, dc, (byte) 0x21, (byte) 0x00, (byte) 0x80);
        setCursor(spi, cs, dc, 0, 0);
        waitBusy(busy);
    }

    private static void displayBase(Spi spi, DigitalOutput cs, DigitalOutput dc,
                                    DigitalInput busy, byte[] image) {
        log.debug("displayBase: write 0x24 BW RAM ({} bytes)", image.length);
        sendCommandData(spi, cs, dc, (byte) 0x24, image, 0, image.length);
        log.debug("displayBase: write 0x26 RED RAM ({} bytes)", image.length);
        sendCommandData(spi, cs, dc, (byte) 0x26, image, 0, image.length);
        log.debug("displayBase: activate (DUC2=0xF7)");
        sendCommand(spi, cs, dc, (byte) 0x22, (byte) 0xF7);
        sendCommand(spi, cs, dc, (byte) 0x20);
        waitBusy(busy);
    }

    private static void displayPartialWait(Spi spi, DigitalOutput cs, DigitalOutput dc,
                                           DigitalOutput rst, DigitalInput busy, byte[] image) {
        log.debug("partialWait: brief RST (0.2 ms LOW)");
        rst.state(DigitalState.LOW);
        delayMicros(200);
        rst.state(DigitalState.HIGH);

        log.debug("partialWait: load partial LUT (CMD 0x32 + 159 bytes)");
        sendCommandData(spi, cs, dc, (byte) 0x32, WF_PARTIAL_2IN9_WAIT, 0, 159);
        waitBusy(busy);

        log.debug("partialWait: 0x37 disp option");
        sendCommand(spi, cs, dc, (byte) 0x37,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);

        log.debug("partialWait: 0x3C border = 0x80");
        sendCommand(spi, cs, dc, (byte) 0x3C, (byte) 0x80);

        log.debug("partialWait: activate clock+analog (DUC2=0xC0)");
        sendCommand(spi, cs, dc, (byte) 0x22, (byte) 0xC0);
        sendCommand(spi, cs, dc, (byte) 0x20);
        waitBusy(busy);

        log.debug("partialWait: setWindow + setCursor");
        setWindows(spi, cs, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(spi, cs, dc, 0, 0);

        log.debug("partialWait: write 0x24 frame ({} bytes) — CS held LOW across cmd + chunks", image.length);
        sendCommandData(spi, cs, dc, (byte) 0x24, image, 0, image.length);

        log.debug("partialWait: activate partial display (DUC2=0x0F)");
        sendCommand(spi, cs, dc, (byte) 0x22, (byte) 0x0F);
        sendCommand(spi, cs, dc, (byte) 0x20);
        waitBusy(busy);
    }

    // ---- Helpers ----

    /**
     * Send a command byte (DC LOW) with optional trailing data bytes (DC HIGH).
     * **CS is held LOW for the entire cmd+data sequence** — the whole point.
     * The kernel will still toggle its CE0/CE1 on BCM 26/23 around each
     * underlying spi.transfer ioctl, but those pins are unconnected so the
     * panel doesn't see those toggles. The panel sees our BCM 8 staying LOW.
     */
    private static void sendCommand(Spi spi, DigitalOutput cs, DigitalOutput dc, byte cmd, byte... data) {
        cs.state(DigitalState.LOW);
        dc.state(DigitalState.LOW);
        spi.transfer(new byte[]{cmd});
        if (data.length > 0) {
            dc.state(DigitalState.HIGH);
            spi.transfer(data);
        }
        cs.state(DigitalState.HIGH);
    }

    /**
     * Send cmd + bulk data from a byte array offset/length. Used for the
     * 0x24/0x26 frame writes and 0x32 LUT load. CS held LOW throughout,
     * including across the SPI chunk boundary (since each spi.transfer
     * call only toggles the kernel's CE pin which is now unconnected).
     */
    private static void sendCommandData(Spi spi, DigitalOutput cs, DigitalOutput dc,
                                        byte cmd, byte[] data, int offset, int length) {
        cs.state(DigitalState.LOW);
        dc.state(DigitalState.LOW);
        spi.transfer(new byte[]{cmd});
        dc.state(DigitalState.HIGH);
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, SPI_CHUNK);
            spi.transfer(data, pos, chunk);
            pos       += chunk;
            remaining -= chunk;
        }
        cs.state(DigitalState.HIGH);
    }

    private static void setWindows(Spi spi, DigitalOutput cs, DigitalOutput dc,
                                   int xStart, int yStart, int xEnd, int yEnd) {
        sendCommand(spi, cs, dc, (byte) 0x44,
            (byte) ((xStart >> 3) & 0xFF), (byte) ((xEnd >> 3) & 0xFF));
        sendCommand(spi, cs, dc, (byte) 0x45,
            (byte) (yStart & 0xFF), (byte) ((yStart >> 8) & 0xFF),
            (byte) (yEnd & 0xFF),   (byte) ((yEnd >> 8) & 0xFF));
    }

    private static void setCursor(Spi spi, DigitalOutput cs, DigitalOutput dc, int x, int y) {
        sendCommand(spi, cs, dc, (byte) 0x4E, (byte) ((x >> 3) & 0xFF));
        sendCommand(spi, cs, dc, (byte) 0x4F, (byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF));
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
