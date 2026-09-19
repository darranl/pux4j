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
 * system property. Each profile declares the underlying chip's native framebuffer size,
 * physical mounting orientation, and capabilities of a specific physical display model.
 *
 * <p>{@code nativeWidth}/{@code nativeHeight} are the chip's actual native framebuffer
 * dimensions (always portrait-shaped — narrow x tall — matching
 * {@code Ssd1680DisplayDriver.WIDTH}/{@code HEIGHT} and
 * {@code Ssd1675aDisplayDriver.WIDTH}/{@code HEIGHT} in the corresponding hat driver modules
 * exactly), not the panel's marketed landscape resolution. Every real
 * {@link dev.pux4j.ui.core.EInkDisplayDriver#getWidth()}/{@code getHeight()} reports these
 * same native dimensions; the panel only appears landscape because of physical mounting. The
 * emulator has no physical mounting, so {@link EmulatedEInkDisplay} performs that rotation
 * itself using {@code orientation} (via {@code OrientationMapping} in {@code pux4j-core}),
 * matching each profile's real hardware orientation exactly
 * ({@code dist-hat-2in9v2}/{@code dist-hat-2in13v4} in {@code pux4j-validation/pom.xml}).
 * Storing native dimensions here — rather than the marketed resolution — means deriving the
 * on-screen size, if ever needed, goes through {@code OrientationMapping.of(...)} the same
 * direction every other caller uses it, with no separate reverse-direction lookup.
 */
enum EmulatorDisplayProfile {

    SSD1675A("ssd1675a", "SSD1675A (2.9\" V2)", 128, 296, Orientation.LANDSCAPE,
        EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL)),

    SSD1680("ssd1680", "SSD1680 (2.13\" V4)", 122, 250, Orientation.LANDSCAPE_INVERTED,
        EnumSet.of(PixelFormat.MONOCHROME),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL));

    private static final Map<String, EmulatorDisplayProfile> BY_NAME =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(p -> p.profileName, p -> p));

    final String profileName;
    final String displayLabel;
    final int nativeWidth;
    final int nativeHeight;
    final Orientation orientation;
    final EnumSet<PixelFormat> formats;
    final EnumSet<RefreshMode> modes;

    EmulatorDisplayProfile(String profileName, String displayLabel,
                            int nativeWidth, int nativeHeight, Orientation orientation,
                            EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this.profileName   = profileName;
        this.displayLabel  = displayLabel;
        this.nativeWidth   = nativeWidth;
        this.nativeHeight  = nativeHeight;
        this.orientation   = orientation;
        this.formats       = formats;
        this.modes         = modes;
    }

    /** Native (portrait-shaped) chip framebuffer width — see the class doc. */
    int nativeWidth() {
        return nativeWidth;
    }

    /** Native (portrait-shaped) chip framebuffer height — see the class doc. */
    int nativeHeight() {
        return nativeHeight;
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
