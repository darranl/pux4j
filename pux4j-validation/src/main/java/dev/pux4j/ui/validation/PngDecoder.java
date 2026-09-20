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

/**
 * Minimal pure-format PNG decoder using {@link java.util.zip.Inflater} — no AWT, safe for
 * GraalVM CE native image. Decodes a PNG byte stream into a generic {@link PngImage} (an ARGB
 * pixel array); it has no eInk-specific knowledge of its own. Callers that need to turn a
 * decoded image into an eInk-displayable bitmap use {@link IconRasterizer}, which is a
 * separate concern — this class only ever answers "what does this PNG file contain".
 */
final class PngDecoder {

    private static final Logger log = LoggerFactory.getLogger(PngDecoder.class);

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
            log.warn("PngDecoder: unsupported bit depth {}", bitDepth);
            return Optional.empty();
        }
        // channel count per pixel for each supported PNG color type (bit depth 8 only):
        // 0 = greyscale, 2 = RGB, 6 = RGBA. Greyscale support exists specifically so this
        // decoder can read back the frame-snapshot PNGs pux4j-core's PngEInkDisplay writes
        // (see notes/project-plan.md Phase 6.3), not just downloaded colour icon assets.
        int channels = switch (colorType) {
            case 0 -> 1;  // greyscale
            case 2 -> 3;  // RGB
            case 6 -> 4;  // RGBA
            default -> {
                log.warn("PngDecoder: unsupported color type {}", colorType);
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
            int off = 0;
            while (off < raw.length && !inflater.finished()) {
                int written = inflater.inflate(raw, off, raw.length - off);
                if (written == 0 && inflater.needsInput()) break;
                off += written;
            }
            inflater.end();
        } catch (DataFormatException e) {
            log.warn("PngDecoder: inflate failed", e);
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
                int rv, gv, bv, av;
                if (channels == 1) {
                    rv = gv = bv = raw[base] & 0xFF;
                    av = 255;
                } else {
                    rv = raw[base] & 0xFF;
                    gv = raw[base + 1] & 0xFF;
                    bv = raw[base + 2] & 0xFF;
                    av = channels == 4 ? (raw[base + 3] & 0xFF) : 255;
                }
                pixels[y * width + x] = (av << 24) | (rv << 16) | (gv << 8) | bv;
            }
        }
        return Optional.of(new PngImage(pixels, width, height));
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
