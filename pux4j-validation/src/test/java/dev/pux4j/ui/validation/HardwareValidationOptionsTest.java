// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Orientation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HardwareValidationOptionsTest {

    private static final String[] PIN_KEYS =
        {"dcPin", "rstPin", "busyPin", "touchRstPin", "touchIntPin", "touchI2cAddress"};

    /** True if the key is present, whatever its value (probed with two different defaults). */
    private static boolean hasInt(DriverConfig config, String key) {
        return config.property(key, -1) != -1 || config.property(key, -2) != -2;
    }

    @Test
    void noFlagsProducesNoPinOrAddressKeys() {
        var options = HardwareValidationTest.Options.parse(new String[] {"--display", "png", "--touch", "x"});
        DriverConfig config = options.driverConfig(Orientation.LANDSCAPE);

        for (String key : PIN_KEYS) {
            assertEquals(false, hasInt(config, key), key);
        }
        assertEquals("LANDSCAPE", config.property("orientation", "unset"));
        assertNull(options.orientation());
    }

    @Test
    void flagsProduceMatchingKeys() {
        var options = HardwareValidationTest.Options.parse(new String[] {
            "--dc-pin", "1", "--rst-pin", "2", "--busy-pin", "3",
            "--touch-rst-pin", "4", "--touch-int-pin", "5", "--touch-i2c-address", "0x15"});
        DriverConfig config = options.driverConfig(Orientation.LANDSCAPE_INVERTED);

        assertEquals(1, config.property("dcPin", -1));
        assertEquals(2, config.property("rstPin", -1));
        assertEquals(3, config.property("busyPin", -1));
        assertEquals(4, config.property("touchRstPin", -1));
        assertEquals(5, config.property("touchIntPin", -1));
        assertEquals(0x15, config.property("touchI2cAddress", -1));
        assertEquals("LANDSCAPE_INVERTED", config.property("orientation", "unset"));
    }

    @Test
    void onlyPassedFlagsAreWritten() {
        var options = HardwareValidationTest.Options.parse(new String[] {"--busy-pin", "7"});
        DriverConfig config = options.driverConfig(Orientation.LANDSCAPE);

        assertEquals(7, config.property("busyPin", -1));
        assertEquals(false, hasInt(config, "dcPin"));
        assertEquals(false, hasInt(config, "touchI2cAddress"));
    }
}
