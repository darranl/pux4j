// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

/**
 * Rasterizes an arbitrary-colour decoded PNG asset (icon, poster) down to a 2-tone bitmap an
 * eInk validation screen can display. This is asset-import scaling and luminance thresholding,
 * not PNG decoding — see {@link PngDecoder} for the format-decode step that produces the
 * {@link PngDecoder.PngImage} this class consumes.
 */
final class IconRasterizer {

    private IconRasterizer() {}

    // Scale src to (dstW × dstH) using nearest-neighbour and threshold to monochrome.
    // Transparent pixels (alpha ≤ 20) → white; luminance ≥ 190 → white; else black.
    static int[] toMonochrome(PngDecoder.PngImage src, int dstW, int dstH) {
        int[] out = new int[dstW * dstH];
        for (int dy = 0; dy < dstH; dy++) {
            int sy = dy * src.height() / dstH;
            for (int dx = 0; dx < dstW; dx++) {
                int sx = dx * src.width() / dstW;
                int argb = src.pixels()[sy * src.width() + sx];
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                out[dy * dstW + dx] = (a > 20 && lum < 190) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return out;
    }

    // Same as toMonochrome but treats a lower luminance threshold (128) for stark
    // high-contrast rendering of the completion screen poster image.
    static int[] toHighContrastMonochrome(PngDecoder.PngImage src, int dstW, int dstH) {
        int[] out = new int[dstW * dstH];
        for (int dy = 0; dy < dstH; dy++) {
            int sy = dy * src.height() / dstH;
            for (int dx = 0; dx < dstW; dx++) {
                int sx = dx * src.width() / dstW;
                int argb = src.pixels()[sy * src.width() + sx];
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                out[dy * dstW + dx] = (a > 20 && lum < 128) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return out;
    }
}
