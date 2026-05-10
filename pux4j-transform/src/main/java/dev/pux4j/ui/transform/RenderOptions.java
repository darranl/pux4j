package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.PixelFormat;

import java.util.Arrays;

/**
 * Configuration for a {@link RenderSession}: pixel format, transform strategy,
 * and all refresh strategy tuning parameters.
 */
public final class RenderOptions {

    private static final float[] DEFAULT_FOUR_GRAY_BOUNDARIES = {64f, 128f, 192f};

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
            .strategy(TransformStrategy.FLOYD_STEINBERG)
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
        private TransformStrategy strategy             = TransformStrategy.FLOYD_STEINBERG;
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

        public RenderOptions build() { return new RenderOptions(this); }
    }
}
