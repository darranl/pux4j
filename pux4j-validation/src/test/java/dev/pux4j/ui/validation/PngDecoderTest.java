// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour-type-0 (8-bit greyscale) decode coverage — added for Phase 6.3 so {@link PngDecoder}
 * can read back the frame-snapshot PNGs {@code pux4j-core}'s {@code PngEInkDisplay} writes, not
 * just the RGB/RGBA colour icon assets it originally handled. Builds its own tiny greyscale PNG
 * by hand (rather than depending on {@code pux4j-core}'s internal encoder, which this module
 * cannot see) so this test exercises only {@link PngDecoder}.
 */
class PngDecoderTest {

    @Test
    void decodesEightBitGrayscale() throws Exception {
        int width = 2, height = 2;
        byte[] gray = { 0, 85, (byte) 170, (byte) 255 }; // top-left..bottom-right, row-major

        byte[] png = encodeMinimalGrayscalePng(gray, width, height);

        var decoded = PngDecoder.read(new ByteArrayInputStream(png));
        assertTrue(decoded.isPresent(), "grayscale PNG must decode");
        PngDecoder.PngImage image = decoded.get();

        assertEquals(width, image.width());
        assertEquals(height, image.height());
        // Grayscale expands to ARGB with R=G=B=sample and full alpha — the same shape
        // IconRasterizer/HardwareValidationTest already assume for RGB/RGBA input.
        assertEquals(0xFF000000, image.pixels()[0], "sample 0 -> black");
        assertEquals(0xFF555555, image.pixels()[1], "sample 85 -> rgb(85,85,85)");
        assertEquals(0xFFAAAAAA, image.pixels()[2], "sample 170 -> rgb(170,170,170)");
        assertEquals(0xFFFFFFFF, image.pixels()[3], "sample 255 -> white");
    }

    // Larger than a single Inflater output-buffer call typically produces in one shot —
    // this is the case the original single-call (unlooped) inflate() silently truncated on
    // before that bug was found during 6.3 (see notes/project-plan.md / diary.md).
    @Test
    void decodesGrayscaleLargerThanOneInflateChunk() throws Exception {
        int width = 250, height = 122;
        byte[] gray = new byte[width * height];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (i % 256);
        }

        byte[] png = encodeMinimalGrayscalePng(gray, width, height);
        var decoded = PngDecoder.read(new ByteArrayInputStream(png));

        assertTrue(decoded.isPresent());
        PngDecoder.PngImage image = decoded.get();
        assertEquals(width, image.width());
        assertEquals(height, image.height());
        for (int i = 0; i < gray.length; i++) {
            int expectedSample = gray[i] & 0xFF;
            int expectedArgb = 0xFF000000 | (expectedSample << 16) | (expectedSample << 8) | expectedSample;
            assertEquals(expectedArgb, image.pixels()[i], "pixel " + i + " must round-trip exactly");
        }
    }

    private static byte[] encodeMinimalGrayscalePng(byte[] gray, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });

        var ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(8);  // bit depth
        ihdr.write(0);  // colour type: greyscale
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        writeChunk(out, "IHDR", ihdr.toByteArray());

        byte[] raw = new byte[(width + 1) * height];
        for (int row = 0; row < height; row++) {
            int rowStart = row * (width + 1);
            raw[rowStart] = 0; // filter: None
            System.arraycopy(gray, row * width, raw, rowStart + 1, width);
        }
        var deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        var idat = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            idat.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        writeChunk(out, "IDAT", idat.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
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
