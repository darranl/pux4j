// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.gt1151q;

import dev.pux4j.ui.core.TouchCalibration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Gt1151qTouchDriverFactoryTest {

    @Test
    void touchCalibrationIsFixedAndIgnoresDisplaySize() {
        var expected = new TouchCalibration(122, 250, true, false, true);
        var factory = new Gt1151qTouchDriverFactory();
        assertEquals(expected, factory.touchCalibration(250, 122));
        assertEquals(expected, factory.touchCalibration(1, 1));
    }
}
