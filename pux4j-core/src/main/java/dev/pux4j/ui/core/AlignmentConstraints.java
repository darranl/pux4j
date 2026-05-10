package dev.pux4j.ui.core;

/**
 * Partial refresh region alignment requirements for a display IC.
 * Both SSD1680 and SSD1675A require X start and end coordinates to be
 * multiples of 8 (one byte covers 8 pixels per row).
 */
public record AlignmentConstraints(int xStepPx) {}
