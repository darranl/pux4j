// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import org.junit.jupiter.api.Test;

import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PngEncoderTest {

    // Verifies the encoder's chunk framing (signature, IHDR fields, single IDAT, IEND) and
    // that the compressed pixel data round-trips byte-for-byte through Inflater — this is
    // pux4j-core's own pixel-exactness check, independent of pux4j-validation's PngDecoder
    // (which pux4j-core cannot depend on; see notes/project-plan.md Phase 6.3).
    @Test
    void encodesGrayscalePixelsRoundTripExactly() {
        int width = 5, height = 3;
        byte[] gray = new byte[width * height];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (i * 17); // distinct, non-repeating sample per pixel
        }

        byte[] png = PngEncoder.encodeGrayscale(gray, width, height);

        assertPngSignature(png);
        assertEquals(width, readInt(png, 16));   // IHDR width
        assertEquals(height, readInt(png, 20));  // IHDR height
        assertEquals(8, png[24] & 0xFF);         // bit depth
        assertEquals(0, png[25] & 0xFF);         // colour type: greyscale

        byte[] idat = extractChunk(png, "IDAT");
        byte[] rows = inflate(idat, (width + 1) * height);

        for (int row = 0; row < height; row++) {
            int rowStart = row * (width + 1);
            assertEquals(0, rows[rowStart], "filter byte must be None (0) for row " + row);
            byte[] rowSamples = new byte[width];
            System.arraycopy(rows, rowStart + 1, rowSamples, 0, width);
            byte[] expected = new byte[width];
            System.arraycopy(gray, row * width, expected, 0, width);
            assertArrayEquals(expected, rowSamples, "row " + row + " samples must match input exactly");
        }
    }

    // A deliberately non-tiny image (larger than one Deflater/Inflater internal buffer) —
    // this is the exact case the original single-shot (unlooped) PngDecoder.inflate() call
    // silently truncated on before that bug was found during 6.3; the encoder's own inflate
    // loop needs the same coverage in the opposite direction so a future regression there
    // fails a test instead of only showing up as a corrupt PNG on disk.
    @Test
    void encodesLargeImageWithoutTruncation() {
        int width = 250, height = 122;
        byte[] gray = new byte[width * height];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (i % 256);
        }

        byte[] png = PngEncoder.encodeGrayscale(gray, width, height);
        byte[] idat = extractChunk(png, "IDAT");
        byte[] rows = inflate(idat, (width + 1) * height);

        for (int row = 0; row < height; row++) {
            int rowStart = row * (width + 1);
            byte[] rowSamples = new byte[width];
            System.arraycopy(rows, rowStart + 1, rowSamples, 0, width);
            byte[] expected = new byte[width];
            System.arraycopy(gray, row * width, expected, 0, width);
            assertArrayEquals(expected, rowSamples, "row " + row + " must survive a full-size encode");
        }
    }

    private static void assertPngSignature(byte[] png) {
        byte[] expected = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        byte[] actual = new byte[8];
        System.arraycopy(png, 0, actual, 0, 8);
        assertArrayEquals(expected, actual, "PNG signature must be present");
    }

    private static byte[] extractChunk(byte[] png, String type) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String chunkType = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (chunkType.equals(type)) {
                byte[] data = new byte[len];
                System.arraycopy(png, pos + 8, data, 0, len);
                return data;
            }
            pos += 8 + len + 4;
        }
        throw new AssertionError("Chunk " + type + " not found");
    }

    private static byte[] inflate(byte[] compressed, int expectedLength) {
        try {
            var inflater = new Inflater();
            inflater.setInput(compressed);
            byte[] out = new byte[expectedLength];
            int off = 0;
            while (off < out.length && !inflater.finished()) {
                off += inflater.inflate(out, off, out.length - off);
            }
            inflater.end();
            assertEquals(expectedLength, off, "inflated length must match expected scanline size");
            return out;
        } catch (Exception e) {
            throw new AssertionError("inflate failed", e);
        }
    }

    private static int readInt(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24)
            | ((data[off + 1] & 0xFF) << 16)
            | ((data[off + 2] & 0xFF) << 8)
            | (data[off + 3] & 0xFF);
    }
}
