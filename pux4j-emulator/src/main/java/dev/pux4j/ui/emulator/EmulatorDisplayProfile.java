// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Named display profiles for the emulator, keyed by the {@code pux4j.emulator.display}
 * system property. Each profile declares the logical dimensions and capabilities of a
 * specific physical display model.
 */
enum EmulatorDisplayProfile {

    SSD1675A("ssd1675a", "SSD1675A (2.9\" V2)", 296, 128,
        EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL)),

    SSD1680("ssd1680", "SSD1680 (2.13\" V4)", 250, 122,
        EnumSet.of(PixelFormat.MONOCHROME),
        EnumSet.of(RefreshMode.FULL, RefreshMode.FAST, RefreshMode.PARTIAL));

    private static final Map<String, EmulatorDisplayProfile> BY_NAME =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(p -> p.profileName, p -> p));

    final String profileName;
    final String displayLabel;
    final int width;
    final int height;
    final EnumSet<PixelFormat> formats;
    final EnumSet<RefreshMode> modes;

    EmulatorDisplayProfile(String profileName, String displayLabel, int width, int height,
                            EnumSet<PixelFormat> formats, EnumSet<RefreshMode> modes) {
        this.profileName  = profileName;
        this.displayLabel = displayLabel;
        this.width        = width;
        this.height       = height;
        this.formats      = formats;
        this.modes        = modes;
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
