// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.PixelFormat;

import java.util.Arrays;

/**
 * Configuration for a {@link RenderSession}: pixel format, transform strategy,
 * and all refresh strategy tuning parameters.
 */
public final class RenderOptions {

    private static final float[] DEFAULT_FOUR_GRAY_BOUNDARIES = {64f, 128f, 192f};

    /**
     * The single source of truth for the default 4-gray classification boundaries — used by
     * {@link Builder}'s own default and by {@link PixelTransforms}' 4-argument
     * {@code transform} overload, so the two can never drift apart. See {@code RenderOptions}'s
     * class doc.
     */
    static float[] defaultFourGrayBinBoundaries() {
        return DEFAULT_FOUR_GRAY_BOUNDARIES.clone();
    }

    private final PixelFormat       pixelFormat;
    private final TransformStrategy strategy;
    private final int               maxPartials;
    private final int               maxRegions;
    private final float             fullRefreshThreshold;
    private final int               mergeThresholdPx;
    private final float[]           fourGrayBinBoundaries;
    private final boolean           preferFastRefresh;

    private RenderOptions(Builder b) {
        this.pixelFormat          = b.pixelFormat;
        this.strategy             = b.strategy;
        this.maxPartials          = b.maxPartials;
        this.maxRegions           = b.maxRegions;
        this.fullRefreshThreshold = b.fullRefreshThreshold;
        this.mergeThresholdPx     = b.mergeThresholdPx;
        this.fourGrayBinBoundaries = b.fourGrayBinBoundaries.clone();
        this.preferFastRefresh    = b.preferFastRefresh;
    }

    public PixelFormat       pixelFormat()          { return pixelFormat;          }
    public TransformStrategy strategy()             { return strategy;             }
    public int               maxPartials()          { return maxPartials;          }
    public int               maxRegions()           { return maxRegions;           }
    public float             fullRefreshThreshold() { return fullRefreshThreshold; }
    public int               mergeThresholdPx()     { return mergeThresholdPx;     }
    public float[]           fourGrayBinBoundaries(){ return fourGrayBinBoundaries.clone(); }
    public boolean           preferFastRefresh()    { return preferFastRefresh;    }

    public static RenderOptions monochromeDefaults() {
        return builder()
            .pixelFormat(PixelFormat.MONOCHROME)
            .strategy(TransformStrategy.THRESHOLD)
            .build();
    }

    public static RenderOptions fourGrayDefaults() {
        return builder()
            .pixelFormat(PixelFormat.FOUR_GRAY)
            .strategy(TransformStrategy.FOUR_GRAY_BINNING)
            .build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private PixelFormat       pixelFormat          = PixelFormat.MONOCHROME;
        private TransformStrategy strategy             = TransformStrategy.THRESHOLD;
        private int               maxPartials          = 5;
        private int               maxRegions           = 1;
        private float             fullRefreshThreshold = 0.5f;
        private int               mergeThresholdPx     = 10;
        private float[]           fourGrayBinBoundaries = DEFAULT_FOUR_GRAY_BOUNDARIES.clone();
        private boolean           preferFastRefresh    = false;

        private Builder() {}

        public Builder pixelFormat(PixelFormat v)          { pixelFormat = v;          return this; }
        public Builder strategy(TransformStrategy v)       { strategy = v;             return this; }
        public Builder maxPartials(int v)                  { maxPartials = v;          return this; }
        public Builder maxRegions(int v)                   { maxRegions = v;           return this; }
        public Builder fullRefreshThreshold(float v)       { fullRefreshThreshold = v; return this; }
        public Builder mergeThresholdPx(int v)             { mergeThresholdPx = v;     return this; }
        public Builder fourGrayBinBoundaries(float[] v)   { fourGrayBinBoundaries = v.clone(); return this; }
        public Builder preferFastRefresh(boolean v)        { preferFastRefresh = v;    return this; }

        /**
         * Validates all tuning parameters before construction, so a mistake here is a
         * {@code build()}-time failure at the call site that made it — not a deferred one
         * surfacing days later, out of context, from deep inside {@link FrameDiff} or
         * {@link PixelTransforms} on whatever {@code prepare()} call first exercises the
         * bad value (e.g. {@code maxRegions(0)} would otherwise only fail on the second
         * {@code prepare()} call, since the first always takes the unconditional-FULL path).
         */
        public RenderOptions build() {
            if (maxRegions < 1) {
                throw new IllegalArgumentException("maxRegions must be >= 1, got " + maxRegions);
            }
            if (maxPartials < 1) {
                throw new IllegalArgumentException("maxPartials must be >= 1, got " + maxPartials);
            }
            if (mergeThresholdPx < 0) {
                throw new IllegalArgumentException("mergeThresholdPx must be >= 0, got " + mergeThresholdPx);
            }
            if (fullRefreshThreshold < 0f || fullRefreshThreshold > 1f) {
                throw new IllegalArgumentException(
                    "fullRefreshThreshold must be in [0, 1], got " + fullRefreshThreshold);
            }
            if (fourGrayBinBoundaries.length != 3) {
                throw new IllegalArgumentException(
                    "fourGrayBinBoundaries must have exactly 3 values [lo, mid, hi], got "
                        + fourGrayBinBoundaries.length);
            }
            if (!(fourGrayBinBoundaries[0] < fourGrayBinBoundaries[1]
                    && fourGrayBinBoundaries[1] < fourGrayBinBoundaries[2])) {
                throw new IllegalArgumentException(
                    "fourGrayBinBoundaries must be strictly ascending [lo, mid, hi], got "
                        + Arrays.toString(fourGrayBinBoundaries));
            }
            return new RenderOptions(this);
        }
    }
}
