package dev.pux4j.ui.core;

/**
 * A single touch contact read from the touch IC.
 * Coordinates are in touch IC native space — NOT mapped to display logical space.
 * Mapping to logical space is handled by {@link TouchCoordinateMapper}.
 */
public record TouchPoint(
    int     id,
    int     x,
    int     y,
    boolean down
) {}
