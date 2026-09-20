// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

public enum TransformStrategy {
    /**
     * Grey >= 128 → white; &lt; 128 → black. Fast, no dithering. Requires MONOCHROME. Default:
     * error-diffusion strategies (FLOYD_STEINBERG) spread a local content change across the rest
     * of the frame in scan order, defeating partial refresh by forcing a full refresh on nearly
     * every update.
     */
    THRESHOLD,
    /**
     * Error-diffusion dithering. Best quality for photographic MONOCHROME content, but each
     * pixel's quantisation error carries into its neighbours, so a single-word text change
     * re-dithers everything downstream — see {@link #THRESHOLD} for why that costs partial
     * refresh. Not the default.
     */
    FLOYD_STEINBERG,
    /** Ordered (Bayer) 2×2 dithering. Faster than FLOYD_STEINBERG. Requires MONOCHROME. */
    ORDERED_2X2,
    /** 4-level grey binning. Requires FOUR_GRAY. Boundaries tunable via RenderOptions. */
    FOUR_GRAY_BINNING
}
