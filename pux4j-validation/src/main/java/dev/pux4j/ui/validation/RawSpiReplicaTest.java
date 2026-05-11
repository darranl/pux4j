// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * Raw-SPI replica of WaveShare's EPD_2IN9_V2 reference partial-refresh sequence.
 *
 * Differs from {@link WaveShareReplicaTest} (Pi4J FFM SPI) and
 * {@link ManualCsReplicaTest} (Pi4J FFM SPI + manual CS) in one place:
 * this test bypasses Pi4J FFM's SPI implementation entirely. It opens
 * /dev/spidev0.0 directly via Java FFM and issues SPI_IOC_MESSAGE(1)
 * ioctls itself. GPIO (DC, RST, BUSY, manual CS on BCM 8) still goes
 * through Pi4J FFM.
 *
 * If this produces clean black partials, Pi4J FFM SPI is the
 * differentiator and we can isolate the fix there. If it still produces
 * pale grey, the SPI ioctl layer is not the differentiator and the next
 * step is to bypass Pi4J GPIO too (option B from the tracker).
 *
 * Requires that `/boot/firmware/config.txt` contains:
 *   dtoverlay=spi0-2cs,cs0_pin=26,cs1_pin=23
 * so BCM 8 is a free GPIO and not held by the SPI controller's CE0.
 *
 * Native access required: this module needs --enable-native-access at
 * launch. The run script (`run-raw-replica-test.sh`) sets that flag.
 */
public final class RawSpiReplicaTest {

    private static final Logger log = LoggerFactory.getLogger(RawSpiReplicaTest.class);

    // ---- Display geometry ----
    private static final int WIDTH       = 128;
    private static final int HEIGHT      = 296;
    private static final int FRAME_BYTES = (WIDTH / 8) * HEIGHT; // 4736

    // ---- BCM pin assignments ----
    private static final int DC_PIN   = 25;
    private static final int RST_PIN  = 17;
    private static final int BUSY_PIN = 24;
    private static final int CS_PIN   = 8;  // BCM 8 = CE0; manually driven here

    // ---- SPI configuration ----
    private static final String SPI_DEVICE = "/dev/spidev0.0";
    private static final int    SPI_SPEED_HZ = 10_000_000;
    private static final byte   SPI_BITS_PER_WORD = 8;

    // SPI mode bits (Linux spidev). Mode 0 = no CPHA, no CPOL.
    //
    // We originally OR'd in SPI_NO_CS (0x40) so the kernel would not drive any
    // CS line. The RP1 SPI driver on Pi 5+ rejects SPI_NO_CS with EINVAL —
    // SPI_IOC_WR_MODE returns -1. So we drop the flag; with the
    // dtoverlay=spi0-2cs,cs0_pin=26,cs1_pin=23 device tree change, the
    // kernel's CE0 is on BCM 26 (unconnected), so leaving the kernel free to
    // toggle "its" CS pin is harmless. We drive BCM 8 (the panel's actual CS
    // line) manually via Pi4J DigitalOutput.
    private static final byte SPI_MODE_0 = 0x00;
    private static final byte SPI_MODE   = SPI_MODE_0;

    // open(2) flags
    private static final int O_RDWR = 2;

    // SPI ioctl numbers. Computed via Linux _IOW macro:
    //   _IOW('k', nr, size) = (1<<30) | (size<<16) | ('k'<<8) | nr
    // 'k' = 0x6B (SPI magic).
    private static final long SPI_IOC_WR_MODE          = 0x40016B01L; // _IOW('k', 1, __u8)
    private static final long SPI_IOC_WR_BITS_PER_WORD = 0x40016B03L; // _IOW('k', 3, __u8)
    private static final long SPI_IOC_WR_MAX_SPEED_HZ  = 0x40046B04L; // _IOW('k', 4, __u32)
    // SPI_IOC_MESSAGE(1): _IOW('k', 0, char[32]) where 32 = sizeof(struct spi_ioc_transfer)
    private static final long SPI_IOC_MESSAGE_1        = 0x40206B00L;

    // Size of struct spi_ioc_transfer on 64-bit Linux (matches Pi4J's SpiIocTransfer layout).
    private static final long SPI_IOC_TRANSFER_BYTES = 32L;

    // ---- libc handles ----
    private static final MethodHandle OPEN_HANDLE;
    private static final MethodHandle CLOSE_HANDLE;
    private static final MethodHandle IOCTL_HANDLE;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();

        // open(const char *pathname, int flags)
        OPEN_HANDLE = linker.downcallHandle(
            libc.find("open").orElseThrow(() -> new IllegalStateException("open not found in libc")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // close(int fd)
        CLOSE_HANDLE = linker.downcallHandle(
            libc.find("close").orElseThrow(() -> new IllegalStateException("close not found in libc")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // ioctl(int fd, unsigned long request, void *arg)
        IOCTL_HANDLE = linker.downcallHandle(
            libc.find("ioctl").orElseThrow(() -> new IllegalStateException("ioctl not found in libc")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    }

    // ---- Partial LUT (verbatim from WaveShare _WF_PARTIAL_2IN9_Wait) ----
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

    public static void main(String[] args) throws Throwable {
        banner("RawSpiReplicaTest starting");
        log.info("Bypasses Pi4J FFM SPI. Opens {} directly via Java FFM, sets mode=0x{} (mode 0).",
            SPI_DEVICE, String.format("%02X", SPI_MODE & 0xFF));
        log.info("Uses Pi4J DigitalOutput for BCM {} as manual CS (kernel CE0 is harmlessly on BCM 26).", CS_PIN);

        Context pi4j = Pi4J.newAutoContext();
        try (Arena arena = Arena.ofShared()) {
            DigitalOutput cs = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("raw-cs").name("Manual CS").bcm(CS_PIN)
                .initial(DigitalState.HIGH).shutdown(DigitalState.HIGH).build());
            DigitalOutput dc = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("raw-dc").name("DC").bcm(DC_PIN)
                .initial(DigitalState.LOW).shutdown(DigitalState.LOW).build());
            DigitalOutput rst = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                .id("raw-rst").name("RST").bcm(RST_PIN)
                .initial(DigitalState.HIGH).shutdown(DigitalState.HIGH).build());
            DigitalInput busy = pi4j.create(DigitalInput.newConfigBuilder(pi4j)
                .id("raw-busy").name("BUSY").bcm(BUSY_PIN)
                .pull(PullResistance.PULL_DOWN).build());

            int spiFd = openSpiDevice(arena);
            try {
                configureSpi(arena, spiFd);

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
                initSequence(arena, spiFd, cs, dc, rst, busy);

                banner("DISPLAY_BASE — all white baseline");
                log.info("OBSERVE: panel should go cleanly all-white (flashing full refresh).");
                displayBase(arena, spiFd, cs, dc, busy, allWhite);
                sleepWithProgress(5_000, "settle after baseline");

                banner("DISPLAY_PARTIAL_WAIT #1 — half-and-half via partial path");
                log.info("OBSERVE (landscape): right half should drive CLEAN BLACK (not pale grey).");
                log.info("This is the key test: clean black = CS handling was the bug.");
                displayPartialWait(arena, spiFd, cs, dc, rst, busy, halfBlack);
                sleepWithProgress(5_000, "observe partial #1");

                banner("DISPLAY_PARTIAL_WAIT #2 — identical content");
                log.info("OBSERVE: panel state should remain the same (IC sees null delta).");
                displayPartialWait(arena, spiFd, cs, dc, rst, busy, halfBlack);
                sleepWithProgress(5_000, "observe partial #2");

                // Partial #3 — true delta showcase. Starting from the half/half
                // state, add a white circle on the black (right) half and a
                // black circle on the white (left) half. The IC has to drive a
                // white→black transition on one region and a black→white
                // transition on another, while holding everything else
                // unchanged. This is the partial-refresh workload that gives
                // WaveShare's drawing app its "only changed pixels move"
                // behaviour.
                byte[] withCircles = new byte[FRAME_BYTES];
                System.arraycopy(halfBlack, 0, withCircles, 0, FRAME_BYTES);
                // Framebuffer rows 0..147 (= panel RIGHT half in landscape) are
                // currently 0x00 black → draw a WHITE circle there.
                drawCircle(withCircles, rowBytes, 64, 74, 30, /* black= */ false);
                // Framebuffer rows 148..295 (= panel LEFT half in landscape)
                // are currently 0xFF white → draw a BLACK circle there.
                drawCircle(withCircles, rowBytes, 64, 222, 30, /* black= */ true);

                banner("DISPLAY_PARTIAL_WAIT #3 — add white circle (right) + black circle (left)");
                log.info("OBSERVE (landscape): a clean WHITE circle should appear on the black (right) half");
                log.info("and a clean BLACK circle on the white (left) half. The half/half boundary and the");
                log.info("rest of the panel should remain unchanged. This is partial refresh doing its real job.");
                displayPartialWait(arena, spiFd, cs, dc, rst, busy, withCircles);
                sleepWithProgress(8_000, "observe partial #3 (the showcase)");

                banner("DISPLAY_BASE — cleanup all white (partial→full transition)");
                log.info("OBSERVE: panel should return cleanly to WHITE.");
                displayBase(arena, spiFd, cs, dc, busy, allWhite);
                sleepWithProgress(5_000, "observe cleanup");

                banner("SLEEP");
                sendCommand(arena, spiFd, cs, dc, (byte) 0x10, new byte[]{(byte) 0x01});
                Thread.sleep(100);
                log.info("RawSpiReplicaTest: PASS — completed without exception");
            } finally {
                closeFd(spiFd);
            }
        } catch (Exception e) {
            log.error("RawSpiReplicaTest: FAILED with exception", e);
            throw e;
        } finally {
            pi4j.shutdown();
        }
    }

    // ---- WaveShare-equivalent sequences ----

    private static void initSequence(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                     DigitalOutput rst, DigitalInput busy) throws Throwable {
        log.debug("init: hardware reset 20/2/20 ms");
        rst.state(DigitalState.HIGH); Thread.sleep(20);
        rst.state(DigitalState.LOW);  Thread.sleep(2);
        rst.state(DigitalState.HIGH); Thread.sleep(20);
        Thread.sleep(100);
        waitBusy(busy);
        log.debug("init: SW reset");
        sendCommand(arena, fd, cs, dc, (byte) 0x12, new byte[0]);
        waitBusy(busy);
        log.debug("init: driver output / data entry / window / disp update 1 / cursor");
        sendCommand(arena, fd, cs, dc, (byte) 0x01, new byte[]{(byte) 0x27, (byte) 0x01, (byte) 0x00});
        sendCommand(arena, fd, cs, dc, (byte) 0x11, new byte[]{(byte) 0x03});
        setWindows(arena, fd, cs, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        sendCommand(arena, fd, cs, dc, (byte) 0x21, new byte[]{(byte) 0x00, (byte) 0x80});
        setCursor(arena, fd, cs, dc, 0, 0);
        waitBusy(busy);
    }

    private static void displayBase(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                    DigitalInput busy, byte[] image) throws Throwable {
        log.debug("displayBase: write 0x24 BW RAM");
        sendCommandData(arena, fd, cs, dc, (byte) 0x24, image, 0, image.length);
        log.debug("displayBase: write 0x26 RED RAM");
        sendCommandData(arena, fd, cs, dc, (byte) 0x26, image, 0, image.length);
        log.debug("displayBase: activate (DUC2=0xF7)");
        sendCommand(arena, fd, cs, dc, (byte) 0x22, new byte[]{(byte) 0xF7});
        sendCommand(arena, fd, cs, dc, (byte) 0x20, new byte[0]);
        waitBusy(busy);
    }

    private static void displayPartialWait(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                           DigitalOutput rst, DigitalInput busy, byte[] image) throws Throwable {
        log.debug("partialWait: brief RST (0.2 ms LOW)");
        rst.state(DigitalState.LOW);
        delayMicros(200);
        rst.state(DigitalState.HIGH);

        log.debug("partialWait: load LUT (CMD 0x32 + 159 bytes)");
        sendCommandData(arena, fd, cs, dc, (byte) 0x32, WF_PARTIAL_2IN9_WAIT, 0, 159);
        waitBusy(busy);

        log.debug("partialWait: 0x37 disp option");
        sendCommand(arena, fd, cs, dc, (byte) 0x37,
            new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                       (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00});
        log.debug("partialWait: 0x3C border = 0x80");
        sendCommand(arena, fd, cs, dc, (byte) 0x3C, new byte[]{(byte) 0x80});

        log.debug("partialWait: activate clock+analog (DUC2=0xC0)");
        sendCommand(arena, fd, cs, dc, (byte) 0x22, new byte[]{(byte) 0xC0});
        sendCommand(arena, fd, cs, dc, (byte) 0x20, new byte[0]);
        waitBusy(busy);

        log.debug("partialWait: setWindow + setCursor");
        setWindows(arena, fd, cs, dc, 0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(arena, fd, cs, dc, 0, 0);

        log.debug("partialWait: write 0x24 (2500 + 2236 chunks, matches WaveShare)");
        // Single CS-asserted sequence: cmd 0x24, then data in two chunks (kernel
        // can't fit 4736 in one transfer at default bufsiz=4096, and WaveShare
        // does the same 2500/2236 split).
        cs.state(DigitalState.LOW);
        dc.state(DigitalState.LOW);
        spiWrite(arena, fd, new byte[]{(byte) 0x24}, 0, 1);
        dc.state(DigitalState.HIGH);
        spiWrite(arena, fd, image, 0, 2500);
        spiWrite(arena, fd, image, 2500, 2236);
        cs.state(DigitalState.HIGH);

        log.debug("partialWait: activate partial display (DUC2=0x0F)");
        sendCommand(arena, fd, cs, dc, (byte) 0x22, new byte[]{(byte) 0x0F});
        sendCommand(arena, fd, cs, dc, (byte) 0x20, new byte[0]);
        waitBusy(busy);
    }

    // ---- Command/data helpers (manual CS) ----

    /**
     * Send a single command byte (DC LOW), with optional trailing data bytes (DC HIGH).
     * CS is held LOW for the entire cmd+data sequence — that is the whole point
     * of this test, vs Pi4J FFM SPI's per-call CS toggling.
     */
    private static void sendCommand(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                    byte cmd, byte[] data) throws Throwable {
        cs.state(DigitalState.LOW);
        dc.state(DigitalState.LOW);
        spiWrite(arena, fd, new byte[]{cmd}, 0, 1);
        if (data.length > 0) {
            dc.state(DigitalState.HIGH);
            spiWrite(arena, fd, data, 0, data.length);
        }
        cs.state(DigitalState.HIGH);
    }

    /**
     * Send cmd + bulk data from a byte array offset/length. Used for 0x24/0x26
     * frame writes and the 0x32 LUT load. CS held LOW throughout. Data is
     * chunked at SPI_CHUNK to stay within Linux spidev's bufsiz=4096.
     */
    private static final int SPI_CHUNK = 4096;

    private static void sendCommandData(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                        byte cmd, byte[] data, int offset, int length) throws Throwable {
        cs.state(DigitalState.LOW);
        dc.state(DigitalState.LOW);
        spiWrite(arena, fd, new byte[]{cmd}, 0, 1);
        dc.state(DigitalState.HIGH);
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, SPI_CHUNK);
            spiWrite(arena, fd, data, pos, chunk);
            pos       += chunk;
            remaining -= chunk;
        }
        cs.state(DigitalState.HIGH);
    }

    private static void setWindows(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                   int xStart, int yStart, int xEnd, int yEnd) throws Throwable {
        sendCommand(arena, fd, cs, dc, (byte) 0x44,
            new byte[]{(byte) ((xStart >> 3) & 0xFF), (byte) ((xEnd >> 3) & 0xFF)});
        sendCommand(arena, fd, cs, dc, (byte) 0x45,
            new byte[]{(byte) (yStart & 0xFF), (byte) ((yStart >> 8) & 0xFF),
                       (byte) (yEnd & 0xFF),   (byte) ((yEnd >> 8) & 0xFF)});
    }

    private static void setCursor(Arena arena, int fd, DigitalOutput cs, DigitalOutput dc,
                                  int x, int y) throws Throwable {
        sendCommand(arena, fd, cs, dc, (byte) 0x4E, new byte[]{(byte) ((x >> 3) & 0xFF)});
        sendCommand(arena, fd, cs, dc, (byte) 0x4F,
            new byte[]{(byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF)});
    }

    // ---- Low-level FFM/ioctl helpers ----

    private static int openSpiDevice(Arena arena) throws Throwable {
        MemorySegment pathSegment = arena.allocateFrom(SPI_DEVICE);
        int fd = (int) OPEN_HANDLE.invokeExact(pathSegment, O_RDWR);
        if (fd < 0) {
            throw new IllegalStateException("Failed to open " + SPI_DEVICE + " (open returned " + fd + ")");
        }
        log.debug("opened {} → fd {}", SPI_DEVICE, fd);
        return fd;
    }

    private static void closeFd(int fd) {
        try {
            int result = (int) CLOSE_HANDLE.invokeExact(fd);
            if (result < 0) {
                log.warn("close({}) returned {}", fd, result);
            }
        } catch (Throwable e) {
            log.warn("close failed", e);
        }
    }

    private static void configureSpi(Arena arena, int fd) throws Throwable {
        // Mode: SPI_MODE_0 | SPI_NO_CS — kernel must not drive CE0.
        MemorySegment modeBuf = arena.allocate(ValueLayout.JAVA_BYTE);
        modeBuf.set(ValueLayout.JAVA_BYTE, 0, SPI_MODE);
        int r = (int) IOCTL_HANDLE.invokeExact(fd, SPI_IOC_WR_MODE, modeBuf);
        if (r < 0) throw new IllegalStateException("SPI_IOC_WR_MODE failed: " + r);
        log.debug("SPI mode set to 0x{} (mode 0 + SPI_NO_CS)", String.format("%02X", SPI_MODE & 0xFF));

        // Bits per word.
        MemorySegment bitsBuf = arena.allocate(ValueLayout.JAVA_BYTE);
        bitsBuf.set(ValueLayout.JAVA_BYTE, 0, SPI_BITS_PER_WORD);
        r = (int) IOCTL_HANDLE.invokeExact(fd, SPI_IOC_WR_BITS_PER_WORD, bitsBuf);
        if (r < 0) throw new IllegalStateException("SPI_IOC_WR_BITS_PER_WORD failed: " + r);
        log.debug("SPI bits-per-word set to {}", SPI_BITS_PER_WORD);

        // Max speed.
        MemorySegment speedBuf = arena.allocate(ValueLayout.JAVA_INT);
        speedBuf.set(ValueLayout.JAVA_INT, 0, SPI_SPEED_HZ);
        r = (int) IOCTL_HANDLE.invokeExact(fd, SPI_IOC_WR_MAX_SPEED_HZ, speedBuf);
        if (r < 0) throw new IllegalStateException("SPI_IOC_WR_MAX_SPEED_HZ failed: " + r);
        log.debug("SPI max speed set to {} Hz", SPI_SPEED_HZ);
    }

    /**
     * Write a buffer over SPI via SPI_IOC_MESSAGE(1).
     *
     * SUSPECT #2 PROBE — mirrors Pi4J FFM SPI's per-transfer allocation
     * pattern as closely as we can from outside Pi4J:
     *
     *   - Opens a fresh {@code Arena.ofConfined()} per transfer (closed
     *     at end of method), mirroring Pi4J's `IoctlNative.call` which
     *     does `try (var arena = Arena.ofConfined()) { ... }` per call.
     *   - Calls {@code Arrays.copyOfRange} to produce the tx slice as
     *     a fresh byte[] (Pi4J does this at FFMSpi.transfer line 93).
     *   - Uses {@code allocateFrom(ValueLayout.JAVA_BYTE, byte[])} to
     *     allocate AND copy the tx data into native memory (Pi4J does
     *     this in `SpiTransferBuffer.createMemorySegment`).
     *   - Also keeps the non-NULL rx_buf from the suspect #1 probe.
     *
     * If this version FAILS visually (pale grey partial, black cleanup
     * full), the per-transfer allocation pattern is what triggers the
     * bug — most likely the freshly-created confined arena's memory
     * placement, or some interaction between `allocateFrom` and the
     * subsequent ioctl.
     *
     * If it still SUCCEEDS, the bug is in something we have not yet
     * isolated from outside — and we file the upstream issue with the
     * existing reproducers for Pi4J maintainers to chase from inside
     * their codebase.
     */
    private static void spiWrite(Arena unusedArena, int fd, byte[] data, int offset, int length) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            // Mirror Pi4J FFMSpi.transfer line 93 — Arrays.copyOfRange first.
            byte[] writeData = Arrays.copyOfRange(data, offset, offset + length);

            // Mirror Pi4J's SpiTransferBuffer.createMemorySegment —
            // allocateFrom (allocates a fresh segment AND copies the bytes).
            MemorySegment txBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, writeData);
            // Pi4J always passes a non-NULL rx_buf. Allocate one the same size
            // as the tx (suspect #1 already ruled out — keeping it for
            // accurate mirroring of Pi4J's actual call shape).
            MemorySegment rxBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, new byte[length]);

            MemorySegment xfer = arena.allocate(SPI_IOC_TRANSFER_BYTES);
            xfer.set(ValueLayout.JAVA_LONG,  0,  txBuf.address());      // tx_buf
            xfer.set(ValueLayout.JAVA_LONG,  8,  rxBuf.address());      // rx_buf
            xfer.set(ValueLayout.JAVA_INT,   16, length);               // len
            xfer.set(ValueLayout.JAVA_INT,   20, SPI_SPEED_HZ);         // speed_hz
            xfer.set(ValueLayout.JAVA_SHORT, 24, (short) 0);            // delay_usecs
            xfer.set(ValueLayout.JAVA_BYTE,  26, SPI_BITS_PER_WORD);    // bits_per_word
            xfer.set(ValueLayout.JAVA_BYTE,  27, (byte) 0);             // cs_change
            xfer.set(ValueLayout.JAVA_BYTE,  28, (byte) 0);             // tx_nbits
            xfer.set(ValueLayout.JAVA_BYTE,  29, (byte) 0);             // rx_nbits
            xfer.set(ValueLayout.JAVA_BYTE,  30, (byte) 0);             // word_delay_usecs
            xfer.set(ValueLayout.JAVA_BYTE,  31, (byte) 0);             // pad

            int r = (int) IOCTL_HANDLE.invokeExact(fd, SPI_IOC_MESSAGE_1, xfer);
            if (r < 0) {
                throw new IllegalStateException("SPI_IOC_MESSAGE(1) failed: returned " + r + " for length=" + length);
            }
        }
    }

    // ---- Misc ----

    /**
     * Fill a circle in the framebuffer. Framebuffer bit encoding: 1 = white,
     * 0 = black. When {@code black} is true, pixels inside the circle are
     * cleared (drawn black); otherwise they are set (drawn white).
     */
    private static void drawCircle(byte[] frame, int rowBytes, int cx, int cy, int radius, boolean black) {
        int rSq = radius * radius;
        for (int y = cy - radius; y <= cy + radius; y++) {
            if (y < 0 || y >= HEIGHT) continue;
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || x >= WIDTH) continue;
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= rSq) {
                    int byteIdx = y * rowBytes + (x / 8);
                    int bitIdx = 7 - (x % 8);
                    if (black) {
                        frame[byteIdx] &= (byte) ~(1 << bitIdx);
                    } else {
                        frame[byteIdx] |= (byte) (1 << bitIdx);
                    }
                }
            }
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
