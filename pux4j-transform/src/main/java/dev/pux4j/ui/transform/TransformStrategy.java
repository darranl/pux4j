package dev.pux4j.ui.transform;

public enum TransformStrategy {
    /** Grey >= 128 → white; < 128 → black. Fast, no dithering. Requires MONOCHROME. */
    THRESHOLD,
    /** Error-diffusion dithering. Best quality for MONOCHROME. Default. */
    FLOYD_STEINBERG,
    /** Ordered (Bayer) 2×2 dithering. Faster than FLOYD_STEINBERG. Requires MONOCHROME. */
    ORDERED_2X2,
    /** 4-level grey binning. Requires FOUR_GRAY. Boundaries tunable via RenderOptions. */
    FOUR_GRAY_BINNING
}
