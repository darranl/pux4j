// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Named display profiles for the emulator, keyed by the {@code pux4j.emulator.display}
 * system property. Each profile declares the on-screen (logical/landscape) dimensions,
 * physical mounting orientation, and capabilities of a specific physical display model.
 *
 * <p>{@code logicalWidth}/{@code logicalHeight} are the panel's marketed landscape
 * resolution (e.g. 296x128 for the 2.9"), matching what a user sees on screen. The
 * underlying chip's native framebuffer is always portrait-shaped (narrow x tall — see
 * {@code Ssd1680DisplayDriver.WIDTH}/{@code HEIGHT} in {@code pux4j-driver-hat-2in13v4}) and
 * relies on physical panel mounting to appear landscape; the emulator has no physical
 * mounting, so {@link EmulatedEInkDisplay} performs that rotation itself using
 * {@code orientation}, matching each profile's real hardware orientation exactly
 * ({@code dist-hat-2in9v2}/{@code dist-hat-2in13v4} in {@code pux4j-validation/pom.xml}).
 */
enum EmulatorDisplayProfile {

    SSD1675A("ssd1675a", "SSD1675A (2.9\" V2)", 296, 128, Orientation.LANDSCAPE,
        EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL)),

    SSD1680("ssd1680", "SSD1680 (2.13\" V4)", 250, 122, Orientation.LANDSCAPE_INVERTED,
        EnumSet.of(PixelFormat.MONOCHROME),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL));

    private static final Map<String, EmulatorDisplayProfile> BY_NAME =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(p -> p.profileName, p -> p));

    final String profileName;
    final String displayLabel;
    final int logicalWidth;
    final int logicalHeight;
    final Orientation orientation;
    final EnumSet<PixelFormat> formats;
    final EnumSet<RefreshMode> modes;

    EmulatorDisplayProfile(String profileName, String displayLabel,
                            int logicalWidth, int logicalHeight, Orientation orientation,
                            EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this.profileName   = profileName;
        this.displayLabel  = displayLabel;
        this.logicalWidth  = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.orientation   = orientation;
        this.formats       = formats;
        this.modes         = modes;
    }

    /** Native (portrait-shaped) chip framebuffer width — see the class doc. */
    int nativeWidth() {
        return switch (orientation) {
            case LANDSCAPE, LANDSCAPE_INVERTED -> logicalHeight;
            case PORTRAIT, PORTRAIT_INVERTED   -> logicalWidth;
        };
    }

    /** Native (portrait-shaped) chip framebuffer height — see the class doc. */
    int nativeHeight() {
        return switch (orientation) {
            case LANDSCAPE, LANDSCAPE_INVERTED -> logicalWidth;
            case PORTRAIT, PORTRAIT_INVERTED   -> logicalHeight;
        };
    }

    static EmulatorDisplayProfile forName(String name) {
        var profile = BY_NAME.get(name);
        if (profile == null) {
            throw new IllegalStateException(
                "Unknown emulator display profile '" + name + "'. Valid profiles: " + BY_NAME.keySet());
        }
        return profile;
    }
}
