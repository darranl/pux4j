// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal pure-format PNG encoder using {@link java.util.zip.Deflater} — no AWT/ImageIO, safe
 * for GraalVM CE native image ({@code pux4j-core} sits on every native-image build's
 * classpath). Writes 8-bit greyscale (PNG colour type 0), one scanline filter byte (0 = None)
 * per row, one {@code IDAT} chunk. This is a pure format encoder with no eInk-specific
 * knowledge; {@code PngEInkDisplay} decides what the pixels mean.
 */
final class PngEncoder {

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngEncoder() {}

    /**
     * Encodes {@code gray} (row-major, one 0-255 sample per pixel, {@code width*height} long)
     * as an 8-bit greyscale PNG.
     */
    static byte[] encodeGrayscale(byte[] gray, int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        writeChunk(out, "IHDR", ihdr(width, height));
        writeChunk(out, "IDAT", deflate(scanlines(gray, width, height)));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] ihdr(int width, int height) {
        var b = new ByteArrayOutputStream();
        writeInt(b, width);
        writeInt(b, height);
        b.write(8);  // bit depth
        b.write(0);  // colour type: greyscale
        b.write(0);  // compression method: deflate
        b.write(0);  // filter method: adaptive (per-scanline filter byte)
        b.write(0);  // interlace method: none
        return b.toByteArray();
    }

    // Prefixes each row with filter-type byte 0 (None) — the encoder always emits raw
    // samples; there's no benefit to the other PNG filter types for eInk-derived content
    // (large flat regions of a handful of shades), and it keeps this encoder simple.
    private static byte[] scanlines(byte[] gray, int width, int height) {
        byte[] raw = new byte[(width + 1) * height];
        for (int row = 0; row < height; row++) {
            int rawStart = row * (width + 1);
            raw[rawStart] = 0; // filter: None
            System.arraycopy(gray, row * width, raw, rawStart + 1, width);
        }
        return raw;
    }

    private static byte[] deflate(byte[] raw) {
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        var out = new ByteArrayOutputStream(raw.length / 2 + 64);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
