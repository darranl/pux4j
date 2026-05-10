// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

public enum PixelFormat {
    /** 1-bit packed — both current eInk ICs support this. */
    MONOCHROME,
    /** Dual-plane 1-bit — SSD1675A only. */
    FOUR_GRAY,
    /** 16-bit packed colour — LCD class. */
    RGB565,
    /** ACeP 4-bit indexed — colour eInk. */
    SEVEN_COLOR
}
