// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

/**
 * ServiceLoader factory that returns the {@link EmulatedTouchDriver} created by
 * {@link EmulatedDisplayDriverFactory}. Must be called after the display factory.
 *
 * <p>Selected automatically on any machine that is not a Raspberry Pi (priority 50,
 * always available). On Raspberry Pi the hardware drivers (priority 100) take precedence.
 */
public final class EmulatedTouchDriverFactory implements TouchDriverFactory {

    @Override
    public String name() { return "emulator"; }

    @Override
    public int priority() { return 50; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public TouchDriver create(Pux4jContext context, DriverConfig config) {
        EmulatedTouchDriver touch = EmulatorContext.instance().touch();
        if (touch == null) {
            throw new IllegalStateException(
                "EmulatedTouchDriverFactory.create() called before EmulatedDisplayDriverFactory.create(). "
                + "Create the display driver first.");
        }
        return touch;
    }

    // EmulatedTouchDriver already reports coordinates in display-logical space (mouse
    // position / scale factor — see EmulatedTouchDriver.onMousePressed), so its "native"
    // resolution is whatever display is currently selected, not a fixed physical constant
    // like the hardware drivers': an identity calibration matching the display exactly.
    @Override
    public TouchCalibration touchCalibration(int displayLogicalWidth, int displayLogicalHeight) {
        return new TouchCalibration(displayLogicalWidth, displayLogicalHeight, false, false, false);
    }
}
