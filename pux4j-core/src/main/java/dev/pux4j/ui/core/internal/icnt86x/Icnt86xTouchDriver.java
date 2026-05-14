// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.icnt86x;

import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.io.i2c.I2C;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Icnt86xTouchDriver implements TouchDriver {

    private static final Logger log = LoggerFactory.getLogger(Icnt86xTouchDriver.class);

    // ICNT86X register addresses (16-bit, big-endian over I2C)
    private static final int REG_IC_VERSION  = 0x000A;
    private static final int REG_TOUCH_COUNT = 0x1001;
    private static final int REG_TOUCH_DATA  = 0x1002;

    private static final int MAX_TOUCH_POINTS   = 5;
    private static final int BYTES_PER_POINT     = 7;

    private final I2C          i2c;
    private final DigitalOutput touchRst;
    private final DigitalInput  touchInt;

    Icnt86xTouchDriver(DriverConfig config) {
        var ctx  = config.pi4j();
        var json = config.config();

        int busAddr  = json.getInt("touchI2cAddress", 0x48);
        int trstPin  = json.getInt("touchRstPin",     22);
        int intPin   = json.getInt("touchIntPin",     27);

        i2c = ctx.create(I2C.newConfigBuilder(ctx)
            .id("icnt86x-i2c")
            .name("ICNT86X I2C")
            .bus(1)
            .device(busAddr)
            .build());

        touchRst = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("icnt86x-trst")
            .name("ICNT86X TRST")
            .bcm(trstPin)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.HIGH)
            .build());

        touchInt = ctx.create(DigitalInput.newConfigBuilder(ctx)
            .id("icnt86x-int")
            .name("ICNT86X INT")
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
    }

    @Override
    public List<TouchPoint> readTouches() {
        // Gate on the INT pin before issuing any I2C read. The ICNT86X asserts
        // INT LOW when new touch data is ready and releases it HIGH after the host
        // clears REG_TOUCH_COUNT (write 0x00). If INT is HIGH, there is no pending
        // data — return immediately to avoid unnecessary I2C traffic and log spam.
        if (touchInt.isHigh()) {
            return Collections.emptyList();
        }
        log.debug("ICNT86X: INT asserted (LOW) — reading touch data");

        byte[] countBuf = new byte[1];
        readRegister(REG_TOUCH_COUNT, countBuf);
        int count = countBuf[0] & 0xFF;

        if (count == 0) {
            writeRegister(REG_TOUCH_COUNT, (byte)0x00);
            delay(1);
            return Collections.emptyList();
        }

        if (count < 1 || count > MAX_TOUCH_POINTS) {
            log.warn("ICNT86X: unexpected touch count {}, discarding", count);
            writeRegister(REG_TOUCH_COUNT, (byte)0x00);
            return Collections.emptyList();
        }

        byte[] pointData = new byte[count * BYTES_PER_POINT];
        readRegister(REG_TOUCH_DATA, pointData);
        writeRegister(REG_TOUCH_COUNT, (byte)0x00);

        List<TouchPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base     = i * BYTES_PER_POINT;
            int x        = ((pointData[base + 2] & 0xFF) << 8) | (pointData[base + 1] & 0xFF);
            int y        = ((pointData[base + 4] & 0xFF) << 8) | (pointData[base + 3] & 0xFF);
            int eventId  = pointData[base + 6] & 0xFF;
            boolean down = (eventId == 0 || eventId == 2); // press or hold
            points.add(new TouchPoint(i, x, y, down));
        }

        log.debug("ICNT86X: {} touch contact(s): {}", count, points);
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
        touchRst.state(DigitalState.HIGH);
        delay(100);
        touchRst.state(DigitalState.LOW);
        delay(100);
        touchRst.state(DigitalState.HIGH);
        delay(100);
    }

    private static void delay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
