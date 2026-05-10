package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.FrameData;

import java.util.List;

/**
 * Stateless diff between two FrameData instances.
 * Returns changed regions snapped to {@link DisplayCapabilities#partialAlignment()}.
 * Stub implementation — diff logic to be added in Phase 5.
 */
public final class FrameDiff {

    private FrameDiff() {}

    public static List<Region> diff(FrameData previous, FrameData next, DisplayCapabilities caps) {
        throw new UnsupportedOperationException("FrameDiff.diff not yet implemented — Phase 5");
    }
}
