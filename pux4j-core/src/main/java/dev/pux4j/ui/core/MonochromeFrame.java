// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * 1-bit packed pixel data for full, fast, or partial refresh, in native framebuffer space
 * (see {@code OrientationMapping} — not the logical/on-screen dimensions).
 * Bit encoding: 0 = black, 1 = white. MSB of each byte is the leftmost pixel
 * in its row. Row-major, packed 1 bit per pixel.
 * Required byte count for a full frame: {@code ceil(framebufferWidth / 8) * framebufferHeight}
 * (e.g. 16 * 250 = 4000 for the SSD1680's 122x250 native framebuffer). A partial-write frame
 * uses the same formula against the region's own width/height instead.
 */
public record MonochromeFrame(byte[] data, RefreshMode mode) implements FrameData {}
