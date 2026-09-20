// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderSessionTest {

    // PORTRAIT = identity mapping; 16x16 keeps the arithmetic simple (multiple of 8) while
    // still leaving room for a "small change in one corner" vs "scattered corners" distinction.
    private static final int FB_W = 16;
    private static final int FB_H = 16;
    private static final OrientationMapping MAPPING = OrientationMapping.of(FB_W, FB_H, Orientation.PORTRAIT);

    private static DisplayCapabilities capsWith(RefreshMode... modes) {
        return new DisplayCapabilities(
            EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.FOUR_GRAY),
            EnumSet.copyOf(java.util.List.of(modes)),
            true,
            Optional.of(new AlignmentConstraints(8)));
    }

    private static MemorySegment allWhiteBgra(Arena arena) {
        return allWhiteBgra(arena, FB_W, FB_H);
    }

    private static MemorySegment allWhiteBgra(Arena arena, int w, int h) {
        MemorySegment seg = arena.allocate((long) w * h * 4);
        for (long i = 0; i < seg.byteSize(); i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) 0xFF);
        }
        return seg;
    }

    private static void blacken(MemorySegment bgra, int x, int y) {
        blacken(bgra, FB_W, x, y);
    }

    private static void blacken(MemorySegment bgra, int w, int x, int y) {
        long off = ((long) y * w + x) * 4;
        bgra.set(ValueLayout.JAVA_BYTE, off, (byte) 0);
        bgra.set(ValueLayout.JAVA_BYTE, off + 1, (byte) 0);
        bgra.set(ValueLayout.JAVA_BYTE, off + 2, (byte) 0);
    }

    @Test
    void firstCallIsAlwaysFull() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.monochromeDefaults());
        try (Arena arena = Arena.ofConfined()) {
            RenderResult result = session.prepare(allWhiteBgra(arena)).orElseThrow();
            assertEquals(RefreshMode.FULL, result.decision().mode());
            assertTrue(result.decision().region().isEmpty());
        }
    }

    @Test
    void noChangeReturnsEmpty() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.monochromeDefaults());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow(); // first call, consumes the FULL
            assertTrue(session.prepare(bgra).isEmpty());
        }
    }

    @Test
    void smallChangeProducesPartialWithCorrectCroppedRegion() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).maxPartials(10).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow(); // first call: FULL

            blacken(bgra, 4, 5); // one pixel, native byte column 0 (px 0-7), row 5
            RenderResult result = session.prepare(bgra).orElseThrow();

            assertEquals(RefreshMode.PARTIAL, result.decision().mode());
            Region region = result.decision().region().orElseThrow();
            assertEquals(new Region(0, 5, 8, 1), region);

            MonochromeFrame frame = (MonochromeFrame) result.frame();
            assertEquals(1, frame.data().length, "cropped frame must be ceil(8/8)*1 = 1 byte");
            assertEquals((byte) ~(1 << 3), frame.data()[0], "pixel 4 of 0..7 cleared, rest white");
        }
    }

    /**
     * {@link #smallChangeProducesPartialWithCorrectCroppedRegion} only ever exercises a full
     * 8-wide byte column, because {@code FB_W = 16} is a multiple of 8 everywhere. On the real
     * 122-wide SSD1680 panel (and here, an 11-wide stand-in), the last byte column is only
     * partially real pixels, and {@code Region.width()} gets clamped short of 8 — see
     * {@code tier2-transform.md}'s corrected crop invariant. This is the end-to-end version of
     * that invariant: {@code FrameDiffTest} proves the <em>diff</em> can produce such a region,
     * this test proves {@code RenderSession}'s actual cropper handles it correctly (not just
     * "doesn't assert-fail" — the returned bytes must be exactly right, or the panel renders
     * garbage in that column).
     */
    @Test
    void croppedRegionOnNonByteMultipleWidthPanelMatchesSubRectangle() {
        OrientationMapping narrow = OrientationMapping.of(11, 8, Orientation.PORTRAIT);
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            narrow, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena, 11, 8);
            session.prepare(bgra).orElseThrow(); // first call: FULL

            blacken(bgra, 11, 9, 3); // byte column 1 (native px 8-15), but only px 8-10 exist on an 11-wide panel
            RenderResult result = session.prepare(bgra).orElseThrow();

            assertEquals(RefreshMode.PARTIAL, result.decision().mode());
            Region region = result.decision().region().orElseThrow();
            assertEquals(new Region(8, 3, 3, 1), region, "width clamped to 11-8=3, not padded back to 8");

            MonochromeFrame frame = (MonochromeFrame) result.frame();
            assertEquals(1, frame.data().length, "ceil(3/8)*1 = 1 byte, not 0 and not rounded up to 2");
            assertEquals((byte) ~(1 << 6), frame.data()[0],
                "px9 is bit 6 of byte column 1; px8/px10 and the 5 padding bits (px11-15) stay white");
        }
    }

    /**
     * The failure mode the design doc names for padding: if the {@code 0xFF}-initialised
     * padding bits in the last, partially-real byte column of each row were not re-derived
     * deterministically on every pack, two calls with byte-for-byte identical content would
     * still show a spurious diff there, and "every frame looks dirty" forever. {@code FB_W = 16}
     * (used by every other test in this class) has no padding at all — this uses an 11-wide
     * panel (byte column 1 covers px 8-15, only 8-10 real) specifically to put a padded byte
     * column into the diff path.
     */
    @Test
    void paddingBitsStayStableAcrossIdenticalFramesOnNonByteMultipleWidthPanel() {
        OrientationMapping narrow = OrientationMapping.of(11, 8, Orientation.PORTRAIT);
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            narrow, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena, 11, 8);
            blacken(bgra, 11, 3, 2); // one real pixel, well clear of the padded byte column
            session.prepare(bgra).orElseThrow(); // first call: FULL

            assertTrue(session.prepare(bgra).isEmpty(),
                "identical content twice must see no change, including in the padded byte column");
        }
    }

    @Test
    void refreshModeAgreesBetweenFrameAndDecision() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            RenderResult first = session.prepare(bgra).orElseThrow();
            assertModeAgrees(first);

            blacken(bgra, 0, 0);
            RenderResult second = session.prepare(bgra).orElseThrow();
            assertModeAgrees(second);
        }
    }

    private static void assertModeAgrees(RenderResult result) {
        if (result.frame() instanceof MonochromeFrame mf) {
            assertEquals(result.decision().mode(), mf.mode());
        }
    }

    @Test
    void maxPartialsForcesFullAndResetsCounter() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).maxPartials(2).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            assertEquals(RefreshMode.FULL, session.prepare(bgra).orElseThrow().decision().mode()); // initial

            blacken(bgra, 0, 0);
            assertEquals(RefreshMode.PARTIAL, session.prepare(bgra).orElseThrow().decision().mode()); // count=1
            blacken(bgra, 1, 0);
            assertEquals(RefreshMode.PARTIAL, session.prepare(bgra).orElseThrow().decision().mode()); // count=2
            blacken(bgra, 2, 0);
            assertEquals(RefreshMode.FULL, session.prepare(bgra).orElseThrow().decision().mode());    // count>=maxPartials -> FULL, reset
            blacken(bgra, 3, 0);
            assertEquals(RefreshMode.PARTIAL, session.prepare(bgra).orElseThrow().decision().mode()); // count=1 again
        }
    }

    @Test
    void changedAreaOverThresholdForcesFull() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).fullRefreshThreshold(0.1f).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow(); // first call: FULL

            for (int y = 0; y < FB_H; y++) {
                for (int x = 0; x < FB_W / 2; x++) {
                    blacken(bgra, x, y); // half the panel changed — well over 10%
                }
            }
            RenderResult result = session.prepare(bgra).orElseThrow();
            assertEquals(RefreshMode.FULL, result.decision().mode());
        }
    }

    @Test
    void scatteredCornerChangesWithMaxRegionsOnePromoteToFull() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).maxRegions(1)
                .fullRefreshThreshold(0.3f).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow();

            blacken(bgra, 0, 0);               // top-left corner
            blacken(bgra, FB_W - 1, FB_H - 1);  // bottom-right corner
            RenderResult result = session.prepare(bgra).orElseThrow();

            // The bounding box of both corners covers nearly the whole panel, which is worse
            // than a full refresh as a "partial" — must be promoted to FULL, not sent as one
            // giant partial region.
            assertEquals(RefreshMode.FULL, result.decision().mode());
        }
    }

    /**
     * Refresh-strategy step 5 ({@code tier2-transform.md}): a FULL decision upgrades to FAST
     * when the caller opted in via {@code preferFastRefresh(true)} and the display supports it.
     * Nothing else in this class sets {@code preferFastRefresh} or includes {@code FAST} in
     * caps, so without this test the entire upgrade block could be deleted and nothing would
     * fail.
     */
    @Test
    void preferFastRefreshUpgradesAForcedFullToFast() {
        RenderSession session = RenderSession.create(
            capsWith(RefreshMode.FULL, RefreshMode.PARTIAL, RefreshMode.FAST),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).maxPartials(1).mergeThresholdPx(0)
                .preferFastRefresh(true).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow(); // first call: FULL (unconditional, not eligible for the upgrade)

            blacken(bgra, 0, 0);
            session.prepare(bgra).orElseThrow(); // count=1, PARTIAL

            blacken(bgra, 1, 0); // count(1) >= maxPartials(1) -> forced FULL -> eligible for FAST upgrade
            RenderResult result = session.prepare(bgra).orElseThrow();
            assertEquals(RefreshMode.FAST, result.decision().mode());
            assertTrue(result.decision().region().isEmpty());
        }
    }

    /**
     * The upgrade in {@link #preferFastRefreshUpgradesAForcedFullToFast} must not leak into the
     * PARTIAL path — {@code preferFastRefresh} only fires when the chosen mode is already FULL.
     */
    @Test
    void preferFastRefreshDoesNotAffectAPartialDecision() {
        RenderSession session = RenderSession.create(
            capsWith(RefreshMode.FULL, RefreshMode.PARTIAL, RefreshMode.FAST),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).maxPartials(10).mergeThresholdPx(0)
                .preferFastRefresh(true).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow();

            blacken(bgra, 0, 0);
            RenderResult result = session.prepare(bgra).orElseThrow();
            assertEquals(RefreshMode.PARTIAL, result.decision().mode());
        }
    }

    @Test
    void resetTreatsNextCallAsFirstCall() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL, RefreshMode.PARTIAL),
            MAPPING, RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.THRESHOLD).mergeThresholdPx(0).build());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            session.prepare(bgra).orElseThrow();
            blacken(bgra, 0, 0);
            assertEquals(RefreshMode.PARTIAL, session.prepare(bgra).orElseThrow().decision().mode());

            session.reset();
            assertEquals(RefreshMode.FULL, session.prepare(bgra).orElseThrow().decision().mode());
        }
    }

    @Test
    void fourGrayNeverProducesAPartialRefresh() {
        RenderSession session = RenderSession.create(capsWith(RefreshMode.FULL),
            MAPPING, RenderOptions.fourGrayDefaults());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bgra = allWhiteBgra(arena);
            RenderResult first = session.prepare(bgra).orElseThrow();
            assertEquals(RefreshMode.FULL, first.decision().mode());
            assertTrue(first.decision().region().isEmpty());

            assertTrue(session.prepare(bgra).isEmpty()); // no change

            blacken(bgra, 0, 0);
            RenderResult second = session.prepare(bgra).orElseThrow();
            assertEquals(RefreshMode.FULL, second.decision().mode());
            assertTrue(second.decision().region().isEmpty());
        }
    }

    @Test
    void createRejectsUnsupportedPixelFormat() {
        DisplayCapabilities caps = new DisplayCapabilities(
            EnumSet.of(PixelFormat.MONOCHROME, PixelFormat.RGB565),
            EnumSet.of(RefreshMode.FULL),
            true, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> RenderSession.create(caps, MAPPING,
            RenderOptions.builder().pixelFormat(PixelFormat.RGB565).strategy(TransformStrategy.THRESHOLD).build()));
    }

    @Test
    void createRejectsFormatStrategyMismatch() {
        DisplayCapabilities caps = capsWith(RefreshMode.FULL);
        assertThrows(IllegalArgumentException.class, () -> RenderSession.create(caps, MAPPING,
            RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME)
                .strategy(TransformStrategy.FOUR_GRAY_BINNING).build()));
    }

    @Test
    void createRejectsUnsupportedFormatNotDeclaredByCaps() {
        DisplayCapabilities caps = new DisplayCapabilities(
            EnumSet.of(PixelFormat.FOUR_GRAY), EnumSet.of(RefreshMode.FULL), true, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> RenderSession.create(caps, MAPPING,
            RenderOptions.monochromeDefaults()));
    }

    @Test
    void createRejectsPartialSupportWithoutByteAlignment() {
        DisplayCapabilities caps = new DisplayCapabilities(
            EnumSet.of(PixelFormat.MONOCHROME), EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
            true, Optional.of(new AlignmentConstraints(16)));
        assertThrows(IllegalArgumentException.class, () -> RenderSession.create(caps, MAPPING,
            RenderOptions.monochromeDefaults()));
    }

    @Test
    void createRejectsPartialSupportWithNoAlignmentDeclared() {
        DisplayCapabilities caps = new DisplayCapabilities(
            EnumSet.of(PixelFormat.MONOCHROME), EnumSet.of(RefreshMode.FULL, RefreshMode.PARTIAL),
            true, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> RenderSession.create(caps, MAPPING,
            RenderOptions.monochromeDefaults()));
    }
}
