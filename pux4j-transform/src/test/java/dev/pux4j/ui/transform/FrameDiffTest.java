// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDiffTest {

    private static MonochromeFrame allWhite(int fbRowBytes, int fbH) {
        byte[] data = new byte[fbRowBytes * fbH];
        Arrays.fill(data, (byte) 0xFF);
        return new MonochromeFrame(data, RefreshMode.FULL);
    }

    private static MonochromeFrame withByteChanged(MonochromeFrame base, int fbRowBytes, int row, int byteCol) {
        byte[] data = base.data().clone();
        int idx = row * fbRowBytes + byteCol;
        data[idx] = (byte) (data[idx] ^ 0x80); // flip the leftmost pixel of that byte
        return new MonochromeFrame(data, RefreshMode.FULL);
    }

    /**
     * The base case everything else builds on: two byte-identical frames must report zero
     * changed regions, not a region covering nothing. {@code RenderSession} relies on an empty
     * list here to decide "skip the driver write entirely" — a diff algorithm that returned a
     * zero-area region instead would silently defeat that optimisation.
     */
    @Test
    void identicalFramesProduceNoRegions() {
        int fbW = 16, fbH = 8, fbRowBytes = 2;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        MonochromeFrame next = allWhite(fbRowBytes, fbH);
        assertTrue(FrameDiff.diff(prev, next, fbW, fbH, 2, 4).isEmpty());
    }

    @Test
    void singleChangedByteProducesOneByteWideRegion() {
        int fbW = 16, fbH = 8, fbRowBytes = 2;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        MonochromeFrame next = withByteChanged(prev, fbRowBytes, 3, 1); // row 3, byte col 1 (px 8-15)

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 2, 4);

        assertEquals(1, regions.size());
        assertEquals(new Region(8, 3, 8, 1), regions.get(0));
    }

    @Test
    void changesWithinMergeThresholdBridgeIntoOneBand() {
        int fbW = 16, fbH = 10, fbRowBytes = 2;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        byte[] data = prev.data().clone();
        data[2 * fbRowBytes] = (byte) 0x80;      // row 2 changed
        data[5 * fbRowBytes] = (byte) 0x80;      // row 5 changed — gap of 2 unchanged rows (3,4)
        MonochromeFrame next = new MonochromeFrame(data, RefreshMode.FULL);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 2, 4); // mergeThresholdPx=2 bridges the gap

        assertEquals(1, regions.size());
        assertEquals(new Region(0, 2, 8, 4), regions.get(0)); // spans rows 2..5 inclusive
    }

    @Test
    void changesBeyondMergeThresholdStaySeparateBands() {
        int fbW = 16, fbH = 10, fbRowBytes = 2;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        byte[] data = prev.data().clone();
        data[2 * fbRowBytes] = (byte) 0x80;      // row 2 changed
        data[9 * fbRowBytes] = (byte) 0x80;      // row 9 changed — gap of 6 unchanged rows, > threshold
        MonochromeFrame next = new MonochromeFrame(data, RefreshMode.FULL);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 2, 4);

        assertEquals(2, regions.size());
    }

    @Test
    void collapsingToOneRegionAlwaysProducesTheBoundingBoxOfEverything() {
        int fbW = 32, fbH = 20, fbRowBytes = 4;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        byte[] data = prev.data().clone();
        data[1 * fbRowBytes]     = (byte) 0x80; // row 1 — isolated band
        data[15 * fbRowBytes]    = (byte) 0x80; // row 15 — far away, isolated band
        MonochromeFrame next = new MonochromeFrame(data, RefreshMode.FULL);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 0, 1);

        assertEquals(1, regions.size(), "maxRegions=1 must collapse everything to one bounding box");
        Region only = regions.get(0);
        assertEquals(1, only.y());
        assertEquals(15, only.y() + only.height() - 1);
    }

    /**
     * The previous version of this test only ever had two bands to collapse with
     * {@code maxRegions=1}, so there was only one possible pair — the "cheapest pair" search
     * itself (picking which two of three-or-more candidates to merge) was never actually
     * exercised. Three bands (A: row 1, B: row 15, C: row 16) with {@code maxRegions=2}: merging
     * B and C (adjacent rows, small bounding box) is far cheaper than merging either with the
     * distant A, so the correct collapse leaves A untouched and merges B+C.
     */
    @Test
    void collapsingToTwoRegionsMergesTheCheapestPairNotAnArbitraryOne() {
        int fbW = 32, fbH = 20, fbRowBytes = 4;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        byte[] data = prev.data().clone();
        data[1 * fbRowBytes]  = (byte) 0x80; // band A — isolated
        data[15 * fbRowBytes] = (byte) 0x80; // band B
        data[17 * fbRowBytes] = (byte) 0x80; // band C — gap of 1 unchanged row from B (row 16), separate band at mergeThreshold=0
        MonochromeFrame next = new MonochromeFrame(data, RefreshMode.FULL);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 0, 2);

        assertEquals(2, regions.size());
        assertEquals(new Region(0, 1, 8, 1), regions.get(0), "band A must be left untouched — it's not part of the cheapest pair");
        assertEquals(new Region(0, 15, 8, 3), regions.get(1), "bands B and C merged into their bounding box (rows 15..17)");
    }

    /**
     * Three evenly-spaced bands (rows 1, 3, 5) give two pairs — (A,B) and (B,C) — an identical
     * merge cost, both cheaper than the third pair (A,C). {@code tier2-transform.md} specifies
     * the tie-break as "lowest band-index pair" specifically so this collapse is deterministic
     * and tests of it don't flake; this test pins that (A,B) wins the tie, not (B,C).
     */
    @Test
    void tiedMergeCostsBreakTowardsTheLowestIndexPair() {
        int fbW = 32, fbH = 8, fbRowBytes = 4;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        byte[] data = prev.data().clone();
        data[1 * fbRowBytes] = (byte) 0x80; // band A
        data[3 * fbRowBytes] = (byte) 0x80; // band B — cost(A,B) == cost(B,C)
        data[5 * fbRowBytes] = (byte) 0x80; // band C

        MonochromeFrame next = new MonochromeFrame(data, RefreshMode.FULL);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 0, 2);

        assertEquals(2, regions.size());
        assertEquals(new Region(0, 1, 8, 3), regions.get(0), "A and B merged (the lower-index tied pair), not B and C");
        assertEquals(new Region(0, 5, 8, 1), regions.get(1), "C left untouched");
    }

    @Test
    void clampingCanLeaveRegionWidthShortOfAByteMultiple() {
        // 11px wide panel: byte column 1 covers native pixels 8-15, but only 8-10 exist.
        int fbW = 11, fbH = 4, fbRowBytes = 2;
        MonochromeFrame prev = allWhite(fbRowBytes, fbH);
        MonochromeFrame next = withByteChanged(prev, fbRowBytes, 0, 1);

        List<Region> regions = FrameDiff.diff(prev, next, fbW, fbH, 2, 4);

        assertEquals(1, regions.size());
        Region r = regions.get(0);
        assertEquals(8, r.x(), "x must still land on a byte boundary");
        assertEquals(3, r.width(), "width is clamped to what's left of the framebuffer (11-8), not padded back to 8");
        assertEquals(11, r.x() + r.width());
    }

    @Test
    void maxRegionsBelowOneIsRejected() {
        MonochromeFrame f = allWhite(2, 8);
        assertThrows(IllegalArgumentException.class, () -> FrameDiff.diff(f, f, 16, 8, 2, 0));
    }

    @Test
    void mismatchedFrameSizeIsRejected() {
        MonochromeFrame prev = allWhite(2, 8);
        MonochromeFrame next = allWhite(3, 8);
        assertThrows(IllegalArgumentException.class, () -> FrameDiff.diff(prev, next, 16, 8, 2, 4));
    }
}
