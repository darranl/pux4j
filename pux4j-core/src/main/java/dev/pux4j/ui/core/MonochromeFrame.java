// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * 1-bit packed pixel data for full, fast, or partial refresh.
 * Bit encoding: 0 = black, 1 = white. MSB of each byte is the leftmost pixel
 * in its row. Row-major, packed 1 bit per pixel.
 * Required byte count: {@code ceil(logicalWidth * logicalHeight / 8)}.
 */
public record MonochromeFrame(byte[] data, RefreshMode mode) implements FrameData {}
