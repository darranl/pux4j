// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

public enum RefreshMode {
    /** Full waveform refresh with visible flash; clears ghosting. SSD1675A ~3 s, SSD1680 ~2 s. */
    FULL,
    /**
     * Fast full refresh using a custom waveform LUT — no visible flash, faster than FULL.
     * SSD1675A ~1 s using WF_FULL LUT (Init_Fast path). SSD1680 uses built-in temperature sensor.
     * Clears pixel-voltage drift like FULL, making it suitable for whole-screen transitions
     * where a flashing refresh would look jarring.
     */
    FAST,
    /** Partial refresh — only drives changed pixels. ~300 ms; no ghosting clear. */
    PARTIAL
}

