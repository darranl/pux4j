// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

/**
 * ServiceLoader factory for the queue-based {@link ProgrammaticTouchDriver}, for headless
 * dev-loop and CI use (see {@code notes/project-plan.md} Phase 6.3). Never auto-selected —
 * a caller must request it explicitly by name, then queue touches via the
 * {@link dev.pux4j.ui.core.ProgrammaticTouchSource} contract the returned driver also
 * implements.
 */
public final class ProgrammaticTouchDriverFactory implements TouchDriverFactory {

    private static final String NAME = "programmatic";

    @Override
    public String name() { return NAME; }

    /** Never auto-selected; use {@code --touch-driver=programmatic} to select it explicitly. */
    @Override
    public boolean isAvailable() { return false; }

    @Override
    public TouchDriver create(Pux4jContext context, DriverConfig config) {
        return new ProgrammaticTouchDriver();
    }

    // Synthetic touches are queued directly in display-logical space by the caller (there is
    // no physical IC with its own native reporting range to calibrate against), so this is an
    // identity calibration matching whatever display is currently selected — same reasoning
    // as EmulatedTouchDriverFactory.touchCalibration in pux4j-emulator.
    @Override
    public TouchCalibration touchCalibration(int displayLogicalWidth, int displayLogicalHeight) {
        return new TouchCalibration(displayLogicalWidth, displayLogicalHeight, false, false, false);
    }
}
