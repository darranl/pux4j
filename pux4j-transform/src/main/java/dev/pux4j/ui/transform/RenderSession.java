// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Stateful per-display render session. Holds the previous frame for diff and tracks
 * the consecutive partial refresh count.
 * Not thread-safe — the caller serialises access.
 * Stub implementation — logic to be added in Phase 5.
 */
public final class RenderSession {

    private final DisplayCapabilities caps;
    private final RenderOptions       options;

    private RenderSession(DisplayCapabilities caps, RenderOptions options) {
        this.caps    = caps;
        this.options = options;
    }

    public static RenderSession create(DisplayCapabilities caps, RenderOptions options) {
        validate(caps, options);
        return new RenderSession(caps, options);
    }

    /**
     * Transform the RGBA buffer and diff against the previous frame.
     * Returns empty if nothing changed; returns present with the work item otherwise.
     */
    public Optional<RenderResult> prepare(MemorySegment rgba, int width, int height) {
        throw new UnsupportedOperationException("RenderSession.prepare not yet implemented — Phase 5");
    }

    /** Discard frame history after a hardware reset or sleep/wake cycle. */
    public void reset() {}

    private static void validate(DisplayCapabilities caps, RenderOptions options) {
        PixelFormat fmt      = options.pixelFormat();
        TransformStrategy st = options.strategy();

        if (!caps.supports(fmt)) {
            throw new IllegalArgumentException(
                "Display does not support pixel format " + fmt);
        }
        boolean monoStrategy = st == TransformStrategy.THRESHOLD
            || st == TransformStrategy.FLOYD_STEINBERG
            || st == TransformStrategy.ORDERED_2X2;
        boolean fourGrayStrategy = st == TransformStrategy.FOUR_GRAY_BINNING;

        if (fmt == PixelFormat.MONOCHROME && fourGrayStrategy) {
            throw new IllegalArgumentException(
                "FOUR_GRAY_BINNING strategy requires FOUR_GRAY pixel format");
        }
        if (fmt == PixelFormat.FOUR_GRAY && monoStrategy) {
            throw new IllegalArgumentException(
                "Strategy " + st + " requires MONOCHROME pixel format");
        }
    }
}
