// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import dev.pux4j.ui.core.internal.CapturingDisplayDriver;

import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Optional screenshot capture for any {@link EInkDisplayDriver}, emulated or real.
 *
 * <p>When the system property {@value #DIR_PROPERTY} names a directory, {@link #wrap} returns a
 * driver that forwards every call to the given one and also writes each distinct frame it is
 * asked to display as a greyscale PNG, in logical (on-screen) orientation, named
 * {@code <prefix>-NNNN.png}. The prefix is the value of {@value #PREFIX_PROPERTY}
 * (default {@code screen}); the launcher sets it to the app name when it is not set.
 * A frame identical to the previous one is not written again. Without the property the driver
 * is returned unchanged, as it is if capture cannot be set up (a warning is logged).
 *
 * <p>The capture shows what the driver was asked to display. It does not show what a physical
 * panel does with it (ghosting, waveform or wiring faults). Capture failures are logged and
 * never affect the display.
 */
public final class ScreenshotCapture {

    /** System property naming the output directory; capture is off when it is not set. */
    public static final String DIR_PROPERTY = "pux4j.screenshots";
    /** System property for the file name prefix; default {@code screen}. */
    public static final String PREFIX_PROPERTY = "pux4j.screenshots.prefix";

    private ScreenshotCapture() {}

    /** Returns {@code driver} wrapped for capture if {@value #DIR_PROPERTY} is set, else {@code driver}. */
    public static EInkDisplayDriver wrap(EInkDisplayDriver driver) {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return driver;
        }
        if (driver instanceof CapturingDisplayDriver) {
            return driver;   // already capturing: a second decorator would overwrite its files
        }
        try {
            return new CapturingDisplayDriver(driver, Path.of(dir), System.getProperty(PREFIX_PROPERTY, "screen"));
        } catch (RuntimeException e) {
            // A bad directory or any setup failure must not stop the app: carry on without capture.
            LoggerFactory.getLogger(ScreenshotCapture.class)
                .warn("Screenshot capture is off: could not set it up for {}={}", DIR_PROPERTY, dir, e);
            return driver;
        }
    }
}
