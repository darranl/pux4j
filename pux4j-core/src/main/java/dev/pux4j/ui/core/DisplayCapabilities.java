package dev.pux4j.ui.core;

import java.util.Optional;
import java.util.Set;

public record DisplayCapabilities(
    Set<PixelFormat>               supportedFormats,
    Set<RefreshMode>               supportedRefreshModes,
    boolean                        hasBusySignal,
    Optional<AlignmentConstraints> partialAlignment
) {
    public boolean supports(PixelFormat format) {
        return supportedFormats.contains(format);
    }

    /**
     * Highest-quality format this display supports, in preference order:
     * FOUR_GRAY > SEVEN_COLOR > RGB565 > MONOCHROME.
     */
    public PixelFormat preferredFormat() {
        for (PixelFormat candidate : new PixelFormat[]{
                PixelFormat.FOUR_GRAY, PixelFormat.SEVEN_COLOR,
                PixelFormat.RGB565,    PixelFormat.MONOCHROME}) {
            if (supportedFormats.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No supported pixel formats declared");
    }
}
