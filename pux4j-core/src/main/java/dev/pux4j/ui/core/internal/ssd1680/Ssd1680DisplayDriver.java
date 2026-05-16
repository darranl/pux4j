// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.ssd1680;

import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiMode;
import dev.pux4j.ui.core.AlignmentConstraints;
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

final class Ssd1680DisplayDriver implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(Ssd1680DisplayDriver.class);
    static final int WIDTH        = 122;
    static final int HEIGHT       = 250;
    static final int BYTES_PER_ROW = 16; // ceil(122 / 8) = 16
    static final int FRAME_BYTES   = BYTES_PER_ROW * HEIGHT; // 4000

    // SSD1680 command bytes
    private static final byte CMD_DRIVER_OUTPUT   = 0x01;
    private static final byte CMD_DEEP_SLEEP      = 0x10;
    private static final byte CMD_DATA_ENTRY_MODE = 0x11;
    private static final byte CMD_SW_RESET        = 0x12;
    private static final byte CMD_TEMP_SENSOR     = 0x18;
    private static final byte CMD_WRITE_TEMP_REG  = 0x1A;
    private static final byte CMD_ACTIVATE        = 0x20;
    private static final byte CMD_DISP_UPDATE_1   = 0x21;
    private static final byte CMD_DISP_UPDATE_2   = 0x22;
    private static final byte CMD_WRITE_BW_RAM    = 0x24;
    private static final byte CMD_WRITE_RED_RAM   = 0x26;
    private static final byte CMD_BORDER_WAVEFORM = 0x3C;
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
    private boolean partialModeConfigured;
    private final byte[] lastFrameBytes = new byte[FRAME_BYTES];

    private int  partialRefreshCount  = 0;
    private int  fastRefreshCount     = 0;
    private long lastFullRefreshTimeMs;
    private RefreshPolicy refreshPolicy = RefreshPolicy.NEVER;

    Ssd1680DisplayDriver(DriverConfig config) {
        var ctx  = config.pi4j();
        var json = config.config();

        orientation = Orientation.valueOf(json.getString("orientation", "PORTRAIT"));

        int dcPin   = json.getInt("dcPin",   25);
        int rstPin  = json.getInt("rstPin",  17);
        int busyPin = json.getInt("busyPin", 24);

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

        int threshold = json.getInt("partialRefreshThreshold", 20);
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
        sendCommand(CMD_DEEP_SLEEP);
        sendData((byte) 0x01);
        delay(100);
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
        assert x >= 0 && x + width <= WIDTH : "x + width must be <= " + WIDTH;
        assert y >= 0 && y + height <= HEIGHT : "y + height must be <= " + HEIGHT;
        assert (x % 8) == 0 : "x must be a multiple of 8 for SSD1680 alignment";

        return CompletableFuture.runAsync(() -> {
            if (!partialModeConfigured) {
                configurePartialRefreshMode();
            }

            waitBusy();
            setWindow(x, y, x + width - 1, y + height - 1);
            setCursor(x, y);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xFF);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            partialRefreshCount++;
        });
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

        partialModeConfigured = false;
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

        partialModeConfigured = false;
    }

    private void configurePartialRefreshMode() {
        rst.low();
        delay(1);
        rst.high();

        sendCommand(CMD_BORDER_WAVEFORM);
        sendData((byte) 0x80);

        sendCommand(CMD_DRIVER_OUTPUT);
        sendData((byte) 0xF9, (byte) 0x00, (byte) 0x00);

        sendCommand(CMD_DATA_ENTRY_MODE);
        sendData((byte) 0x03);

        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(0, 0);

        partialModeConfigured = true;
    }

    private CompletableFuture<Void> writeFullFrame(byte[] data) {
        assert data.length == FRAME_BYTES : "Frame must be " + FRAME_BYTES + " bytes, was " + data.length;

        return CompletableFuture.runAsync(() -> {
            configureFullRefreshMode();

            waitBusy();
            setCursor(0, 0);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(data);

            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xF7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            partialRefreshCount = 0;
            fastRefreshCount = 0;
            lastFullRefreshTimeMs = System.currentTimeMillis();
        });
    }

    private CompletableFuture<Void> writeFastFrame(byte[] data) {
        assert data.length == FRAME_BYTES : "Frame must be " + FRAME_BYTES + " bytes, was " + data.length;

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

            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xC7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            fastRefreshCount++;
            partialRefreshCount = 0;
        });
    }

    private CompletableFuture<Void> writePartialFrame(byte[] data) {
        assert data.length == FRAME_BYTES : "Frame must be " + FRAME_BYTES + " bytes, was " + data.length;

        return CompletableFuture.runAsync(() -> {
            if (!partialModeConfigured) {
                configurePartialRefreshMode();
            }

            waitBusy();

            // Write previous frame to 0x26 (RED RAM) for delta comparison
            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(lastFrameBytes);
            
            // Write new frame to 0x24 (BW RAM)
            setCursor(0, 0);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);

            sendCommand(CMD_DISP_UPDATE_2);
            sendData((byte) 0xFF);
            sendCommand(CMD_ACTIVATE);
            waitBusy();

            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            partialRefreshCount++;
        });
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
        rst.high();
        delay(20);
        rst.low();
        delay(2);
        rst.high();
        delay(20);
    }

    private void waitBusy() {
        int stableCount = 0;
        while (stableCount < 3) {
            if (busy.isLow()) {
                stableCount++;
            } else {
                stableCount = 0;
            }
            delay(10);
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
