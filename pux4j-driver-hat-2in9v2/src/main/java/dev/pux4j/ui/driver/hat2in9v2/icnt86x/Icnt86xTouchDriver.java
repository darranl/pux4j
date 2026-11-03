// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import com.pi4j.context.Context;
import dev.pux4j.ui.core.Pux4jContext;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.io.i2c.I2C;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.GpioChipResolver;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

final class Icnt86xTouchDriver implements TouchDriver {

    private static final Logger log = LoggerFactory.getLogger(Icnt86xTouchDriver.class);

    // ICNT86X register addresses (16-bit, big-endian over I2C)
    private static final int REG_IC_VERSION  = 0x000A;
    private static final int REG_TOUCH_COUNT = 0x1001;
    private static final int REG_TOUCH_DATA  = 0x1002;

    private static final int MAX_TOUCH_POINTS   = 5;
    private static final int BYTES_PER_POINT     = 7;

    /**
     * Event id (byte 6 of a point record) at which the chip reports a lift. Inferred from captured
     * reports, not from the vendor documentation: 1 = new contact, 2 = moving, 3 = stationary, 4 = lift.
     */
    static final int EVENT_LIFT = 4;

    private final TouchContactTracker contacts;
    private final I2C          i2c;
    private final DigitalOutput touchRst;
    private final DigitalInput  touchInt;

    Icnt86xTouchDriver(Pux4jContext context, DriverConfig config) {
        Context ctx = context.pi4j();

        int busAddr  = config.property("touchI2cAddress", 0x48);
        int trstPin  = config.property("touchRstPin",     22);
        int intPin   = config.property("touchIntPin",     27);
        int gpioChip = GpioChipResolver.resolveHeaderChip(config);
        contacts = new TouchContactTracker(
            config.property("touchHoldTimeoutMs", (int) TouchContactTracker.DEFAULT_HOLD_TIMEOUT_MS));

        i2c = ctx.create(I2C.newConfigBuilder(ctx)
            .id("icnt86x-i2c")
            .name("ICNT86X I2C")
            .bus(1)
            .device(busAddr)
            .build());

        touchRst = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("icnt86x-trst")
            .name("ICNT86X TRST")
            .bus(gpioChip)
            .bcm(trstPin)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.HIGH)
            .build());

        touchInt = ctx.create(DigitalInput.newConfigBuilder(ctx)
            .id("icnt86x-int")
            .name("ICNT86X INT")
            .bus(gpioChip)
            .bcm(intPin)
            .pull(PullResistance.PULL_UP)
            .build());
    }

    @Override
    public void initialize() {
        log.info("ICNT86X: initializing touch IC");
        hardwareReset();

        byte[] version = new byte[4];
        readRegister(REG_IC_VERSION, version);
        // IC version bytes: [0]=IC major, [1]=IC minor, [2]=FW major, [3]=FW minor
        log.info("ICNT86X: IC version 0x{}{} FW version 0x{}{}",
            String.format("%02X", version[0] & 0xFF),
            String.format("%02X", version[1] & 0xFF),
            String.format("%02X", version[2] & 0xFF),
            String.format("%02X", version[3] & 0xFF));
    }

    @Override
    public void reset() {
        log.debug("ICNT86X: reset");
        hardwareReset();
        contacts.clear();
    }

    @Override
    public List<TouchPoint> readTouches() {
        long now = System.nanoTime();
        // Gate on the INT pin before issuing any I2C read. The ICNT86X asserts
        // INT LOW when new touch data is ready and releases it HIGH after the host
        // clears REG_TOUCH_COUNT (write 0x00). If INT is HIGH there is no new report, but a
        // finger resting on the panel is still down: hold it (see TouchContactTracker).
        if (touchInt.isHigh()) {
            return contacts.onNoReport(now);
        }
        log.debug("ICNT86X: INT asserted (LOW) — reading touch data");

        // Read touch point count from ICNT86X register 0x1001 (1 byte).
        // Non-zero value means that many touch points are ready at 0x1002.
        byte[] countBuf = new byte[1];
        readRegister(REG_TOUCH_COUNT, countBuf);
        int count = countBuf[0] & 0xFF;

        if (count == 0) {
            log.debug("ICNT86X: zero-contact report");
            // Write 0x00 to REG_TOUCH_COUNT (0x1001) to acknowledge and release the INT pin HIGH
            writeRegister(REG_TOUCH_COUNT, (byte)0x00);
            delay(1); // TODO: verify this delay is still required; may be removable once hardware behaviour is confirmed
            return contacts.onReport(List.of(), now);
        }

        if (count > MAX_TOUCH_POINTS) {
            log.warn("ICNT86X: unexpected touch count {}, discarding", count);
            writeRegister(REG_TOUCH_COUNT, (byte)0x00);
            return contacts.onNoReport(now);
        }

        // Read touch point coordinates from ICNT86X register 0x1002 (7 bytes per point).
        byte[] pointData = new byte[count * BYTES_PER_POINT];
        readRegister(REG_TOUCH_DATA, pointData);
        // Write 0x00 to REG_TOUCH_COUNT (0x1001) to acknowledge the interrupt — releases INT pin HIGH
        writeRegister(REG_TOUCH_COUNT, (byte)0x00);
        if (log.isDebugEnabled()) {
            log.debug("ICNT86X: raw count={} bytes={}", count, HexFormat.ofDelimiter(" ").formatHex(pointData));
        }

        List<TouchPoint> points = decode(pointData, count);
        log.debug("ICNT86X: {} record(s), {} contact(s) down: {}", count, points.size(), points);
        return contacts.onReport(points, now);
    }

    /**
     * Decodes {@code count} 7-byte point records into the contacts that are down. A record with
     * event id {@link #EVENT_LIFT} is a lift and is left out; every other event id (new contact,
     * moving, stationary) is a finger on the panel. An empty result means no finger is down.
     */
    static List<TouchPoint> decode(byte[] pointData, int count) {
        List<TouchPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base    = i * BYTES_PER_POINT;
            int x       = ((pointData[base + 2] & 0xFF) << 8) | (pointData[base + 1] & 0xFF);
            int y       = ((pointData[base + 4] & 0xFF) << 8) | (pointData[base + 3] & 0xFF);
            int eventId = pointData[base + 6] & 0xFF;
            if (eventId != EVENT_LIFT) {
                points.add(new TouchPoint(i, x, y, true));
            }
        }
        return Collections.unmodifiableList(points);
    }

    // --- I2C register helpers (16-bit big-endian address) ---

    private void readRegister(int reg, byte[] buf) {
        byte[] addr = { (byte)(reg >> 8), (byte)(reg & 0xFF) };
        i2c.readRegister(addr, buf);
    }

    private void writeRegister(int reg, byte value) {
        byte[] data = { (byte)(reg >> 8), (byte)(reg & 0xFF), value };
        i2c.write(data);
    }

    // --- GPIO helpers ---

    private void hardwareReset() {
        // ICNT86X hardware reset per ICNT86X datasheet: RST is active-LOW with 100 ms settling on each phase.
        touchRst.state(DigitalState.HIGH);
        delay(100); // Allow RST HIGH to fully settle before pulling LOW
        touchRst.state(DigitalState.LOW);
        delay(100); // RST LOW hold time — IC performs internal reset sequence
        touchRst.state(DigitalState.HIGH);
        delay(100); // Post-reset settling time before first I2C access
    }

    private static void delay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
