// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngEInkDisplayTest {

    // 5x9 native framebuffer — narrow x tall, matching every real native framebuffer's shape
    // (see OrientationMapping's class javadoc); width deliberately not a multiple of 8, same
    // reasoning as FrameRenderSupportTest: proves row-padding survives the whole write path,
    // not just decode.
    private static final int W = 5, H = 9;

    private static EInkDisplayDriver newDriver(Path outputDir, String orientation) {
        return newDriver(outputDir, orientation, W, H);
    }

    private static EInkDisplayDriver newDriver(Path outputDir, String orientation, int width, int height) {
        DriverConfig config = DriverConfig.builder()
            .property("width", width)
            .property("height", height)
            .property("orientation", orientation)
            .property("outputDir", outputDir.toString())
            .build();
        return DisplayDriverFactory.select("png").create(null, config);
    }

    private static byte[] allBlack() {
        return new byte[H]; // ceil(5/8)=1 byte/row * H rows, all bits 0 = black
    }

    @Test
    void capabilitiesDeclareBothFormatsItActuallyDecodes() {
        var caps = DisplayDriverFactory.select("png").create(null, DriverConfig.builder().build()).getCapabilities();
        // Regression guard for the inconsistency found during 6.3: writeFrame already
        // decoded FourGrayFrame while CAPABILITIES claimed only MONOCHROME support.
        assertTrue(caps.supports(PixelFormat.MONOCHROME));
        assertTrue(caps.supports(PixelFormat.FOUR_GRAY));
    }

    @Test
    void writeFrameProducesSequentiallyNamedFiles(@TempDir Path tmp) throws Exception {
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT");

        driver.writeFrame(new MonochromeFrame(allBlack(), RefreshMode.FULL)).get();
        driver.writeFrame(new MonochromeFrame(allBlack(), RefreshMode.FULL)).get();
        driver.writeFrame(new MonochromeFrame(allBlack(), RefreshMode.FULL)).get();

        assertTrue(Files.exists(tmp.resolve("frame-0001.png")));
        assertTrue(Files.exists(tmp.resolve("frame-0002.png")));
        assertTrue(Files.exists(tmp.resolve("frame-0003.png")));
    }

    @Test
    void freshDriverDefaultsToWhiteNotTransparentBlack(@TempDir Path tmp) throws Exception {
        // A bare int[] framebuffer defaults to 0x00000000 (transparent black); this pins the
        // fix that initialises it to white instead, matching an eInk panel's actual power-on
        // state — see PngEInkDisplay's nativeBuffer field javadoc.
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT");

        // Write only a 1x1 black region in the corner; everywhere else must stay white.
        driver.writeRegion(0, 0, 1, 1, new MonochromeFrame(new byte[]{ 0 }, RefreshMode.PARTIAL)).get();

        byte[] gray = decodePngGrayscale(tmp.resolve("frame-0001.png"), W, H);
        assertEquals(0, gray[0] & 0xFF, "the written pixel must be black");
        for (int i = 1; i < gray.length; i++) {
            assertEquals(255, gray[i] & 0xFF, "pixel " + i + " outside the written region must be white");
        }
    }

    @Test
    void writeRegionCompositesOntoRetainedBufferAcrossCalls(@TempDir Path tmp) throws Exception {
        // A wider (multiple-of-8) fixture than W/H above, specifically so a non-zero x offset
        // is actually reachable under the 8px alignment CAPABILITIES declares.
        int rw = 16, rh = 12;
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT", rw, rh);

        // Two disjoint, non-zero-offset black regions. Deliberately NOT "write black then
        // write already-white": the original version of this test wrote row 2 white onto an
        // already-white background, so a transposed x/y bug at the composite site would have
        // been invisible and the test would still have passed. Here, a wrong destination
        // index for either write lands black pixels somewhere this test checks are white (or
        // vice versa), so a transposition is guaranteed to fail an assertion.
        byte[] region1 = new byte[]{ (byte) 0xFF, (byte) 0xFF }; // 8x2 all black
        byte[] region2 = new byte[]{ (byte) 0xFF, (byte) 0xFF }; // 8x2 all black
        driver.writeRegion(0, 0, 8, 2, new MonochromeFrame(invert(region1), RefreshMode.PARTIAL)).get(); // top-left
        driver.writeRegion(8, 6, 8, 2, new MonochromeFrame(invert(region2), RefreshMode.PARTIAL)).get(); // bottom-right-ish, offset in both x and y

        byte[] gray = decodePngGrayscale(tmp.resolve("frame-0002.png"), rw, rh);
        for (int row = 0; row < rh; row++) {
            for (int col = 0; col < rw; col++) {
                boolean expectBlack = (row < 2 && col < 8) || (row >= 6 && row < 8 && col >= 8);
                int actual = gray[row * rw + col] & 0xFF;
                assertEquals(expectBlack ? 0 : 255, actual,
                    "pixel (" + col + "," + row + ") — wrong value means a region landed at the wrong offset");
            }
        }
    }

    @Test
    void writeRegionRejectsOutOfBoundsX(@TempDir Path tmp) {
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT", 16, 12);
        byte[] region = new byte[]{ 0, 0 };
        assertThrows(IllegalArgumentException.class,
            () -> driver.writeRegion(8, 0, 16, 2, new MonochromeFrame(region, RefreshMode.PARTIAL)),
            "x=8 with width=16 exceeds the driver's width=16 (8+16>16)");
    }

    @Test
    void writeRegionRejectsOutOfBoundsY(@TempDir Path tmp) {
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT", 16, 12);
        byte[] region = new byte[]{ 0, 0 };
        assertThrows(IllegalArgumentException.class,
            () -> driver.writeRegion(0, 10, 16, 4, new MonochromeFrame(region, RefreshMode.PARTIAL)),
            "y=10 with height=4 exceeds the driver's height=12 (10+4>12)");
    }

    @Test
    void writeRegionRejectsMisalignedX(@TempDir Path tmp) {
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT", 16, 12);
        byte[] region = new byte[]{ 0, 0 };
        assertThrows(IllegalArgumentException.class,
            () -> driver.writeRegion(4, 0, 8, 2, new MonochromeFrame(region, RefreshMode.PARTIAL)),
            "x=4 is not a multiple of the declared 8px alignment step");
    }

    // Regression test for the data race found during 6.3 review: PngEInkDisplay's retained
    // native buffer was mutated on the caller's thread while a previous write's virtual
    // thread could still be reading it, risking a torn frame. Fires several writeRegion calls
    // from different threads with none of them awaited before the next is issued — exactly
    // the misuse pattern that produced the race — then awaits everything and checks both that
    // every write actually landed (no lost/corrupted composite) and that file numbering has
    // no gaps or duplicates despite the out-of-order submission.
    @Test
    void concurrentWritesAreSerialisedWithoutTornFramesOrLostWrites(@TempDir Path tmp) throws Exception {
        int cw = 16, ch = 12; // 6 disjoint 16x2 row bands cover the whole buffer exactly once
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT", cw, ch);
        int bandCount = ch / 2;
        // 16 wide (2 bytes/row) x 2 rows tall = 4 bytes, all 1s = all black once inverted (see invert()).
        byte[] blackRow = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int band = 0; band < bandCount; band++) {
            int y = band * 2;
            // Submitted from the test thread in a tight loop with no .get() in between —
            // deliberately overlapping in time from the driver's point of view.
            futures.add(driver.writeRegion(0, y, cw, 2, new MonochromeFrame(invert(blackRow), RefreshMode.PARTIAL)));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

        // Regions are disjoint and together cover the whole buffer, so regardless of the
        // (nondeterministic) order the single-thread worker actually executed them in, the
        // final frame must be entirely black. Any white pixel means a write was lost or
        // clobbered by a race; any garbage byte pattern would fail decodePngGrayscale itself.
        byte[] finalFrame = decodePngGrayscale(tmp.resolve(String.format("frame-%04d.png", bandCount)), cw, ch);
        for (int i = 0; i < finalFrame.length; i++) {
            assertEquals(0, finalFrame[i] & 0xFF, "pixel " + i + " must be black after all bands are written");
        }

        // No gaps/duplicates: exactly one file per submitted write, sequence 1..bandCount.
        for (int seq = 1; seq <= bandCount; seq++) {
            assertTrue(Files.exists(tmp.resolve(String.format("frame-%04d.png", seq))),
                "frame-" + String.format("%04d", seq) + ".png must exist — worker must not skip or merge sequence numbers");
        }
    }

    // MonochromeFrame's bit convention is 0=black, 1=white (see its javadoc); the region
    // fixtures above are expressed as "all black" for readability, so this inverts an
    // all-1s byte array into the all-0s bytes that convention actually requires.
    private static byte[] invert(byte[] bits) {
        byte[] out = new byte[bits.length];
        for (int i = 0; i < bits.length; i++) {
            out[i] = (byte) ~bits[i];
        }
        return out;
    }

    @Test
    void writeFrameRotatesOutputToLogicalOrientation(@TempDir Path tmp) throws Exception {
        // LANDSCAPE swaps the on-disk PNG's dimensions relative to the native WxH — the
        // whole point of rotating before encoding (project-plan.md 6.3: "the PNG must show
        // what a human would see", not the raw native framebuffer layout).
        EInkDisplayDriver driver = newDriver(tmp, "LANDSCAPE");
        driver.writeFrame(new MonochromeFrame(allBlack(), RefreshMode.FULL)).get();

        int[] wh = readPngDimensions(tmp.resolve("frame-0001.png"));
        assertEquals(H, wh[0], "logical width under LANDSCAPE must be the native height");
        assertEquals(W, wh[1], "logical height under LANDSCAPE must be the native width");
    }

    @Test
    void writeRegionRejectsFourGrayFrame(@TempDir Path tmp) {
        EInkDisplayDriver driver = newDriver(tmp, "PORTRAIT");
        byte[] plane = allBlack();
        assertThrows(UnsupportedOperationException.class,
            () -> driver.writeRegion(0, 0, W, H, new FourGrayFrame(plane, plane)));
    }

    // --- minimal PNG chunk/inflate reader, scoped to this test only ---

    private static byte[] decodePngGrayscale(Path pngFile, int expectedWidth, int expectedHeight) throws IOException {
        byte[] data = Files.readAllBytes(pngFile);
        int[] wh = readPngDimensions(pngFile);
        assertEquals(expectedWidth, wh[0]);
        assertEquals(expectedHeight, wh[1]);
        byte[] idat = extractChunk(data, "IDAT");
        byte[] rows = inflate(idat, (wh[0] + 1) * wh[1]);
        byte[] gray = new byte[wh[0] * wh[1]];
        for (int row = 0; row < wh[1]; row++) {
            System.arraycopy(rows, row * (wh[0] + 1) + 1, gray, row * wh[0], wh[0]);
        }
        return gray;
    }

    private static int[] readPngDimensions(Path pngFile) throws IOException {
        byte[] data = Files.readAllBytes(pngFile);
        return new int[]{ readInt(data, 16), readInt(data, 20) };
    }

    private static byte[] extractChunk(byte[] png, String type) {
        int pos = 8;
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String chunkType = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (chunkType.equals(type)) {
                byte[] out = new byte[len];
                System.arraycopy(png, pos + 8, out, 0, len);
                return out;
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
