// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import java.util.Optional;
import java.util.Set;

/**
 * Static capability descriptor for a display IC, returned by
 * {@link EInkDisplayDriver#getCapabilities()}.
 *
 * @param supportedFormats      pixel formats this IC can accept
 * @param supportedRefreshModes refresh modes this IC supports
 * @param hasBusySignal         {@code true} if the driver can observe the BUSY pin
 *                              to determine when a refresh has completed
 * @param partialAlignment      alignment constraints for partial-refresh regions,
 *                              or empty if partial refresh is not supported
 */
public record DisplayCapabilities(
    Set<PixelFormat>               supportedFormats,
    Set<RefreshMode>               supportedRefreshModes,
    boolean                        hasBusySignal,
    Optional<AlignmentConstraints> partialAlignment
) {
    /**
     * Returns {@code true} if this display IC accepts frames in the given pixel format.
     *
     * @param format the format to test
     */
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
