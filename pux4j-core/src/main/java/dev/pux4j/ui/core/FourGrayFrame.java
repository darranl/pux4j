// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Dual-plane 4-gray pixel data for SSD1675A, in native framebuffer space (see
 * {@code OrientationMapping} — not the logical/on-screen dimensions). Always triggers a full
 * refresh — the IC has no partial-refresh path for 4-gray content, so this type carries no
 * {@link RefreshMode}.
 * Shade lookup: (bwPlane=1, redPlane=1)=white, (1,0)=light grey, (0,1)=dark grey, (0,0)=black.
 * Required byte count per plane: {@code ceil(framebufferWidth / 8) * framebufferHeight}, same
 * row-major MSB-leftmost packing as {@link MonochromeFrame}.
 */
public record FourGrayFrame(byte[] bwPlane, byte[] redPlane) implements FrameData {}
