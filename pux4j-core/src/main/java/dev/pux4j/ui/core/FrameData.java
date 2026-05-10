// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Packed pixel data ready to write to a display IC.
 * The sealed type makes invalid format/mode combinations impossible to express:
 * {@link MonochromeFrame} carries a {@link RefreshMode}; {@link FourGrayFrame} has no
 * refresh mode because its refresh sequence is fixed by the IC.
 */
public sealed interface FrameData permits MonochromeFrame, FourGrayFrame {}
