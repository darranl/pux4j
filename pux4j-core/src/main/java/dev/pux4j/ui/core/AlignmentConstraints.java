// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Partial refresh region alignment requirements for a display IC.
 * Both SSD1680 and SSD1675A require X start and end coordinates to be
 * multiples of 8 (one byte covers 8 pixels per row).
 *
 * @param xStepPx required pixel granularity for the X start and end coordinates
 *                of any partial-refresh region (typically 8)
 */
public record AlignmentConstraints(int xStepPx) {}
