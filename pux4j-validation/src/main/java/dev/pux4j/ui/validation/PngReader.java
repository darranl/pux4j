// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Minimal PNG decoder using java.util.zip.Inflater — no AWT, safe for GraalVM CE native image.
final class PngReader {

    private static final Logger log = LoggerFactory.getLogger(PngReader.class);

    record PngImage(int[] pixels, int width, int height) {}

    static Optional<PngImage> read(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        byte[] data = buf.toByteArray();

        if (data.length < 8) return Optional.empty();

        // Verify PNG signature: 137 80 78 71 13 10 26 10
        long sig = 0x89504E470D0A1A0AL;
        long actual = 0;
        for (int i = 0; i < 8; i++) actual = (actual << 8) | (data[i] & 0xFF);
        if (actual != sig) return Optional.empty();

        int width = 0, height = 0, bitDepth = 0, colorType = 0;
        var idatBuf = new ByteArrayOutputStream();
        int pos = 8;

        while (pos + 12 <= data.length) {
            int len = readInt(data, pos);
            int type = readInt(data, pos + 4);
            pos += 8;
            if (type == 0x49484452) { // IHDR
                width = readInt(data, pos);
                height = readInt(data, pos + 4);
                bitDepth = data[pos + 8] & 0xFF;
                colorType = data[pos + 9] & 0xFF;
            } else if (type == 0x49444154) { // IDAT
                idatBuf.write(data, pos, len);
            } else if (type == 0x49454E44) { // IEND
                break;
            }
            pos += len + 4; // data + CRC
        }

        if (bitDepth != 8) {
            log.warn("PngReader: unsupported bit depth {}", bitDepth);
            return Optional.empty();
        }
        int channels = switch (colorType) {
            case 2 -> 3;  // RGB
            case 6 -> 4;  // RGBA
            default -> {
                log.warn("PngReader: unsupported color type {}", colorType);
                yield -1;
            }
        };
        if (channels == -1) return Optional.empty();

        byte[] compressed = idatBuf.toByteArray();
        int stride = width * channels + 1; // +1 for filter byte
        byte[] raw = new byte[stride * height];
        try {
            var inflater = new Inflater();
            inflater.setInput(compressed);
            inflater.inflate(raw);
            inflater.end();
        } catch (DataFormatException e) {
            log.warn("PngReader: inflate failed", e);
            return Optional.empty();
        }

        // Reconstruct with PNG filters (RFC 2083 section 6.3)
        byte[] prior = new byte[stride];
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            int filter = raw[rowStart] & 0xFF;
            for (int x = 1; x < stride; x++) {
                int i = rowStart + x;
                int a = x > channels ? (raw[i - channels] & 0xFF) : 0;
                int b = prior[x] & 0xFF;
                int c = x > channels ? (prior[x - channels] & 0xFF) : 0;
                int orig = raw[i] & 0xFF;
                raw[i] = (byte) switch (filter) {
                    case 0 -> orig;
                    case 1 -> orig + a;
                    case 2 -> orig + b;
                    case 3 -> orig + (a + b) / 2;
                    case 4 -> orig + paeth(a, b, c);
                    default -> orig;
                };
            }
            System.arraycopy(raw, rowStart, prior, 0, stride);
            for (int x = 0; x < width; x++) {
                int base = rowStart + 1 + x * channels;
                int rv = raw[base] & 0xFF;
                int gv = raw[base + 1] & 0xFF;
                int bv = raw[base + 2] & 0xFF;
                int av = channels == 4 ? (raw[base + 3] & 0xFF) : 255;
                pixels[y * width + x] = (av << 24) | (rv << 16) | (gv << 8) | bv;
            }
        }
        return Optional.of(new PngImage(pixels, width, height));
    }

    // Scale src to (dstW × dstH) using nearest-neighbour and threshold to monochrome.
    // Transparent pixels (alpha ≤ 20) → white; luminance ≥ 190 → white; else black.
    static int[] toMonochrome(PngImage src, int dstW, int dstH) {
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
    static int[] toHighContrastMonochrome(PngImage src, int dstW, int dstH) {
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

    private static int readInt(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24)
            | ((data[off + 1] & 0xFF) << 16)
            | ((data[off + 2] & 0xFF) << 8)
            | (data[off + 3] & 0xFF);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }
}
