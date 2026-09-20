// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

/**
 * Rectangular region in native framebuffer space (not logical — see
 * {@code tier2-transform.md}'s "Coordinate spaces" section).
 *
 * <p>{@code x} is always a multiple of the display's {@code xStepPx} (8): both
 * {@code Ssd1680DisplayDriver.writeRegion} and the emulator require it. {@code width} is
 * <strong>not</strong> guaranteed to be a multiple of 8 — clamping a region to the framebuffer's
 * actual (possibly non-8-multiple) width, e.g. 122, can shorten it below the next byte boundary.
 */
public record Region(int x, int y, int width, int height) {}
