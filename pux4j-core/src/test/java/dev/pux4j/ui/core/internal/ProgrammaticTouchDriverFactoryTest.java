// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.ProgrammaticTouchSource;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgrammaticTouchDriverFactoryTest {

    @Test
    void neverAutoSelectedButSelectableByName() {
        TouchDriverFactory factory = TouchDriverFactory.select("programmatic");
        assertEquals("programmatic", factory.name());
        // Regression guard: this driver must stay opt-in-only (see 6.3's plan — it exists
        // for headless dev-loop/CI callers that explicitly ask for it, never as a silent
        // stand-in for a missing hardware touch IC).
        assertFalse(factory.isAvailable());
    }

    @Test
    void touchCalibrationIsIdentityMatchingTheSelectedDisplay() {
        TouchDriverFactory factory = TouchDriverFactory.select("programmatic");
        TouchCalibration calibration = factory.touchCalibration(250, 122);

        assertEquals(new TouchCalibration(250, 122, false, false, false), calibration);
    }

    @Test
    void createdDriverIsQueueableThroughProgrammaticTouchSource() {
        TouchDriver driver = TouchDriverFactory.select("programmatic").create(null, DriverConfig.builder().build());

        assertTrue(driver instanceof ProgrammaticTouchSource,
            "the returned TouchDriver must also implement ProgrammaticTouchSource so a caller can queue touches");

        var source = (ProgrammaticTouchSource) driver;
        source.queueTouch(new TouchPoint(1, 10, 20, true));

        List<TouchPoint> batch = driver.readTouches();
        assertEquals(List.of(new TouchPoint(1, 10, 20, true)), batch);
        assertEquals(List.of(), driver.readTouches(), "the queue must drain one batch per readTouches() call");
    }

    @Test
    void resetClearsAnyQueuedTouches() {
        TouchDriver driver = TouchDriverFactory.select("programmatic").create(null, DriverConfig.builder().build());
        ((ProgrammaticTouchSource) driver).queueTouch(new TouchPoint(1, 0, 0, true));

        driver.reset();

        assertEquals(List.of(), driver.readTouches());
    }
}
