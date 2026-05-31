// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.ssd1680;

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiMode;
import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.RefreshPolicy;
import dev.pux4j.ui.core.RefreshStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Ssd1680DisplayDriver implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(Ssd1680DisplayDriver.class);
    private static final ExecutorService DRIVER_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pux4j-driver-ssd1680-", 0).factory());
    static final int WIDTH        = 122;
    static final int HEIGHT       = 250;
    static final int BYTES_PER_ROW = 16; // ceil(122 / 8) = 16
    static final int FRAME_BYTES   = BYTES_PER_ROW * HEIGHT; // 4000

    // SSD1680 command bytes — Solomon Systech SSD1680 datasheet section 9 (command table)
    // Panel configuration and initialisation commands
    private static final byte CMD_DRIVER_OUTPUT   = 0x01;
    private static final byte CMD_DATA_ENTRY_MODE = 0x11;
    private static final byte CMD_SW_RESET        = 0x12;
    private static final byte CMD_TEMP_SENSOR     = 0x18;
    private static final byte CMD_WRITE_TEMP_REG  = 0x1A;
    private static final byte CMD_BORDER_WAVEFORM = 0x3C;
    // Display update sequence commands
    private static final byte CMD_ACTIVATE        = 0x20; // Master Activation — triggers the panel refresh cycle
    private static final byte CMD_DISP_UPDATE_1   = 0x21;
    private static final byte CMD_DISP_UPDATE_2   = 0x22; // Display Update Control 2 — bitmask selects clock/analog/LUT steps
    // RAM write commands — 0x24 = BW (new frame), 0x26 = RED (previous-frame baseline for partial delta)
    private static final byte CMD_WRITE_BW_RAM    = 0x24;
    private static final byte CMD_WRITE_RED_RAM   = 0x26;
    // Deep sleep — CMD 0x10; data 0x01 retains RAM; hardware reset required to wake
    private static final byte CMD_DEEP_SLEEP      = 0x10;
    // RAM address window and cursor commands
    private static final byte CMD_SET_X_WINDOW    = 0x44;
    private static final byte CMD_SET_Y_WINDOW    = 0x45;
    private static final byte CMD_SET_X_CURSOR    = 0x4E;
    private static final byte CMD_SET_Y_CURSOR    = 0x4F;

    private static final DisplayCapabilities CAPABILITIES = new DisplayCapabilities(
        EnumSet.of(PixelFormat.MONOCHROME),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL),
        true,
        Optional.of(new AlignmentConstraints(8))
    );

    private final Orientation   orientation;
    private final Spi           spi;
    private final DigitalOutput dc;
    private final DigitalOutput rst;
    private final DigitalInput  busy;
    private final byte[] lastFrameBytes = new byte[FRAME_BYTES];

    private int  partialRefreshCount  = 0;
    private int  fastRefreshCount     = 0;
    private long lastFullRefreshTimeMs;
    private RefreshPolicy refreshPolicy = RefreshPolicy.NEVER;

    Ssd1680DisplayDriver(Pux4jContext context, DriverConfig config) {
        Context ctx = context.pi4j();

        orientation = Orientation.valueOf(config.property("orientation", "PORTRAIT"));

        int dcPin   = config.property("dcPin",   25);
        int rstPin  = config.property("rstPin",  17);
        int busyPin = config.property("busyPin", 24);

        spi = ctx.create(Spi.newConfigBuilder(ctx)
            .id("ssd1680-spi")
            .name("SSD1680 SPI")
            .bus(SpiBus.BUS_0)
            .channel(0)
            .mode(SpiMode.MODE_0)
            .baud(10_000_000)
            .build());

        dc = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("ssd1680-dc")
            .name("SSD1680 DC")
            .bcm(dcPin)
            .initial(DigitalState.LOW)
            .shutdown(DigitalState.LOW)
            .build());

        rst = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("ssd1680-rst")
            .name("SSD1680 RST")
            .bcm(rstPin)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.HIGH)
            .build());

        busy = ctx.create(DigitalInput.newConfigBuilder(ctx)
            .id("ssd1680-busy")
            .name("SSD1680 BUSY")
            .bcm(busyPin)
            .pull(PullResistance.PULL_DOWN)
            .build());

        Arrays.fill(lastFrameBytes, (byte) 0xFF);
        lastFullRefreshTimeMs = System.currentTimeMillis();

        int threshold = config.property("partialRefreshThreshold", 20);
        if (threshold > 0) {
            refreshPolicy = RefreshPolicy.afterPartials(threshold);
        }
    }

    @Override public int getWidth()                        { return WIDTH; }
    @Override public int getHeight()                       { return HEIGHT; }
    @Override public Orientation getOrientation()          { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return CAPABILITIES; }

    @Override
    public RefreshStats getRefreshStats() {
        return new RefreshStats(
            partialRefreshCount,
            fastRefreshCount,
            System.currentTimeMillis() - lastFullRefreshTimeMs
        );
    }

    @Override
    public void setRefreshPolicy(RefreshPolicy policy) {
        this.refreshPolicy = policy != null ? policy : RefreshPolicy.NEVER;
    }

    @Override
    public void initialize() {
        configureFullRefreshMode();
        log.info("SSD1680 display initialized ({}×{}, {})", WIDTH, HEIGHT, orientation);
    }

    @Override
    public void reset() {
        log.debug("SSD1680: reset");
        hardwareReset();
    }

    @Override
    public void sleep() {
        // Enter deep sleep mode — SSD1680 CMD 0x10; data 0x01 = retain RAM.
        // Follow-up delay allows the power rails to ramp down before any hardware shutdown.
        sendCommand(CMD_DEEP_SLEEP);
        sendData((byte) 0x01);
        delay(100); // Allow power rail to stabilise after deep sleep command
        log.debug("SSD1680 entered deep sleep");
    }

    @Override
    public void wake() {
        log.debug("SSD1680: wake");
        initialize();
    }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        if (!(frame instanceof MonochromeFrame mf)) {
            throw new IllegalArgumentException("SSD1680 only supports MONOCHROME frames");
        }

        RefreshMode mode = mf.mode();
        if (refreshPolicy.shouldFullRefresh(getRefreshStats(), mode)) {
            log.debug("writeFrame: policy upgraded {} → FULL", mode);
            mode = RefreshMode.FULL;
        }

        return switch (mode) {
            case FULL    -> writeFullFrame(mf.data());
            case FAST    -> writeFastFrame(mf.data());
            case PARTIAL -> writePartialFrame(mf.data());
        };
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
        if (!(frame instanceof MonochromeFrame mf)) {
            throw new UnsupportedOperationException("SSD1680 only supports MONOCHROME frames");
        }

        byte[] data = mf.data();
        if (x < 0 || x + width > WIDTH) {
            throw new IllegalArgumentException("x + width must be <= " + WIDTH + " (x=" + x + ", width=" + width + ")");
        }
        if (y < 0 || y + height > HEIGHT) {
            throw new IllegalArgumentException("y + height must be <= " + HEIGHT + " (y=" + y + ", height=" + height + ")");
        }
        if ((x % 8) != 0) {
            throw new IllegalArgumentException("x must be a multiple of 8 for SSD1680 alignment (x=" + x + ")");
        }

        return CompletableFuture.runAsync(() -> {
            configurePartialRefreshMode();

            waitBusy();
            setWindow(x, y, x + width - 1, y + height - 1);
            setCursor(x, y);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            // Trigger display update sequence — SSD1680 CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xFF);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            partialRefreshCount++;
        }, DRIVER_EXECUTOR);
    }

    private void configureFullRefreshMode() {
        hardwareReset();
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();

        sendCommand(CMD_DRIVER_OUTPUT);
        sendData((byte) 0xF9, (byte) 0x00, (byte) 0x00);

        sendCommand(CMD_DATA_ENTRY_MODE);
        sendData((byte) 0x03);

        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(0, 0);

        sendCommand(CMD_BORDER_WAVEFORM);
        sendData((byte) 0x05);

        sendCommand(CMD_DISP_UPDATE_1);
        sendData((byte) 0x00, (byte) 0x80);

        sendCommand(CMD_TEMP_SENSOR);
        sendData((byte) 0x80);
        waitBusy();

    }

    private void configureFastRefreshMode() {
        hardwareReset();
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();

        sendCommand(CMD_TEMP_SENSOR);
        sendData((byte) 0x80);

        sendCommand(CMD_DATA_ENTRY_MODE);
        sendData((byte) 0x03);

        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(0, 0);

        sendCommand(CMD_DISP_UPDATE_2);
        sendData((byte) 0xB1);
        sendCommand(CMD_ACTIVATE);
        waitBusy();

        sendCommand(CMD_WRITE_TEMP_REG);
        sendData((byte) 0x64, (byte) 0x00);

        sendCommand(CMD_DISP_UPDATE_2);
        sendData((byte) 0x91);
        sendCommand(CMD_ACTIVATE);
        waitBusy();

    }

    private void configurePartialRefreshMode() {
        // A brief RST pulse is required before entering partial mode to reset the scan driver
        // state without triggering a full SW_RESET (which would reload the OTP full-refresh LUT).
        rst.low();
        delay(1); // RST LOW pulse width per SSD1680 hardware reset specification
        rst.high();

        sendCommand(CMD_BORDER_WAVEFORM);
        sendData((byte) 0x80);

        sendCommand(CMD_DRIVER_OUTPUT);
        sendData((byte) 0xF9, (byte) 0x00, (byte) 0x00);

        sendCommand(CMD_DATA_ENTRY_MODE);
        sendData((byte) 0x03);

        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(0, 0);

    }

    private CompletableFuture<Void> writeFullFrame(byte[] data) {
        if (data.length != FRAME_BYTES) {
            throw new IllegalArgumentException("writeFullFrame: expected " + FRAME_BYTES + " bytes, got " + data.length);
        }
        return CompletableFuture.runAsync(() -> {
            configureFullRefreshMode();

            waitBusy();
            setCursor(0, 0);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(data);

            // Trigger display update sequence — SSD1680 CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xF7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            partialRefreshCount = 0;
            fastRefreshCount = 0;
            lastFullRefreshTimeMs = System.currentTimeMillis();
        }, DRIVER_EXECUTOR);
    }

    private CompletableFuture<Void> writeFastFrame(byte[] data) {
        if (data.length != FRAME_BYTES) {
            throw new IllegalArgumentException("writeFastFrame: expected " + FRAME_BYTES + " bytes, got " + data.length);
        }
        return CompletableFuture.runAsync(() -> {
            configureFastRefreshMode();

            waitBusy();

            // Write to both RAMs to establish baseline for subsequent partial refresh
            setCursor(0, 0);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(data);

            // Trigger display update sequence — SSD1680 CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xC7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            fastRefreshCount++;
            partialRefreshCount = 0;
        }, DRIVER_EXECUTOR);
    }

    private CompletableFuture<Void> writePartialFrame(byte[] data) {
        if (data.length != FRAME_BYTES) {
            throw new IllegalArgumentException("writePartialFrame: expected " + FRAME_BYTES + " bytes, got " + data.length);
        }

        return CompletableFuture.runAsync(() -> {
            configurePartialRefreshMode();

            waitBusy();

            // Write previous frame to 0x26 (RED RAM) for delta comparison
            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(lastFrameBytes);

            // Write new frame to 0x24 (BW RAM)
            setCursor(0, 0);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            // Trigger display update sequence — SSD1680 CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xFF);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            partialRefreshCount++;
        }, DRIVER_EXECUTOR);
    }

    private void setWindow(int xStart, int yStart, int xEnd, int yEnd) {
        sendCommand(CMD_SET_X_WINDOW);
        sendData((byte) (xStart >> 3), (byte) (xEnd >> 3));

        sendCommand(CMD_SET_Y_WINDOW);
        sendData(
            (byte) (yStart & 0xFF),
            (byte) ((yStart >> 8) & 0xFF),
            (byte) (yEnd & 0xFF),
            (byte) ((yEnd >> 8) & 0xFF)
        );
    }

    private void setCursor(int x, int y) {
        sendCommand(CMD_SET_X_CURSOR);
        sendData((byte) (x >> 3));

        sendCommand(CMD_SET_Y_CURSOR);
        sendData((byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF));
    }

    private void hardwareReset() {
        // Hardware reset sequence per SSD1680 datasheet; RST is active-LOW.
        rst.high();
        delay(20); // RST HIGH settling time per SSD1680 hardware reset specification
        rst.low();
        delay(2);  // RST LOW pulse width per SSD1680 hardware reset specification
        rst.high();
        delay(20); // RST HIGH settling time — allow IC to complete internal power-on reset
    }

    private void waitBusy() {
        // Per SSD1680 spec: BUSY HIGH = IC busy (updating), BUSY LOW = ready for commands.
        // Polls at 10 ms intervals; requires 3 consecutive LOW readings for debounce stability.
        int stableCount = 0;
        while (stableCount < 3) {
            if (busy.isLow()) {
                stableCount++;
            } else {
                stableCount = 0;
            }
            delay(10); // Polling interval — 10 ms between BUSY pin samples
        }
    }

    private void sendCommand(byte cmd) {
        dc.low();
        spi.write(cmd);
    }

    private void sendData(byte... data) {
        assert data != null : "data must not be null";
        assert data.length > 0 : "data must not be empty";

        dc.high();
        for (int pos = 0; pos < data.length; pos += 4096) {
            int chunk = Math.min(4096, data.length - pos);
            byte[] buf = Arrays.copyOfRange(data, pos, pos + chunk);
            spi.transfer(buf);
        }
    }

    private void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
