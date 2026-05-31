// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Physical orientation of the display panel relative to its native portrait layout.
 * The driver reports its configured orientation via {@link EInkDisplayDriver#getOrientation()},
 * and logical width/height swap accordingly when landscape modes are used.
 */
public enum Orientation {
    /** Native portrait orientation — no rotation applied. */
    PORTRAIT,
    /** 90° clockwise from portrait. */
    LANDSCAPE,
    /** 180° from portrait. */
    PORTRAIT_INVERTED,
    /** 270° clockwise from portrait. */
    LANDSCAPE_INVERTED
}
