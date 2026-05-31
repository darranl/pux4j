// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

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
import dev.pux4j.ui.core.FourGrayFrame;
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

final class Ssd1675aDisplayDriver implements EInkDisplayDriver {

    private static final Logger log = LoggerFactory.getLogger(Ssd1675aDisplayDriver.class);
    private static final ExecutorService DRIVER_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pux4j-driver-ssd1675a-", 0).factory());
    static final int WIDTH        = 128;
    static final int HEIGHT       = 296;
    static final int BYTES_PER_ROW = WIDTH / 8;
    static final int FRAME_BYTES   = BYTES_PER_ROW * HEIGHT; // 4736

    // SSD1675A command bytes — Solomon Systech SSD1675A datasheet section 9 (command table)
    // Panel configuration and initialisation commands
    private static final byte CMD_DRIVER_OUTPUT   = 0x01;
    private static final byte CMD_DATA_ENTRY_MODE = 0x11;
    private static final byte CMD_SW_RESET        = 0x12;
    private static final byte CMD_BORDER_WAVEFORM = 0x3C;
    // Voltage configuration commands — used when loading custom LUTs (see loadFastLut / loadGray4Lut)
    private static final byte CMD_GATE_VOLTAGE    = 0x03;
    private static final byte CMD_SOURCE_VOLTAGE  = 0x04;
    private static final byte CMD_VCOM            = 0x2C;
    private static final byte CMD_EOPT            = 0x3F;
    // Display update sequence commands
    private static final byte CMD_ACTIVATE        = 0x20; // Master Activation — triggers the panel refresh cycle
    private static final byte CMD_DISP_UPDATE_1   = 0x21;
    private static final byte CMD_DISP_UPDATE_2   = 0x22; // Display Update Control 2 — bitmask selects clock/analog/LUT steps
    // RAM write commands — 0x24 = BW (new frame), 0x26 = RED (previous-frame baseline for partial delta)
    private static final byte CMD_WRITE_BW_RAM    = 0x24;
    private static final byte CMD_WRITE_RED_RAM   = 0x26;
    // LUT and display option commands
    private static final byte CMD_WRITE_LUT       = 0x32; // Write custom waveform LUT — used for fast/partial/gray4 refresh modes
    private static final byte CMD_WRITE_DISP_OPT  = 0x37;
    // Deep sleep — CMD 0x10; data 0x01 retains RAM; hardware reset required to wake
    private static final byte CMD_DEEP_SLEEP      = 0x10;
    // RAM address window and cursor commands
    private static final byte CMD_SET_X_WINDOW    = 0x44;
    private static final byte CMD_SET_Y_WINDOW    = 0x45;
    private static final byte CMD_SET_X_CURSOR    = 0x4E;
    private static final byte CMD_SET_Y_CURSOR    = 0x4F;
    // Analog/digital block control — used in 4-gray init sequence per SSD1675A datasheet
    private static final byte CMD_ANALOG_BLOCK    = 0x74;
    private static final byte CMD_DIGITAL_BLOCK   = 0x7E;

    // Partial refresh LUT — WF_PARTIAL_2IN9 with RP[0]=2 (Group 0 runs 3×, keeps BUSY HIGH)
    private static final byte[] WF_PARTIAL_2IN9_WAIT = {
        0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        (byte)0x80, (byte)0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x40, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, (byte)0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        // Group timing (12 groups × 7 bytes = 84 bytes): TP[A],TP[B],TP[C],TP[D],SR,ENT,RP
        0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,  // Group 0: TP[A]=0x0A, RP=2 (run 3×)
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

    // Fast full-refresh LUT — WF_FULL from WaveShare EPD_2in9_V2.c.
    // 159 bytes: first 153 are the waveform payload loaded via CMD 0x32; the
    // remaining 6 are voltage parameters applied via separate voltage commands
    // (see loadFastLut()). This LUT is designed for a quick full refresh with
    // no visible flash — it clears pixel voltage drift without the slow alternating
    // waveform used by the OTP default full LUT. WaveShare's Init_Fast() uses it.
    private static final byte[] WF_FULL = {
        (byte)0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L0 1.00S
        0x60,       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L1
        (byte)0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L2
        0x60,       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L3
        0x00,       0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L4
        // Group timing (12 groups × 7 bytes): TP[A],TP[B],TP[C],TP[D],SR,ENT,RP
        0x19, 0x19, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 0
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 1
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 2
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 3
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 4
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 5
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 6
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 8
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 9
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 10
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 11
        // FR, XON (9 bytes)
        0x24, 0x42, 0x22, 0x22, 0x23, 0x32, 0x00, 0x00, 0x00,
        // Voltage bytes [153..158]: EOPT VGH VSH1 VSH2 VSL VCOM
        0x22, 0x17, 0x41, (byte)0xAE, 0x32, 0x38
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
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL),
        true,
        Optional.of(new AlignmentConstraints(8))
    );

    static {
        // Fail fast at class-load time if any LUT array was accidentally
        // truncated or padded. Each must be exactly 159 bytes (153 waveform +
        // 6 voltage bytes split by loadFastLut / loadGray4Lut).
        assert WF_PARTIAL_2IN9_WAIT.length == 159
            : "WF_PARTIAL_2IN9_WAIT must be 159 bytes, was " + WF_PARTIAL_2IN9_WAIT.length;
        assert WF_FULL.length == 159
            : "WF_FULL must be 159 bytes, was " + WF_FULL.length;
        assert GRAY4_LUT.length == 159
            : "GRAY4_LUT must be 159 bytes, was " + GRAY4_LUT.length;
    }

    private final Orientation   orientation;
    private final Spi           spi;
    private final DigitalOutput dc;
    private final DigitalOutput rst;
    private final DigitalInput  busy;
    private boolean fullModeConfigured;
    // Cached copy of the last frame written to 0x24. Used to explicitly re-prime
    // 0x26 (previous-frame RAM) before each partial refresh so the IC computes
    // the delta against what is actually displayed. The IC does not maintain
    // 0x26 reliably across our full↔partial mode transitions.
    private final byte[] lastFrameBytes = new byte[FRAME_BYTES];

    // Refresh counters — reset to zero after every FULL refresh.
    private int  partialRefreshCount  = 0;
    private int  fastRefreshCount     = 0;
    private long lastFullRefreshTimeMs;

    // Policy evaluated before each writeFrame(); default never upgrades.
    private RefreshPolicy refreshPolicy = RefreshPolicy.NEVER;

    Ssd1675aDisplayDriver(Pux4jContext context, DriverConfig config) {
        Context ctx = context.pi4j();

        orientation = Orientation.valueOf(config.property("orientation", "PORTRAIT"));

        int dcPin   = config.property("dcPin",   25);
        int rstPin  = config.property("rstPin",  17);
        int busyPin = config.property("busyPin", 24);

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

        // Display powers on with all pixels white; mirror that in the baseline cache.
        Arrays.fill(lastFrameBytes, (byte) 0xFF);
        lastFullRefreshTimeMs = System.currentTimeMillis();

        // Apply threshold policy from config if present.
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
        this.refreshPolicy = (policy != null) ? policy : RefreshPolicy.NEVER;
    }

    @Override
    public void initialize() {
        log.debug("SSD1675A: initialize");
        configureFullRefreshMode();
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
        // Enter deep sleep mode — SSD1675A CMD 0x10; data 0x01 = retain RAM.
        // Follow-up delay allows the power rails to ramp down before any hardware shutdown.
        sendCommand(CMD_DEEP_SLEEP, (byte)0x01);
        delay(100); // Allow power rail to stabilise after deep sleep command
    }

    @Override
    public void wake() {
        log.debug("SSD1675A: wake");
        initialize();
    }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        if (frame instanceof MonochromeFrame mf) {
            RefreshMode mode = mf.mode();
            // Give the policy a chance to upgrade a PARTIAL or FAST to a FULL.
            // The policy does not choose which FULL variant — the driver does.
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
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                "writeRegion: invalid region (x=" + x + ",y=" + y + ",w=" + width + ",h=" + height + ")");
        }
        if (x + width > WIDTH) {
            throw new IllegalArgumentException(
                "writeRegion: x+width (" + (x + width) + ") exceeds WIDTH (" + WIDTH + ")");
        }
        if (y + height > HEIGHT) {
            throw new IllegalArgumentException(
                "writeRegion: y+height (" + (y + height) + ") exceeds HEIGHT (" + HEIGHT + ")");
        }
        byte[] regionData = mf.data();
        log.debug("SSD1675A: writeRegion ({},{}) {}x{}", x, y, width, height);
        return CompletableFuture.runAsync(() -> {
            waitBusy();
            partialInit();
            setWindow(x, y, x + width - 1, y + height - 1);
            setCursor(x, y);
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(regionData);
            // Trigger display update sequence — SSD1675A CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2, (byte)0x0F);
            sendCommand(CMD_ACTIVATE);
            waitBusy();
        }, DRIVER_EXECUTOR);
    }

    // --- Frame write helpers ---

    private CompletableFuture<Void> writeFullFrame(byte[] data) {
        if (data == null || data.length != FRAME_BYTES) {
            throw new IllegalArgumentException(
                "writeFullFrame: expected " + FRAME_BYTES + " bytes, got " + (data == null ? "null" : data.length));
        }
        return CompletableFuture.runAsync(() -> {
            log.debug("writeFullFrame: begin ({} bytes, fullModeConfigured={})", data.length, fullModeConfigured);
            waitBusy();
            if (!fullModeConfigured) {
                // Transitioning from PARTIAL mode back to FULL. We deliberately do
                // NOT call configureFullRefreshMode() here — partialInit already
                // re-applied the registers we need (DRIVER_OUTPUT, DATA_ENTRY_MODE,
                // DISP_UPDATE_1, window/cursor), and the 0xF7 activate below has
                // bit 4 set ("Load LUT with display mode 1") which reloads the
                // OTP default full-refresh LUT. Adding a hardware reset on this
                // transition leaves the IC in a state where the subsequent full
                // refresh produces inverted output — observed in round 16.
                // WaveShare's reference Display_Base() does exactly this: no reset,
                // just write 0x24/0x26 + activate 0xF7.
                log.debug("writeFullFrame: transitioning from PARTIAL → FULL without reset");
                fullModeConfigured = true;
            }
            setFullWindow();
            log.debug("writeFullFrame: writing 0x24 BW RAM");
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);
            setCursor(0, 0);
            log.debug("writeFullFrame: writing 0x26 RED RAM (baseline for partial)");
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(data);
            // Trigger display update sequence — SSD1675A CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            log.debug("writeFullFrame: activate full update (DUC2=0xF7)");
            sendCommand(CMD_DISP_UPDATE_2, (byte)0xF7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();
            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            resetRefreshCounters();
            log.debug("writeFullFrame: complete");
        }, DRIVER_EXECUTOR);
    }

    private CompletableFuture<Void> writeFastFrame(byte[] data) {
        return CompletableFuture.runAsync(() -> {
            log.debug("writeFastFrame: begin ({} bytes)", data.length);
            waitBusy();
            fastInit();
            setFullWindow();
            log.debug("writeFastFrame: writing 0x24 BW RAM");
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);
            setCursor(0, 0);
            log.debug("writeFastFrame: writing 0x26 RED RAM (baseline for partial)");
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(data);
            // 0xC7 = enable clock + enable analog + display mode 1 + display mode 2 + disable analog + disable clock.
            // Bit 4 (Load LUT from OTP) is deliberately NOT set — this preserves the custom WF_FULL LUT
            // loaded by fastInit()/loadFastLut() via CMD 0x32, which is what makes this refresh FAST.
            // Using 0xF7 (slow-FULL activation) here would reload the OTP LUT, discarding the custom LUT
            // and producing a slow full refresh identical to writeFullFrame(). Matches WaveShare's
            // EPD_2IN9_V2_TurnOnDisplay_Fast() which also uses 0xC7.
            // Trigger display update sequence — SSD1675A CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            log.debug("writeFastFrame: activate fast update (DUC2=0xC7)");
            sendCommand(CMD_DISP_UPDATE_2, (byte)0xC7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();
            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            resetRefreshCounters();
            log.debug("writeFastFrame: complete");
        }, DRIVER_EXECUTOR);
    }

    private CompletableFuture<Void> writePartialFrame(byte[] data) {
        if (data == null || data.length != FRAME_BYTES) {
            throw new IllegalArgumentException(
                "writePartialFrame: expected " + FRAME_BYTES + " bytes, got " + (data == null ? "null" : data.length));
        }
        return CompletableFuture.runAsync(() -> {
            log.debug("writePartialFrame: begin ({} bytes)", data.length);
            waitBusy();
            partialInit();
            setFullWindow();
            log.debug("writePartialFrame: writing 0x24 BW RAM with new frame");
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(data);
            // Trigger display update sequence — SSD1675A CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            log.debug("writePartialFrame: activate partial display (DUC2=0x0F)");
            sendCommand(CMD_DISP_UPDATE_2, (byte)0x0F);
            sendCommand(CMD_ACTIVATE);
            waitBusy();
            System.arraycopy(data, 0, lastFrameBytes, 0, FRAME_BYTES);
            partialRefreshCount++;
            log.debug("writePartialFrame: complete (partialCount={})", partialRefreshCount);
        }, DRIVER_EXECUTOR);
    }

    private CompletableFuture<Void> writeFourGrayFrame(FourGrayFrame frame) {
        return CompletableFuture.runAsync(() -> {
            log.debug("SSD1675A: 4-gray frame");
            waitBusy();
            fourGrayInit();
            sendCommand(CMD_WRITE_BW_RAM);
            sendData(frame.bwPlane());
            setCursor(0, 0);
            sendCommand(CMD_WRITE_RED_RAM);
            sendData(frame.redPlane());
            // Trigger display update sequence — SSD1675A CMD 0x20 (Master Activation); waits for BUSY to clear after refresh
            sendCommand(CMD_DISP_UPDATE_2, (byte)0xC7);
            sendCommand(CMD_ACTIVATE);
            waitBusy();
        }, DRIVER_EXECUTOR);
    }

    // --- Init sequences ---

    private void partialInit() {
        log.debug("partialInit: begin");
        fullModeConfigured = false;
        // Reverted to round-10 state after round 12 (brief RST, no register
        // re-init) regressed: the BUSY-hang crash returned on the 2nd partial
        // with no visibility improvement. The register re-init below is
        // load-bearing for stability even though it does not fix visibility.
        log.debug("partialInit: RST pulse (2 ms LOW + 20 ms HIGH + waitBusy)");
        rst.state(DigitalState.LOW);
        delay(2);  // RST LOW pulse width per SSD1675A hardware reset specification
        rst.state(DigitalState.HIGH);
        delay(20); // RST HIGH settling time — required before re-applying register configuration for partial mode
        waitBusy();
        log.debug("partialInit: RST complete, re-applying register configuration");
        sendCommand(CMD_DRIVER_OUTPUT,   (byte)0x27, (byte)0x01, (byte)0x00);
        sendCommand(CMD_DATA_ENTRY_MODE, (byte)0x03);
        setFullWindow();
        sendCommand(CMD_DISP_UPDATE_1,   (byte)0x00, (byte)0x80);
        setCursor(0, 0);
        // Partial LUT as a single 159-byte burst. WaveShare's reference sends
        // the same payload via CMD 0x32 and their partials render correctly,
        // so the IC accepts the trailing voltage bytes as part of the LUT
        // register for partial waveforms.
        // Write custom waveform LUT for partial refresh mode; data sourced from WaveShare EPD_2in9_V2.c reference implementation
        log.debug("partialInit: loading 159-byte partial LUT via CMD 0x32");
        sendCommand(CMD_WRITE_LUT);
        sendData(WF_PARTIAL_2IN9_WAIT, 0, 159);
        waitBusy();
        log.debug("partialInit: LUT loaded, applying display option / border / activating clock+analog");

        sendCommand(CMD_WRITE_DISP_OPT,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00);
        sendCommand(CMD_BORDER_WAVEFORM, (byte)0x80);
        // 0xC0 = enable clock + enable analog only (no display update); activates the oscillator after LUT load
        sendCommand(CMD_DISP_UPDATE_2,   (byte)0xC0);
        sendCommand(CMD_ACTIVATE);
        waitBusy();
        log.debug("partialInit: complete");
    }

    private void fastInit() {
        // Mirrors WaveShare EPD_2IN9_V2_Init_Fast(): hardware reset + SW_RESET +
        // standard register init + custom WF_FULL LUT loaded via LUT_by_host().
        // Border waveform 0x05 (vs 0x80 for slow FULL) is what WaveShare specifies
        // for the fast path — do not change without re-testing on hardware.
        log.debug("fastInit: begin");
        fullModeConfigured = true;
        hardwareReset();
        delay(100); // Additional settle time after hardware reset before issuing SW_RESET; mirrors WaveShare Init_Fast() timing
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();
        sendCommand(CMD_DRIVER_OUTPUT,   (byte)0x27, (byte)0x01, (byte)0x00);
        sendCommand(CMD_DATA_ENTRY_MODE, (byte)0x03);
        setFullWindow();
        sendCommand(CMD_BORDER_WAVEFORM, (byte)0x05);
        sendCommand(CMD_DISP_UPDATE_1,   (byte)0x00, (byte)0x80);
        setCursor(0, 0);
        waitBusy();
        loadFastLut();
        log.debug("fastInit: complete");
    }

    /**
     * Loads the WF_FULL fast LUT via the LUT_by_host protocol from WaveShare's
     * EPD_2in9_V2.c. The 159-byte WF_FULL array is split: the first 153 bytes go
     * to CMD 0x32 (CMD_WRITE_LUT), and the trailing 6 voltage bytes are sent via
     * dedicated voltage commands. This split is mandatory — the voltage registers
     * (EOPT, gate voltage, source voltage, VCOM) are separate from the waveform
     * RAM and must be programmed individually for the fast LUT to take effect.
     */
    private void loadFastLut() {
        log.debug("loadFastLut: sending 153-byte WF_FULL waveform via CMD 0x32");
        sendCommand(CMD_WRITE_LUT);
        sendData(WF_FULL, 0, 153);
        waitBusy();
        // Voltage bytes [153..158]: EOPT, VGH, VSH1, VSH2, VSL, VCOM
        sendCommand(CMD_EOPT,           WF_FULL[153]);
        sendCommand(CMD_GATE_VOLTAGE,   WF_FULL[154]);
        sendCommand(CMD_SOURCE_VOLTAGE, WF_FULL[155], WF_FULL[156], WF_FULL[157]);
        sendCommand(CMD_VCOM,           WF_FULL[158]);
        log.debug("loadFastLut: complete");
    }

    private void fourGrayInit() {
        log.debug("SSD1675A: fourGrayInit");
        fullModeConfigured = false;
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

        // Write custom waveform LUT for 4-gray refresh mode; data sourced from WaveShare EPD_2in9_V2.c reference implementation
        sendCommand(CMD_WRITE_LUT);
        sendData(GRAY4_LUT, 0, 153);
        waitBusy();
        sendCommand(CMD_EOPT,           GRAY4_LUT[153]);
        sendCommand(CMD_GATE_VOLTAGE,   GRAY4_LUT[154]);
        sendCommand(CMD_SOURCE_VOLTAGE, GRAY4_LUT[155], GRAY4_LUT[156], GRAY4_LUT[157]);
        sendCommand(CMD_VCOM,           GRAY4_LUT[158]);
    }

    private void configureFullRefreshMode() {
        // Matches WaveShare EPD_2IN9_V2_Init(): hardware reset then SW_RESET then configure.
        // The hardware reset is required to cleanly exit partial mode before a full refresh.
        hardwareReset();
        delay(100); // Additional settle time after hardware reset before issuing SW_RESET; mirrors WaveShare Init() timing
        waitBusy();
        sendCommand(CMD_SW_RESET);
        waitBusy();
        sendCommand(CMD_DRIVER_OUTPUT,   (byte)0x27, (byte)0x01, (byte)0x00);
        sendCommand(CMD_DATA_ENTRY_MODE, (byte)0x03);
        setFullWindow();
        sendCommand(CMD_DISP_UPDATE_1,   (byte)0x00, (byte)0x80);
        setCursor(0, 0);
        waitBusy();
        fullModeConfigured = true;
    }

    private void resetRefreshCounters() {
        partialRefreshCount  = 0;
        fastRefreshCount     = 0;
        lastFullRefreshTimeMs = System.currentTimeMillis();
    }

    // --- SPI and GPIO helpers ---

    private void hardwareReset() {
        // Hardware reset sequence per SSD1675A datasheet; RST is active-LOW.
        rst.state(DigitalState.HIGH);
        delay(20); // RST HIGH settling time per SSD1675A hardware reset specification
        rst.state(DigitalState.LOW);
        delay(2);  // RST LOW pulse width per SSD1675A hardware reset specification
        rst.state(DigitalState.HIGH);
        delay(20); // RST HIGH settling time — allow IC to complete internal power-on reset
    }

    private static final long BUSY_TIMEOUT_MS = 10_000;

    private void waitBusy() {
        // Per SSD1675A spec: BUSY HIGH = IC busy (updating), BUSY LOW = ready for commands.
        // Polls at 10 ms intervals with a 10-second timeout to detect hardware hang.
        long start = System.currentTimeMillis();
        long deadline = start + BUSY_TIMEOUT_MS;
        while (busy.isHigh()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "SSD1675A BUSY pin stuck HIGH after " + BUSY_TIMEOUT_MS + " ms");
            }
            delay(10); // Polling interval — 10 ms between BUSY pin samples
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= 5) {
            log.debug("waitBusy: BUSY released after {} ms", elapsed);
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

    // Linux spidev default bufsiz = 4096 bytes; chunk large transfers to stay within it.
    private static final int SPI_CHUNK = 4096;

    private void sendData(byte[] data) {
        sendData(data, 0, data.length);
    }

    private void sendData(byte[] data, int offset, int length) {
        assert data != null                        : "sendData: data must not be null";
        assert offset >= 0                         : "sendData: offset must be >= 0, was " + offset;
        assert length >= 0                         : "sendData: length must be >= 0, was " + length;
        assert offset + length <= data.length      : "sendData: offset+length (" + (offset + length)
                                                     + ") exceeds data.length (" + data.length + ")";
        dc.state(DigitalState.HIGH);
        int remaining = length;
        int pos = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, SPI_CHUNK);
            // Pi4J's Spi.transfer(byte[], int, byte[], int, int) delegates internally to
            // transfer(buf, off, buf, off, len) — the same array is passed as
            // both the write and read buffer. The FFM backend then copies the
            // full-duplex SPI read-back bytes (zeros from the eInk display)
            // back into that same array via System.arraycopy, corrupting it
            // in-place after every ioctl chunk. For a 4736-byte frame split
            // into 4096+640 chunks, the first chunk zeroes data[0..4095], so
            // the second RAM-plane write and all subsequent calls receive zeros
            // instead of the intended pixel data.
            //
            // Fix: copy each chunk before transfer so the source array is never
            // used as the read destination.
            spi.transfer(Arrays.copyOfRange(data, pos, pos + chunk));
            pos       += chunk;
            remaining -= chunk;
        }
    }

    private static void delay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
