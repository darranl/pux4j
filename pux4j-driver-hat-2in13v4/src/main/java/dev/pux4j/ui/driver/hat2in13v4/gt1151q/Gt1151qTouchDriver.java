// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.gt1151q;

import com.pi4j.context.Context;
import dev.pux4j.ui.core.Pux4jContext;
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

final class Gt1151qTouchDriver implements TouchDriver {

    private static final Logger log = LoggerFactory.getLogger(Gt1151qTouchDriver.class);

    // GT1151Q register addresses (16-bit, big-endian over I2C)
    private static final int REG_PRODUCT_ID    = 0x8140;
    private static final int REG_FW_VERSION    = 0x8144;
    private static final int REG_TOUCH_STATUS  = 0x814E;
    private static final int REG_TOUCH_DATA    = 0x814F;

    private static final int MAX_TOUCH_POINTS = 5;
    private static final int BYTES_PER_POINT  = 8;

    private final I2C           i2c;
    private final DigitalOutput touchRst;
    private final DigitalInput  touchInt;

    Gt1151qTouchDriver(Pux4jContext context, DriverConfig config) {
        Context ctx = context.pi4j();

        int busAddr  = config.intProperty("touchI2cAddress", 0x14);
        int trstPin  = config.intProperty("touchRstPin",     22);
        int intPin   = config.intProperty("touchIntPin",     27);

        i2c = ctx.create(I2C.newConfigBuilder(ctx)
            .id("gt1151q-i2c")
            .name("GT1151Q I2C")
            .bus(1)
            .device(busAddr)
            .build());

        touchRst = ctx.create(DigitalOutput.newConfigBuilder(ctx)
            .id("gt1151q-trst")
            .name("GT1151Q TRST")
            .bcm(trstPin)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.HIGH)
            .build());

        touchInt = ctx.create(DigitalInput.newConfigBuilder(ctx)
            .id("gt1151q-int")
            .name("GT1151Q INT")
            .bcm(intPin)
            .pull(PullResistance.PULL_UP)
            .build());
    }

    @Override
    public void initialize() {
        log.info("GT1151Q: initializing touch IC");
        hardwareReset();

        byte[] productId = new byte[4];
        readRegister(REG_PRODUCT_ID, productId);
        String product = new String(productId);

        byte[] fwVersion = new byte[2];
        readRegister(REG_FW_VERSION, fwVersion);
        int version = ((fwVersion[0] & 0xFF) << 8) | (fwVersion[1] & 0xFF);

        log.info("GT1151Q: Product ID '{}', FW version 0x{}",
            product, String.format("%04X", version));
    }

    @Override
    public void reset() {
        log.debug("GT1151Q: reset");
        hardwareReset();
    }

    @Override
    public List<TouchPoint> readTouches() {
        // INT pin is active-LOW: the GT1151Q drives it LOW when new touch data is available.
        // If INT is HIGH there is no pending event — skip I2C entirely to avoid unnecessary bus traffic.
        if (touchInt.isHigh()) {
            return Collections.emptyList();
        }
        log.debug("GT1151Q: INT asserted (LOW) — reading touch data");

        // Read touch point count and status from GT1151Q status register 0x814E (1 byte).
        // Bit 7 = buffer ready flag; bits [3:0] = number of touch points currently detected.
        byte[] statusBuf = new byte[1];
        readRegister(REG_TOUCH_STATUS, statusBuf);
        int status = statusBuf[0] & 0xFF;

        if ((status & 0x80) == 0) {
            writeRegister(REG_TOUCH_STATUS, (byte) 0x00);
            return Collections.emptyList();
        }

        int touchCount = status & 0x0F;
        if (touchCount == 0) {
            writeRegister(REG_TOUCH_STATUS, (byte) 0x00);
            return Collections.emptyList();
        }
        if (touchCount > MAX_TOUCH_POINTS) {
            log.warn("GT1151Q: unexpected touch count {}, discarding", touchCount);
            writeRegister(REG_TOUCH_STATUS, (byte) 0x00);
            return Collections.emptyList();
        }

        // Read touch point coordinates from GT1151Q data register 0x814F (8 bytes per point).
        byte[] pointData = new byte[touchCount * BYTES_PER_POINT];
        readRegister(REG_TOUCH_DATA, pointData);
        // Write 0x00 to REG_TOUCH_STATUS (0x814E) to acknowledge the interrupt — releases the INT pin HIGH
        writeRegister(REG_TOUCH_STATUS, (byte) 0x00);

        List<TouchPoint> points = new ArrayList<>(touchCount);
        for (int i = 0; i < touchCount; i++) {
            int base     = i * BYTES_PER_POINT;
            // GT1151Q layout per point (8 bytes from 0x814F):
            //   [0] track ID  [1] X_L  [2] X_H  [3] Y_L  [4] Y_H  [5] size_L  [6] size_H  [7] reserved
            int trackId  = pointData[base + 0] & 0xFF;
            int x        = ((pointData[base + 2] & 0xFF) << 8) | (pointData[base + 1] & 0xFF);
            int y        = ((pointData[base + 4] & 0xFF) << 8) | (pointData[base + 3] & 0xFF);
            points.add(new TouchPoint(trackId, x, y, true));
        }

        log.debug("GT1151Q: {} touch contact(s): {}", touchCount, points);
        return Collections.unmodifiableList(points);
    }

    private void readRegister(int reg, byte[] buf) {
        byte[] addr = { (byte)(reg >> 8), (byte)(reg & 0xFF) };
        i2c.readRegister(addr, buf);
    }

    private void writeRegister(int reg, byte value) {
        byte[] data = { (byte)(reg >> 8), (byte)(reg & 0xFF), value };
        i2c.write(data);
    }

    private void hardwareReset() {
        // GT1151Q hardware reset per GT1151Q datasheet: RST is active-LOW with 100 ms settling on each phase.
        touchRst.state(DigitalState.HIGH);
        delay(100); // Allow RST HIGH to fully settle before pulling LOW
        touchRst.state(DigitalState.LOW);
        delay(100); // RST LOW hold time — IC performs internal reset sequence
        touchRst.state(DigitalState.HIGH);
        delay(100); // Post-reset settling time before first I2C access
    }

    private void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
