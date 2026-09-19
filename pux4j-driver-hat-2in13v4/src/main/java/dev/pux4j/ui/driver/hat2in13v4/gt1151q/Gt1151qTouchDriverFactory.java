// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.gt1151q;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Gt1151qTouchDriverFactory implements TouchDriverFactory {

    // The GT1151Q on the WaveShare 2.13" V4 HAT reports raw coordinates in the same
    // native-portrait frame as the SSD1680 chip itself (122 wide x 250 tall) — confirmed
    // against the vendored WaveShare demo (hardware/common/Touch_e-Paper_Code/python/
    // examples/TP2in13_test.py: touch hit-tests there use X in roughly 0-121 and Y in
    // roughly 0-249). Its raw X axis aligns to the display's native (portrait) X axis, not
    // Y — a real bug both here and in hardware/spec/touch-gt1151q.md claimed the opposite
    // (250 wide x 122 tall), caught and fixed 2026-09-19 by independently re-deriving from
    // the demo source rather than trusting that doc. swapAxes is still needed because the
    // display's LANDSCAPE_INVERTED mounting swaps which native axis the logical X/Y axes
    // correspond to; flipX corrects the IC's origin corner relative to that mounting.
    // Calibrated against real hardware — see diary.md 2026-05-17 and
    // notes/project-plan.md Phase 6.0.
    private static final TouchCalibration CALIBRATION = new TouchCalibration(122, 250, true, false, true);

    @Override
    public String name() { return "gt1151q"; }

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
        return new Gt1151qTouchDriver(context, config);
    }

    @Override
    public TouchCalibration touchCalibration(int displayLogicalWidth, int displayLogicalHeight) {
        return CALIBRATION;
    }
}
