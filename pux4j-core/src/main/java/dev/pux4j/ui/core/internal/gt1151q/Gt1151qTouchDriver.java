// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.gt1151q;

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

    Gt1151qTouchDriver(DriverConfig config) {
        var ctx  = config.pi4j();
        var json = config.config();

        int busAddr  = json.getInt("touchI2cAddress", 0x14);
        int trstPin  = json.getInt("touchRstPin",     22);
        int intPin   = json.getInt("touchIntPin",     27);

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
        if (touchInt.isHigh()) {
            return Collections.emptyList();
        }
        log.debug("GT1151Q: INT asserted (LOW) — reading touch data");

        byte[] statusBuf = new byte[1];
        readRegister(REG_TOUCH_STATUS, statusBuf);
        int status = statusBuf[0] & 0xFF;

        if ((status & 0x80) == 0) {
            writeRegister(REG_TOUCH_STATUS, (byte) 0x00);
            return Collections.emptyList();
        }

        int touchCount = status & 0x0F;
        if (touchCount < 1 || touchCount > MAX_TOUCH_POINTS) {
            log.warn("GT1151Q: unexpected touch count {}, discarding", touchCount);
            writeRegister(REG_TOUCH_STATUS, (byte) 0x00);
            return Collections.emptyList();
        }

        byte[] pointData = new byte[touchCount * BYTES_PER_POINT];
        readRegister(REG_TOUCH_DATA, pointData);
        writeRegister(REG_TOUCH_STATUS, (byte) 0x00);

        List<TouchPoint> points = new ArrayList<>(touchCount);
        for (int i = 0; i < touchCount; i++) {
            int base     = i * BYTES_PER_POINT;
            int trackId  = pointData[base + 1] & 0xFF;
            int x        = ((pointData[base + 3] & 0xFF) << 8) | (pointData[base + 2] & 0xFF);
            int y        = ((pointData[base + 5] & 0xFF) << 8) | (pointData[base + 4] & 0xFF);
            int size     = ((pointData[base + 7] & 0xFF) << 8) | (pointData[base + 6] & 0xFF);
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
        touchRst.state(DigitalState.HIGH);
        delay(100);
        touchRst.state(DigitalState.LOW);
        delay(100);
        touchRst.state(DigitalState.HIGH);
        delay(100);
    }

    private void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
