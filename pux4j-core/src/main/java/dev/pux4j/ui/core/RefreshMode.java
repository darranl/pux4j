// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

public enum RefreshMode {
    /** Full waveform refresh; clears ghosting. SSD1680 ~2 s, SSD1675A ~3 s. */
    FULL,
    /** Fast refresh via built-in temperature sensor. SSD1680 only, ~0.3 s. */
    FAST,
    /** Partial/fast refresh. Both ICs supported. ~300 ms; no ghosting clear. */
    PARTIAL
}
