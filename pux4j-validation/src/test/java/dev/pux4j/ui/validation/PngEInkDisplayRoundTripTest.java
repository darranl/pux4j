// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end round trip: {@code pux4j-core}'s {@code PngEInkDisplay} (reached only through the
 * public {@link DisplayDriverFactory} SPI — its concrete class is not exported) writes a real
 * PNG file, and this module's own {@link PngDecoder} — extended for greyscale during Phase 6.3
 * specifically so it could read this driver's own output, not just colour icon assets — reads
 * it back. Confirms the two halves this session designed together (PngEInkDisplay's encoder
 * output, PngDecoder's decoder input) actually agree byte-for-byte, across all four
 * orientations, the way {@code CanvasOrientationParityTest}/{@code PixelTransformsCanvasParityTest}
 * already do for the packing direction. See notes/project-plan.md Phase 6.3.
 */
class PngEInkDisplayRoundTripTest {

    // 11x19 (narrow x tall) — same fixture shape as CanvasOrientationParityTest/
    // OrientationMappingTest: >8 and not a multiple of 8, so row padding is exercised.
    private static final int NATIVE_W = 11;
    private static final int NATIVE_H = 19;

    @Test
    void writtenFrameDecodesToExactlyTheOriginalPixelsForEveryOrientation(@TempDir Path tmp) throws Exception {
        for (Orientation orientation : Orientation.values()) {
            Path outDir = tmp.resolve(orientation.name());
            byte[] nativeData = checkerboardPattern();

            DriverConfig config = DriverConfig.builder()
                .property("width", NATIVE_W)
                .property("height", NATIVE_H)
                .property("orientation", orientation.name())
                .property("outputDir", outDir.toString())
                .build();
            EInkDisplayDriver driver = DisplayDriverFactory.select("png").create(null, config);
            try (var closeable = (AutoCloseable) driver) {
                driver.writeFrame(new MonochromeFrame(nativeData, RefreshMode.FULL)).get();
            }

            Path written = outDir.resolve("frame-0001.png");
            assertTrue(Files.exists(written), orientation + ": frame-0001.png must exist");

            PngDecoder.PngImage image;
            try (var in = Files.newInputStream(written)) {
                var decoded = PngDecoder.read(in);
                assertTrue(decoded.isPresent(), orientation + ": PngDecoder must read PngEInkDisplay's own output");
                image = decoded.get();
            }

            OrientationMapping mapping = OrientationMapping.of(NATIVE_W, NATIVE_H, orientation);
            assertEquals(mapping.logicalWidth(), image.width(), orientation + ": PNG width");
            assertEquals(mapping.logicalHeight(), image.height(), orientation + ": PNG height");

            // For every native pixel, its expected shade (from the checkerboard pattern) must
            // appear at exactly the logical coordinate OrientationMapping predicts — this is
            // the actual round-trip assertion, not just "some image of the right size came back".
            for (int ny = 0; ny < NATIVE_H; ny++) {
                for (int nx = 0; nx < NATIVE_W; nx++) {
                    boolean expectWhite = isCheckerboardWhite(nx, ny);
                    int lx = mapping.logicalX(nx, ny);
                    int ly = mapping.logicalY(nx, ny);
                    int argb = image.pixels()[ly * image.width() + lx];
                    int expected = expectWhite ? 0xFFFFFFFF : 0xFF000000;
                    assertEquals(expected, argb,
                        orientation + ": native (" + nx + "," + ny + ") -> logical (" + lx + "," + ly + ")");
                }
            }
        }
    }

    private static boolean isCheckerboardWhite(int x, int y) {
        return (x + y) % 2 == 0;
    }

    // Row-major, MSB-leftmost 1-bit packing of the checkerboard pattern above, at NATIVE_W x
    // NATIVE_H (11x19 — row padding present, per MonochromeFrame's byte layout).
    private static byte[] checkerboardPattern() {
        int rowBytes = (NATIVE_W + 7) / 8;
        byte[] data = new byte[rowBytes * NATIVE_H];
        for (int y = 0; y < NATIVE_H; y++) {
            for (int x = 0; x < NATIVE_W; x++) {
                if (isCheckerboardWhite(x, y)) {
                    data[y * rowBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return data;
    }
}
