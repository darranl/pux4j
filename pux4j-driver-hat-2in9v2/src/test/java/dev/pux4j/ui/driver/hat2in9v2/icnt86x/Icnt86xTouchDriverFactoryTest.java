// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.TouchCalibration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Icnt86xTouchDriverFactoryTest {

    @Test
    void touchCalibrationIsFixedAndIgnoresDisplaySize() {
        var expected = new TouchCalibration(296, 128, true, true, false);
        var factory = new Icnt86xTouchDriverFactory();
        assertEquals(expected, factory.touchCalibration(296, 128));
        assertEquals(expected, factory.touchCalibration(1, 1));
    }
}
