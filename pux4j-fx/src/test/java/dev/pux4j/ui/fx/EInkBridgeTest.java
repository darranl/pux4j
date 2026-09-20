// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link EInkBridge} construction (driver selection, configuration, geometry) —
 * the part of Phase 6.4 that doesn't need the FX toolkit or a display/touch thread running.
 * Uses the real {@code png}/{@code programmatic} drivers from {@code pux4j-core} (opt-in,
 * software-only) rather than mocks, matching the rest of this codebase's test style.
 */
class EInkBridgeTest {

    private static final String DISPLAY_PROP = "pux4j.display.driver";
    private static final String TOUCH_PROP = "pux4j.touch.driver";
    private static final String ORIENTATION_PROP = "pux4j.display.orientation";
    private static final String PIXEL_FORMAT_PROP = "pux4j.transform.pixelFormat";
    private static final String STRATEGY_PROP = "pux4j.transform.strategy";

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(DISPLAY_PROP);
        System.clearProperty(TOUCH_PROP);
        System.clearProperty(ORIENTATION_PROP);
        System.clearProperty(PIXEL_FORMAT_PROP);
        System.clearProperty(STRATEGY_PROP);
    }

    /**
     * The PNG driver's default native size is 128x296 (matching the SSD1675A/2.9" panel it
     * mimics); LANDSCAPE (the default orientation) swaps that to a 296x128 logical canvas per
     * OrientationMapping's landscape rule — this pins the bridge's geometry accessors to that
     * derivation rather than to the raw native pair, which is the whole point of
     * displayWidth()/displayHeight() existing separately from framebufferWidth()/Height().
     */
    @Test
    void fromConfig_selectsNamedPngDriver_exposesLogicalAndFramebufferGeometry() {
        System.setProperty(DISPLAY_PROP, "png");

        EInkBridge bridge = EInkBridge.fromConfig();

        assertEquals(296, bridge.displayWidth());
        assertEquals(128, bridge.displayHeight());
        assertEquals(128, bridge.framebufferWidth());
        assertEquals(296, bridge.framebufferHeight());
        assertFalse(bridge.hasTouch(), "no pux4j.touch.driver set and no auto-selectable touch factory on the test classpath");
    }

    /**
     * An explicitly requested touch driver name must be honoured (not silently swallowed the
     * way an unrequested auto-select failure is) — proves the two code paths in
     * {@code fromConfig()} are actually distinct, not just that construction succeeds.
     */
    @Test
    void fromConfig_withExplicitProgrammaticTouch_wiresTouchDriver() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(TOUCH_PROP, "programmatic");

        EInkBridge bridge = EInkBridge.fromConfig();

        assertTrue(bridge.hasTouch());
    }

    /**
     * {@code pux4j.display.orientation} changes which OrientationMapping the bridge derives
     * its geometry from — PORTRAIT keeps the PNG driver's native 128x296 pair unrotated,
     * unlike the LANDSCAPE case above where width and height swap.
     */
    @Test
    void fromConfig_orientationProperty_changesLogicalGeometry() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(ORIENTATION_PROP, "PORTRAIT");

        EInkBridge bridge = EInkBridge.fromConfig();

        assertEquals(128, bridge.displayWidth());
        assertEquals(296, bridge.displayHeight());
    }

    /**
     * A display driver is mandatory (unlike touch, there's no "run without one" mode), so an
     * unmatched name must always fail loudly, with no auto-select fallback to swallow it.
     */
    @Test
    void fromConfig_unknownDisplayDriverName_throwsIllegalStateException() {
        System.setProperty(DISPLAY_PROP, "does-not-exist");

        assertThrows(IllegalStateException.class, EInkBridge::fromConfig);
    }

    /**
     * An explicitly requested but unmatched touch driver must fail loudly — it is a
     * misconfiguration, not the "no touch hardware present" case that auto-select swallows.
     */
    @Test
    void fromConfig_unknownTouchDriverName_throwsIllegalStateException() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(TOUCH_PROP, "does-not-exist");

        assertThrows(IllegalStateException.class, EInkBridge::fromConfig);
    }

    /** {@link EInkBridge.Builder#display(EInkDisplayDriver)} is the one required setter. */
    @Test
    void builder_withoutDisplay_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> EInkBridge.builder().build());
    }

    /**
     * {@code 0} is a legal, documented value (disables timer-driven capture — the application
     * drives all refreshes manually), so the validation boundary is "negative", not "non-positive".
     */
    @Test
    void builder_negativeCaptureInterval_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> EInkBridge.builder().captureIntervalMs(-1));
    }

    /**
     * Explicit wiring (no system properties at all) exercises the other construction path —
     * a caller that already has driver instances (e.g. built directly via
     * {@link DisplayDriverFactory#select(String)}, as here) rather than going through
     * {@code fromConfig()}'s system-property selection.
     */
    @Test
    void builder_explicitDisplayDriver_exposesGeometry() {
        DisplayDriverFactory factory = DisplayDriverFactory.select("png");
        EInkDisplayDriver display = factory.create(null, DriverConfig.builder().build());
        display.initialize();

        EInkBridge bridge = EInkBridge.builder().display(display).build();

        assertEquals(296, bridge.displayWidth());
        assertEquals(128, bridge.displayHeight());
        assertFalse(bridge.hasTouch());
    }

    /**
     * The PNG driver declares both MONOCHROME and FOUR_GRAY, so its
     * {@code DisplayCapabilities.preferredFormat()} is FOUR_GRAY — an explicit override to
     * MONOCHROME must still construct successfully (THRESHOLD, the default strategy, is
     * valid for MONOCHROME; it's only invalid for FOUR_GRAY — see the next test).
     */
    @Test
    void fromConfig_explicitMonochromePixelFormat_constructsSuccessfully() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(PIXEL_FORMAT_PROP, "MONOCHROME");

        EInkBridge bridge = EInkBridge.fromConfig();

        assertEquals(296, bridge.displayWidth());
    }

    /**
     * Pins the reasoning behind {@code EInkBridge.pixelFormatDefaults()}: THRESHOLD is
     * rejected by {@code RenderSession.validate()} for FOUR_GRAY (it's a MONOCHROME-only
     * strategy — see {@code RenderSession}'s javadoc). Without the FOUR_GRAY_BINNING
     * override applied when the format isn't overridden too, this combination — the PNG
     * driver's default preferred format with an explicit non-default strategy — would throw
     * for a different, wrong reason (or not at all, if the override were removed and this
     * test weren't here to catch it).
     */
    @Test
    void fromConfig_explicitThresholdStrategyWithDefaultFourGrayFormat_throwsIllegalArgumentException() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(STRATEGY_PROP, "THRESHOLD");

        assertThrows(IllegalArgumentException.class, EInkBridge::fromConfig);
    }

    /**
     * Pins the touch mapper's geometry, not just its presence: the programmatic driver's
     * identity calibration means a corner touch point should map through unchanged. Built
     * from {@code orientationMapping.logicalWidth()/logicalHeight()} — if it were built from
     * the framebuffer dimensions instead (an easy slip, since {@code display.getWidth()}
     * returns framebuffer, not logical, size — see {@code EInkDisplayDriver.getWidth()}),
     * this would map a corner to a differently-shaped canvas and fail here even though
     * {@link EInkBridge#hasTouch()} alone would not catch it.
     */
    @Test
    void fromConfig_withProgrammaticTouch_mapperUsesLogicalNotFramebufferGeometry() {
        System.setProperty(DISPLAY_PROP, "png");
        System.setProperty(TOUCH_PROP, "programmatic");

        EInkBridge bridge = EInkBridge.fromConfig();

        assertNotNull(bridge.touchMapper());
        TouchPoint corner = new TouchPoint(0, bridge.displayWidth() - 1, bridge.displayHeight() - 1, true);
        TouchPoint mapped = bridge.touchMapper().map(corner);
        assertEquals(corner.x(), mapped.x());
        assertEquals(corner.y(), mapped.y());
    }
}
