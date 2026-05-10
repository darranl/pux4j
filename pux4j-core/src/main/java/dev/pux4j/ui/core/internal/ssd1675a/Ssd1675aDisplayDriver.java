// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.ssd1675a;

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
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class Ssd1675aDisplayDriver implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(Ssd1675aDisplayDriver.class);
    private static final Executor VTHREAD = task -> Thread.ofVirtual().start(task);

    static final int WIDTH        = 128;
    static final int HEIGHT       = 296;
    static final int BYTES_PER_ROW = WIDTH / 8;
    static final int FRAME_BYTES   = BYTES_PER_ROW * HEIGHT; // 4736

    // SSD1675A command bytes
    private static final byte CMD_DRIVER_OUTPUT   = 0x01;
    private static final byte CMD_GATE_VOLTAGE    = 0x03;
    private static final byte CMD_SOURCE_VOLTAGE  = 0x04;
    private static final byte CMD_DEEP_SLEEP      = 0x10;
    private static final byte CMD_DATA_ENTRY_MODE = 0x11;
    private static final byte CMD_SW_RESET        = 0x12;
    private static final byte CMD_ACTIVATE        = 0x20;
    private static final byte CMD_DISP_UPDATE_1   = 0x21;
    private static final byte CMD_DISP_UPDATE_2   = 0x22;
    private static final byte CMD_WRITE_BW_RAM    = 0x24;
    private static final byte CMD_WRITE_RED_RAM   = 0x26;
    private static final byte CMD_VCOM            = 0x2C;
    private static final byte CMD_WRITE_LUT       = 0x32;
    private static final byte CMD_WRITE_DISP_OPT  = 0x37;
    private static final byte CMD_BORDER_WAVEFORM = 0x3C;
    private static final byte CMD_EOPT            = 0x3F;
    private static final byte CMD_SET_X_WINDOW    = 0x44;
    private static final byte CMD_SET_Y_WINDOW    = 0x45;
    private static final byte CMD_SET_X_CURSOR    = 0x4E;
    private static final byte CMD_SET_Y_CURSOR    = 0x4F;
    private static final byte CMD_ANALOG_BLOCK    = 0x74;
    private static final byte CMD_DIGITAL_BLOCK   = 0x7E;

    // Partial refresh LUT — WF_PARTIAL_2IN9 with wait flag (byte 64 = 0x02)
    private static final byte[] WF_PARTIAL_2IN9_WAIT = {
        0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        (byte)0x80, (byte)0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x40, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, (byte)0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        // Group timing (12 groups × 7 bytes = 84 bytes); byte[64] = 0x02 (wait flag)
        0x0A, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00,  // Group 0
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 1
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 2
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 3
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 4
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 5
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 6
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 8
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 9
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 10
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 11
        // FR, XON (9 bytes)
        0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x00, 0x00, 0x00,
        // Voltage bytes (6): EOPT VGH VSH1 VSH2 VSL VCOM
        0x22, 0x17, 0x41, (byte)0xB0, 0x32, 0x36
    };

    // 4-gray LUT — 159 bytes; [153..158] are EOPT,VGH,VSH1,VSH2,VSL,VCOM
    private static final byte[] GRAY4_LUT = {
        0x00, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x20, 0x60, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x28, 0x60, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x2A, 0x60, 0x15, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, (byte)0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        // Group timing
        0x00, 0x02, 0x00, 0x05, 0x14, 0x00, 0x00,  // Group 0
        0x1E, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x01,  // Group 1
        0x00, 0x02, 0x00, 0x05, 0x14, 0x00, 0x00,  // Group 2
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 3
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 4
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 5
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 6
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 8
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 9
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 10
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // Group 11
        // FR, XON (9 bytes)
        0x24, 0x22, 0x22, 0x22, 0x23, 0x32, 0x00, 0x00, 0x00,
        // Voltage bytes [153..158]: EOPT VGH VSH1 VSH2 VSL VCOM
        0x22, 0x17, 0x41, (byte)0xAE, 0x32, 0x28
    };

    private static final DisplayCapabilities CAPABILITIES = new DisplayCapabilities(
        EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
        EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
        true,
        Optional.of(new AlignmentConstraints(8))
    );

    private final Orientation   orientation;
    private final Spi           spi;
    private final DigitalOutput dc;
    private final DigitalOutput rst;
    private final DigitalInput  busy;

    Ssd1675aDisplayDriver(DriverConfig config) {
        var ctx  = config.pi4j();
        var json = config.config();

        orientation = Orientation.valueOf(json.getString("orientation", "PORTRAIT"));

        int dcPin   = json.getInt("dcPin",   25);
        int rstPin  = json.getInt("rstPin",  17);
        int busyPin = json.getInt("busyPin", 24);

        spi = ctx.create(Spi.newConfigBuilder(ctx)
            .id("ssd1675a-spi")
            .name("SSD1675A SPI")
            .bus(SpiBus.BUS_0)
            .channel(0)
            .mode(SpiMode.MODE_0)
            .baud(10_000_000)
            .build());

        dc = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("ssd1675a-dc")
            .name("SSD1675A DC")
            .bcm(dcPin)
            .initial(DigitalState.LOW)
            .shutdown(DigitalState.LOW)
            .build());

        rst = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("ssd1675a-rst")
            .name("SSD1675A RST")
            .bcm(rstPin)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.HIGH)
            .build());

        busy = ctx.create(DigitalInput.newConfigBuilder(ctx)
            .id("ssd1675a-busy")
            .name("SSD1675A BUSY")
            .bcm(busyPin)
            .pull(PullResistance.PULL_DOWN)
            .build());
    }

    @Override public int getWidth()                        { return WIDTH; }
    @Override public int getHeight()                       { return HEIGHT; }
    @Override public Orientation getOrientation()          { return orientation; }
    @Override public DisplayCapabilities getCapabilities() { return CAPABILITIES; }

    @Override
    public void initialize() {
        log.debug("SSD1675A: initialize");
        hardwareReset();
        delay(100);
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();

        sendCommand(CMD_DRIVER_OUTPUT,   (byte)0x27, (byte)0x01, (byte)0x00);
        sendCommand(CMD_DATA_ENTRY_MODE, (byte)0x03);
        setFullWindow();
        sendCommand(CMD_DISP_UPDATE_1,   (byte)0x00, (byte)0x80);
        waitBusy();
        log.debug("SSD1675A: initialized");
    }

    @Override
    public void reset() {
        log.debug("SSD1675A: reset");
        hardwareReset();
    }

    @Override
    public void sleep() {
        log.debug("SSD1675A: sleep");
        sendCommand(CMD_DEEP_SLEEP, (byte)0x01);
        delay(100);
    }

    @Override
    public void wake() {
        log.debug("SSD1675A: wake");
        initialize();
    }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        if (frame instanceof MonochromeFrame mf) {
            return switch (mf.mode()) {
                case FULL    -> writeFullFrame(mf.data());
                case PARTIAL -> writePartialFrame(mf.data());
                case FAST    -> throw new UnsupportedOperationException(
                    "SSD1675A does not support FAST refresh");
            };
        }
        if (frame instanceof FourGrayFrame fg) {
            return writeFourGrayFrame(fg);
        }
        throw new UnsupportedOperationException("Unsupported frame type: " + frame.getClass());
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
        if (frame instanceof FourGrayFrame) {
            throw new UnsupportedOperationException("FourGrayFrame does not support writeRegion");
        }
        if (!(frame instanceof MonochromeFrame mf)) {
            throw new UnsupportedOperationException("Unsupported frame type: " + frame.getClass());
        }
        log.debug("SSD1675A: writeRegion ({},{}) {}x{}", x, y, width, height);
        partialInit();
        setWindow(x, y, x + width - 1, y + height - 1);
        setCursor(x, y);
        sendCommand(CMD_WRITE_BW_RAM);
        sendData(mf.data());
        sendCommand(CMD_DISP_UPDATE_2, (byte)0x0F);
        sendCommand(CMD_ACTIVATE);
        return CompletableFuture.runAsync(this::waitBusy, VTHREAD);
    }

    // --- Frame write helpers ---

    private CompletableFuture<Void> writeFullFrame(byte[] data) {
        log.debug("SSD1675A: full-refresh frame ({} bytes)", data.length);
        setFullWindow();
        sendCommand(CMD_WRITE_BW_RAM);
        sendData(data);
        sendCommand(CMD_DISP_UPDATE_2, (byte)0xF7);
        sendCommand(CMD_ACTIVATE);
        return CompletableFuture.runAsync(this::waitBusy, VTHREAD);
    }

    private CompletableFuture<Void> writePartialFrame(byte[] data) {
        log.debug("SSD1675A: partial-refresh frame ({} bytes)", data.length);
        partialInit();
        setFullWindow();
        sendCommand(CMD_WRITE_BW_RAM);
        sendData(data);
        sendCommand(CMD_DISP_UPDATE_2, (byte)0x0F);
        sendCommand(CMD_ACTIVATE);
        return CompletableFuture.runAsync(this::waitBusy, VTHREAD);
    }

    private CompletableFuture<Void> writeFourGrayFrame(FourGrayFrame frame) {
        log.debug("SSD1675A: 4-gray frame");
        fourGrayInit();
        sendCommand(CMD_WRITE_BW_RAM);
        sendData(frame.bwPlane());
        setCursor(0, 0);
        sendCommand(CMD_WRITE_RED_RAM);
        sendData(frame.redPlane());
        sendCommand(CMD_DISP_UPDATE_2, (byte)0xC7);
        sendCommand(CMD_ACTIVATE);
        return CompletableFuture.runAsync(this::waitBusy, VTHREAD);
    }

    // --- Init sequences ---

    private void partialInit() {
        log.debug("SSD1675A: partialInit");
        rst.state(DigitalState.LOW);
        delay(1);
        rst.state(DigitalState.HIGH);

        sendCommand(CMD_WRITE_LUT);
        sendData(WF_PARTIAL_2IN9_WAIT);
        waitBusy();

        sendCommand(CMD_WRITE_DISP_OPT,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00);
        sendCommand(CMD_BORDER_WAVEFORM, (byte)0x80);
        sendCommand(CMD_DISP_UPDATE_2,   (byte)0xC0);
        sendCommand(CMD_ACTIVATE);
        waitBusy();
    }

    private void fourGrayInit() {
        log.debug("SSD1675A: fourGrayInit");
        hardwareReset();
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();

        sendCommand(CMD_ANALOG_BLOCK,    (byte)0x54);
        sendCommand(CMD_DIGITAL_BLOCK,   (byte)0x3B);
        sendCommand(CMD_DRIVER_OUTPUT,   (byte)0x27, (byte)0x01, (byte)0x00);
        sendCommand(CMD_DATA_ENTRY_MODE, (byte)0x03);
        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        sendCommand(CMD_BORDER_WAVEFORM, (byte)0x00);
        sendCommand(CMD_DISP_UPDATE_1,   (byte)0x00, (byte)0x80);
        setCursor(0, 0);
        waitBusy();

        sendCommand(CMD_WRITE_LUT);
        sendData(GRAY4_LUT, 0, 153);
        waitBusy();
        sendCommand(CMD_EOPT,           GRAY4_LUT[153]);
        sendCommand(CMD_GATE_VOLTAGE,   GRAY4_LUT[154]);
        sendCommand(CMD_SOURCE_VOLTAGE, GRAY4_LUT[155], GRAY4_LUT[156], GRAY4_LUT[157]);
        sendCommand(CMD_VCOM,           GRAY4_LUT[158]);
    }

    // --- SPI and GPIO helpers ---

    private void hardwareReset() {
        rst.state(DigitalState.HIGH);
        delay(20);
        rst.state(DigitalState.LOW);
        delay(2);
        rst.state(DigitalState.HIGH);
        delay(20);
    }

    private void waitBusy() {
        while (busy.isHigh()) {
            delay(10);
        }
    }

    private void setFullWindow() {
        setWindow(0, 0, WIDTH - 1, HEIGHT - 1);
        setCursor(0, 0);
    }

    private void setWindow(int xStart, int yStart, int xEnd, int yEnd) {
        // X register takes byte-unit indices (pixel / 8)
        sendCommand(CMD_SET_X_WINDOW, (byte)(xStart / 8), (byte)(xEnd / 8));
        // Y register takes row indices in 16-bit LE
        sendCommand(CMD_SET_Y_WINDOW,
            (byte)(yStart & 0xFF), (byte)(yStart >> 8),
            (byte)(yEnd   & 0xFF), (byte)(yEnd   >> 8));
    }

    private void setCursor(int x, int y) {
        sendCommand(CMD_SET_X_CURSOR, (byte)(x / 8));
        sendCommand(CMD_SET_Y_CURSOR, (byte)(y & 0xFF), (byte)(y >> 8));
    }

    private void sendCommand(byte cmd, byte... data) {
        dc.state(DigitalState.LOW);
        spi.transfer(new byte[]{cmd});
        if (data.length > 0) {
            dc.state(DigitalState.HIGH);
            spi.transfer(data);
        }
    }

    private void sendData(byte[] data) {
        dc.state(DigitalState.HIGH);
        spi.transfer(data);
    }

    private void sendData(byte[] data, int offset, int length) {
        byte[] slice = new byte[length];
        System.arraycopy(data, offset, slice, 0, length);
        dc.state(DigitalState.HIGH);
        spi.transfer(slice);
    }

    private static void delay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
