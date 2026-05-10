package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.PixelFormat;

import java.lang.foreign.MemorySegment;

/**
 * Stateless single-frame RGBA → FrameData conversion.
 * Stub implementation — conversion logic to be added in Phase 5.
 */
public final class PixelTransforms {

    private PixelTransforms() {}

    public static FrameData transform(MemorySegment rgba, int width, int height,
                                      PixelFormat format, TransformStrategy strategy) {
        throw new UnsupportedOperationException("PixelTransforms.transform not yet implemented — Phase 5");
    }
}
