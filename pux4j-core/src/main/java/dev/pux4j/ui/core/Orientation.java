// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Physical viewing orientation of the display panel.
 *
 * <p>LANDSCAPE and LANDSCAPE_INVERTED produce a logical canvas that is wider than tall
 * (width &gt; height). PORTRAIT and PORTRAIT_INVERTED produce a canvas that is taller
 * than wide (height &gt; width).
 *
 * <p>The driver reports its configured orientation via
 * {@link EInkDisplayDriver#getOrientation()}.
 */
public enum Orientation {
    /** Display viewed in landscape — wider than tall. No content rotation required. */
    LANDSCAPE,
    /** Display viewed in landscape, physically mounted upside-down (180° rotation). */
    LANDSCAPE_INVERTED,
    /** Display viewed in portrait — taller than wide. Content is rotated 90° CW. */
    PORTRAIT,
    /** Display viewed in portrait, physically mounted upside-down (270° CW rotation). */
    PORTRAIT_INVERTED
}
