// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Icnt86xTouchDriverFactory implements TouchDriverFactory {

    // The ICNT86X on the WaveShare 2.9" V2 HAT reports raw coordinates already in the
    // display's own X/Y range (no axis swap needed); flipX/flipY correct the IC's origin
    // corner relative to the display's LANDSCAPE mounting. Calibrated against real hardware
    // — see diary.md 2026-08-30 and notes/project-plan.md Phase 6.0 (previously threaded
    // through --flip-x/--flip-y/--touch-native-width/--touch-native-height CLI flags; now
    // owned here, next to the one driver it's actually true for).
    private static final TouchCalibration CALIBRATION = new TouchCalibration(296, 128, true, true, false);

    @Override
    public String name() { return "icnt86x"; }

    @Override
    public int priority() { return 100; }

    @Override
    public boolean isAvailable() {
        try {
            return Files.readString(Path.of("/proc/device-tree/model")).startsWith("Raspberry Pi");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public TouchDriver create(Pux4jContext context, DriverConfig config) {
        return new Icnt86xTouchDriver(context, config);
    }

    @Override
    public TouchCalibration touchCalibration(int displayLogicalWidth, int displayLogicalHeight) {
        return CALIBRATION;
    }
}
