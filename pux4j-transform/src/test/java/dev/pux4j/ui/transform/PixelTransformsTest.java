// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelTransformsTest {

    // PORTRAIT is the identity mapping (nativeX == logicalX, nativeY == logicalY), so test
    // fixtures can address pixels directly without hand-computing a rotation.
    private static final int FB_W = 11;
    private static final int FB_H = 19;
    private static final int FB_ROW_BYTES = (FB_W + 7) / 8;
    private static final OrientationMapping IDENTITY = OrientationMapping.of(FB_W, FB_H, Orientation.PORTRAIT);

    private static MemorySegment allocateBgra(Arena arena, int w, int h) {
        MemorySegment seg = arena.allocate((long) w * h * 4);
        for (long i = 0; i < seg.byteSize(); i += 4) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) 0xFF);     // B
            seg.set(ValueLayout.JAVA_BYTE, i + 1, (byte) 0xFF); // G
            seg.set(ValueLayout.JAVA_BYTE, i + 2, (byte) 0xFF); // R
            seg.set(ValueLayout.JAVA_BYTE, i + 3, (byte) 0xFF); // A
        }
        return seg;
    }

    private static void setPixelGrey(MemorySegment bgra, int w, int x, int y, int grey) {
        setPixelBgr(bgra, w, x, y, grey, grey, grey);
    }

    /** Sets a pixel's B/G/R channels independently — for tests that need a real color, not a
     * grey value, e.g. to distinguish a correct BGRA channel read from an R/B swap. */
    private static void setPixelBgr(MemorySegment bgra, int w, int x, int y, int b, int g, int r) {
        long off = ((long) y * w + x) * 4;
        bgra.set(ValueLayout.JAVA_BYTE, off, (byte) b);
        bgra.set(ValueLayout.JAVA_BYTE, off + 1, (byte) g);
        bgra.set(ValueLayout.JAVA_BYTE, off + 2, (byte) r);
    }

    private static boolean isBlack(byte[] packed, int fbRowBytes, int fx, int fy) {
        int idx = fy * fbRowBytes + (fx / 8);
        int bit = 7 - (fx % 8);
        return (packed[idx] & (1 << bit)) == 0;
    }

    /**
     * A single black logical pixel must land at the identical native coordinate and nowhere
     * else — catches an off-by-one or a wrong byte/bit index in {@code THRESHOLD}'s packing
     * loop specifically (not orientation, which {@code PORTRAIT}'s identity mapping keeps out
     * of this test on purpose — see {@code CanvasOrientationParityTest} in pux4j-validation for
     * that).
     */
    @Test
    void thresholdSetsExactlyTheBlackPixel() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            setPixelGrey(bgra, FB_W, 3, 5, 0); // pure black

            MonochromeFrame frame = (MonochromeFrame) PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.THRESHOLD, IDENTITY);

            int blackCount = 0;
            for (int fy = 0; fy < FB_H; fy++) {
                for (int fx = 0; fx < FB_W; fx++) {
                    if (isBlack(frame.data(), FB_ROW_BYTES, fx, fy)) {
                        blackCount++;
                        assertEquals(3, fx);
                        assertEquals(5, fy);
                    }
                }
            }
            assertEquals(1, blackCount);
        }
    }

    /**
     * A uniformly white frame must pack to all-1 bits under every strategy, including the two
     * that dither ({@code ORDERED_2X2}, {@code FLOYD_STEINBERG}). This is the padding-bit
     * initialisation check from a different angle: if the pre-fill-to-{@code 0xFF} step were
     * missing or wrong, or a dither strategy nudged an extreme value across its threshold, this
     * would fail without needing to know exactly where the padding bits are.
     */
    @Test
    void allWhiteInputStaysAllWhiteRegardlessOfStrategy() {
        for (TransformStrategy strategy : new TransformStrategy[]{
                TransformStrategy.THRESHOLD, TransformStrategy.ORDERED_2X2, TransformStrategy.FLOYD_STEINBERG}) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bgra = allocateBgra(arena, FB_W, FB_H); // pre-filled white
                MonochromeFrame frame = (MonochromeFrame) PixelTransforms.transform(
                    bgra, PixelFormat.MONOCHROME, strategy, IDENTITY);
                byte[] expectedAllWhite = new byte[FB_ROW_BYTES * FB_H];
                java.util.Arrays.fill(expectedAllWhite, (byte) 0xFF);
                assertArrayEquals(expectedAllWhite, frame.data(), "strategy=" + strategy);
            }
        }
    }

    /**
     * The black-extreme counterpart to {@link #allWhiteInputStaysAllWhiteRegardlessOfStrategy}.
     * Specifically targets {@code FLOYD_STEINBERG}: at lum=0 the quantisation error is always
     * exactly 0 (old value already equals the quantised value), so error can never accumulate
     * and push a black run back to white — a diffusion-weight bug (wrong coefficients, or
     * carrying error across a row boundary incorrectly) would otherwise only show up on
     * mid-tone content, which is harder to pin down.
     */
    @Test
    void allBlackInputStaysAllBlackRegardlessOfStrategy() {
        for (TransformStrategy strategy : new TransformStrategy[]{
                TransformStrategy.THRESHOLD, TransformStrategy.ORDERED_2X2, TransformStrategy.FLOYD_STEINBERG}) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
                for (int y = 0; y < FB_H; y++) {
                    for (int x = 0; x < FB_W; x++) {
                        setPixelGrey(bgra, FB_W, x, y, 0);
                    }
                }
                MonochromeFrame frame = (MonochromeFrame) PixelTransforms.transform(
                    bgra, PixelFormat.MONOCHROME, strategy, IDENTITY);
                for (int fy = 0; fy < FB_H; fy++) {
                    for (int fx = 0; fx < FB_W; fx++) {
                        assertTrue(isBlack(frame.data(), FB_ROW_BYTES, fx, fy),
                            "strategy=" + strategy + " (" + fx + "," + fy + ")");
                    }
                }
            }
        }
    }

    /**
     * {@code ORDERED_2X2} exists specifically because its threshold is a pure function of pixel
     * position (unlike {@code FLOYD_STEINBERG}'s carried error), so a static image must dither
     * identically frame to frame — that's what {@link FrameDiff} relies on to see "no change".
     * Packs the same uniform mid-grey input twice and requires byte-identical output; also
     * asserts the output is non-uniform (both a black and a white bit present at a midtone),
     * since a same-output-twice check alone would also pass for a broken strategy that ignored
     * the Bayer matrix and just always chose one colour.
     */
    @Test
    void orderedDitherIsDeterministicForTheSameInput() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            for (int y = 0; y < FB_H; y++) {
                for (int x = 0; x < FB_W; x++) {
                    setPixelGrey(bgra, FB_W, x, y, 128);
                }
            }
            MonochromeFrame first = (MonochromeFrame) PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.ORDERED_2X2, IDENTITY);
            MonochromeFrame second = (MonochromeFrame) PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.ORDERED_2X2, IDENTITY);
            assertArrayEquals(first.data(), second.data());
            assertTrue(isBlack(first.data(), FB_ROW_BYTES, 1, 0));
            assertFalse(isBlack(first.data(), FB_ROW_BYTES, 0, 0));
        }
    }

    /**
     * {@code allWhiteInputStaysAllWhiteRegardlessOfStrategy}/{@code allBlack...} are the only
     * other tests exercising {@code FLOYD_STEINBERG}, and both are degenerate for error
     * diffusion (the quantisation error is always exactly zero at the extremes), so none of the
     * actual diffusion weights, the padded-index offsets, or the row-swap ever run. This test
     * uses a single uniform mid-grey row (lum 100, below the 128 threshold but not by much) and
     * a hand-computed expected result from the standard Floyd-Steinberg kernel (7/16 right,
     * 3/16 down-left, 5/16 down, 1/16 down-right — irrelevant here since there's only one row,
     * but the right-neighbour carry is what this fixture actually exercises):
     * <pre>
     * x=0: old=100+0=100        -> black (0);  error=100   -> carries 7/16*100=43.75 to x=1
     * x=1: old=100+43.75=143.75 -> white (255); error=-111.25 -> carries 7/16*-111.25=-48.67 to x=2
     * x=2: old=100-48.67=51.33  -> black (0);  error=51.33  -> carries 7/16*51.33=22.44 to x=3
     * x=3: old=100+22.44=122.44 -> black (0)
     * </pre>
     * Expected pattern: black, white, black, black. A transposed weight (e.g. 3/16 and 1/16
     * swapped) or a padded-index off-by-one would change this pattern; the all-white/all-black
     * tests would not notice.
     */
    @Test
    void floydSteinbergMatchesHandComputedDiffusionPattern() {
        int w = 4, h = 1;
        OrientationMapping mapping = OrientationMapping.of(w, h, Orientation.PORTRAIT);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = arena.allocate((long) w * h * 4);
            for (int x = 0; x < w; x++) {
                setPixelGrey(bgra, w, x, 0, 100);
            }

            MonochromeFrame frame = (MonochromeFrame) PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.FLOYD_STEINBERG, mapping);

            int rowBytes = (w + 7) / 8;
            boolean[] expectedBlack = {true, false, true, true};
            for (int x = 0; x < w; x++) {
                assertEquals(expectedBlack[x], isBlack(frame.data(), rowBytes, x, 0), "pixel x=" + x);
            }
        }
    }

    /**
     * Every other test in this class uses B=G=R (grey) pixels, which cannot distinguish a
     * correctly-ordered BGRA read from an R/B channel swap — {@code r*299} alone maxes out at
     * lum 76 and {@code b*114} alone at lum 29, so a pure-red-or-blue pixel can never cross the
     * THRESHOLD cutoff of 128 either way, and the design doc calls this exact bug out by name
     * ("swapping R and B ... yields a wrong-but-plausible image that no visual check on a 1-bit
     * panel will detect"). This pixel (B=0, G=100, R=255) is hand-picked so the two channels
     * combine with G to cross 128 in <em>opposite</em> directions depending on which byte is
     * read as R and which as B: correctly read, lum = (255*299 + 100*587 + 0*114)/1000 = 134
     * (white, no mark); with R/B swapped, lum = (0*299 + 100*587 + 255*114)/1000 = 87 (black,
     * a visible mark where there should be none).
     */
    @Test
    void luminanceReadsBgraChannelsInTheCorrectOrder() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            setPixelBgr(bgra, FB_W, 3, 5, 0, 100, 255); // B=0, G=100, R=255

            MonochromeFrame frame = (MonochromeFrame) PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.THRESHOLD, IDENTITY);

            for (int fy = 0; fy < FB_H; fy++) {
                for (int fx = 0; fx < FB_W; fx++) {
                    assertFalse(isBlack(frame.data(), FB_ROW_BYTES, fx, fy),
                        "no pixel should be black — lum 134 is white; an R/B swap would make (3,5) black");
                }
            }
        }
    }

    /**
     * One pixel per bin, checked against {@code FourGrayFrame}'s documented shade lookup table
     * (bw=1,red=1 = white ... bw=0,red=0 = black). Exists to catch the classification/bit-write
     * logic being transposed — e.g. writing "light grey" where "dark grey" was intended, which
     * would still produce plausible-looking 4-level output but the wrong shade at every pixel.
     */
    @Test
    void fourGrayBinningBitsMatchShadeLookupTable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            setPixelGrey(bgra, FB_W, 0, 0, 0);   // black
            setPixelGrey(bgra, FB_W, 1, 0, 100); // dark grey
            setPixelGrey(bgra, FB_W, 2, 0, 150); // light grey
            setPixelGrey(bgra, FB_W, 3, 0, 220); // white

            FourGrayFrame frame = (FourGrayFrame) PixelTransforms.transform(
                bgra, PixelFormat.FOUR_GRAY, TransformStrategy.FOUR_GRAY_BINNING, IDENTITY);

            assertEquals(false, bit(frame.bwPlane(), 0, 0)); assertEquals(false, bit(frame.redPlane(), 0, 0)); // black (0,0)
            assertEquals(false, bit(frame.bwPlane(), 1, 0)); assertEquals(true, bit(frame.redPlane(), 1, 0));  // dark grey (0,1)
            assertEquals(true, bit(frame.bwPlane(), 2, 0));  assertEquals(false, bit(frame.redPlane(), 2, 0)); // light grey (1,0)
            assertEquals(true, bit(frame.bwPlane(), 3, 0));  assertEquals(true, bit(frame.redPlane(), 3, 0));  // white (1,1)
        }
    }

    /**
     * {@link #fourGrayBinningBitsMatchShadeLookupTable} only uses values comfortably inside each
     * bin (0, 100, 150, 220), so a {@code >=} vs {@code >} slip on any of the three boundary
     * comparisons in {@code packFourGray} would still pass it. This test uses the boundary
     * values themselves (64, 128, 192) and pins the documented {@code >=} semantics: a pixel
     * exactly at a boundary belongs to the <em>brighter</em> of its two adjacent bins.
     */
    @Test
    void fourGrayBinningTreatsExactBoundaryAsTheBrighterBin() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            setPixelGrey(bgra, FB_W, 0, 0, 64);  // exactly lo -> dark grey (0,1), not black (0,0)
            setPixelGrey(bgra, FB_W, 1, 0, 128); // exactly mid -> light grey (1,0), not dark grey
            setPixelGrey(bgra, FB_W, 2, 0, 192); // exactly hi -> white (1,1), not light grey

            FourGrayFrame frame = (FourGrayFrame) PixelTransforms.transform(
                bgra, PixelFormat.FOUR_GRAY, TransformStrategy.FOUR_GRAY_BINNING, IDENTITY);

            assertEquals(false, bit(frame.bwPlane(), 0, 0)); assertEquals(true, bit(frame.redPlane(), 0, 0));  // dark grey
            assertEquals(true, bit(frame.bwPlane(), 1, 0));  assertEquals(false, bit(frame.redPlane(), 1, 0)); // light grey
            assertEquals(true, bit(frame.bwPlane(), 2, 0));  assertEquals(true, bit(frame.redPlane(), 2, 0));  // white
        }
    }

    private static boolean bit(byte[] plane, int fx, int fy) {
        int idx = fy * FB_ROW_BYTES + (fx / 8);
        int bitPos = 7 - (fx % 8);
        return (plane[idx] & (1 << bitPos)) != 0;
    }

    /**
     * {@code RenderSession.create()} has its own copy of this validation, but
     * {@code PixelTransforms.transform} is also a directly-callable public entry point (the
     * "fine-grained API" — e.g. {@code pux4j-native}'s future callers), so it must not trust a
     * caller who bypasses {@code RenderSession} to have already checked format/strategy
     * compatibility.
     */
    @Test
    void monochromeFormatRejectsFourGrayStrategy() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            assertThrows(IllegalArgumentException.class, () -> PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.FOUR_GRAY_BINNING, IDENTITY));
        }
    }

    /** The mirror image of {@link #monochromeFormatRejectsFourGrayStrategy} — both directions
     * of the format/strategy mismatch must fail, not just one. */
    @Test
    void fourGrayFormatRejectsMonochromeStrategy() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            assertThrows(IllegalArgumentException.class, () -> PixelTransforms.transform(
                bgra, PixelFormat.FOUR_GRAY, TransformStrategy.THRESHOLD, IDENTITY));
        }
    }

    /**
     * {@code RGB565}/{@code SEVEN_COLOR} have no packing implementation at all (no
     * {@code TransformStrategy} targets them) — must fail loudly here with a clear message
     * rather than falling through to a {@code switch}'s default/unreachable branch or silently
     * producing garbage.
     */
    @Test
    void unsupportedPixelFormatsAreRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allocateBgra(arena, FB_W, FB_H);
            assertThrows(IllegalArgumentException.class, () -> PixelTransforms.transform(
                bgra, PixelFormat.RGB565, TransformStrategy.THRESHOLD, IDENTITY));
            assertThrows(IllegalArgumentException.class, () -> PixelTransforms.transform(
                bgra, PixelFormat.SEVEN_COLOR, TransformStrategy.THRESHOLD, IDENTITY));
        }
    }

    /**
     * A too-small {@code MemorySegment} would otherwise be read out of bounds by the packing
     * loop (native code / FFM — no automatic Java bounds exception guaranteed at the point of
     * misuse); this bounds check is the only thing standing between a caller's mistake and
     * undefined behaviour, so it needs its own explicit test rather than relying on some other
     * test to happen to trip it.
     */
    @Test
    void tooSmallSegmentIsRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = arena.allocate(4); // 1 pixel, way too small for FB_W x FB_H
            assertThrows(IllegalArgumentException.class, () -> PixelTransforms.transform(
                bgra, PixelFormat.MONOCHROME, TransformStrategy.THRESHOLD, IDENTITY));
        }
    }
}
