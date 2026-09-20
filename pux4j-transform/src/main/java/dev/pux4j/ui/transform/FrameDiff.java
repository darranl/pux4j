// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.MonochromeFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless byte-level diff between two packed {@link MonochromeFrame}s, in native framebuffer
 * space. Returns at most {@code maxRegions} candidate {@link Region}s, already x-aligned to
 * whole bytes (the algorithm works entirely in byte columns — see {@code tier2-transform.md}'s
 * "Diff and region selection" section for the full rationale).
 *
 * <p>Typed to {@link MonochromeFrame} rather than the general {@code FrameData} sealed interface
 * deliberately: {@code FourGrayFrame} has no partial-refresh path anywhere in this stack (no
 * driver's {@code writeRegion} accepts one), so there is no meaningful diff/region computation
 * for it to return — {@link RenderSession} short-circuits FOUR_GRAY before ever reaching this
 * class, and a caller trying to diff two {@code FourGrayFrame}s is now a compile error instead
 * of a runtime question this class would otherwise have to answer.
 */
public final class FrameDiff {

    private FrameDiff() {}

    /**
     * @param previous          the prior packed frame
     * @param next               the new packed frame; must be the same size as {@code previous}
     * @param framebufferWidth  native framebuffer width in pixels
     * @param framebufferHeight native framebuffer height in pixels
     * @param mergeThresholdPx  when forming a vertical band, the max run of consecutive
     *                          unchanged rows to bridge before ending the band (lets a blank
     *                          line inside a paragraph stay in one band)
     * @param maxRegions        collapse bands down to at most this many regions by repeatedly
     *                          merging the pair whose bounding box adds the least area
     * @return candidate regions, at most {@code maxRegions}, each clamped to the framebuffer
     *         bounds (which can leave {@code width} short of a multiple of 8 — see
     *         {@link Region}'s javadoc)
     */
    public static List<Region> diff(MonochromeFrame previous, MonochromeFrame next,
                                     int framebufferWidth, int framebufferHeight,
                                     int mergeThresholdPx, int maxRegions) {
        if (maxRegions < 1) {
            throw new IllegalArgumentException("maxRegions must be >= 1, got " + maxRegions);
        }
        byte[] prevData = previous.data();
        byte[] nextData = next.data();
        int fbRowBytes = (framebufferWidth + 7) / 8;
        if (prevData.length != fbRowBytes * framebufferHeight || nextData.length != prevData.length) {
            throw new IllegalArgumentException("frame data length does not match "
                + framebufferWidth + "x" + framebufferHeight + " (fbRowBytes=" + fbRowBytes + ")");
        }

        List<Region> bands = formBands(prevData, nextData, fbRowBytes, framebufferHeight, mergeThresholdPx);
        List<Region> collapsed = collapseToMaxRegions(bands, maxRegions);
        List<Region> clamped = new ArrayList<>(collapsed.size());
        for (Region r : collapsed) {
            clamped.add(clampToBounds(r, framebufferWidth, framebufferHeight));
        }
        return clamped;
    }

    /**
     * Step 1-2: per-row min/max differing byte, then walk rows top to bottom collapsing into
     * vertical bands, bridging up to mergeThresholdPx consecutive unchanged rows within a band.
     */
    private static List<Region> formBands(byte[] prevData, byte[] nextData, int fbRowBytes,
                                           int framebufferHeight, int mergeThresholdPx) {
        List<Region> bands = new ArrayList<>();
        int bandTop = -1;
        int bandLastChangedRow = -1;
        int bandMinByte = -1;
        int bandMaxByte = -1;

        for (int y = 0; y < framebufferHeight; y++) {
            int rowMinByte = -1;
            int rowMaxByte = -1;
            int base = y * fbRowBytes;
            for (int byteX = 0; byteX < fbRowBytes; byteX++) {
                if (prevData[base + byteX] != nextData[base + byteX]) {
                    if (rowMinByte == -1) rowMinByte = byteX;
                    rowMaxByte = byteX;
                }
            }
            boolean rowChanged = rowMinByte != -1;

            if (rowChanged) {
                if (bandTop == -1) {
                    bandTop = y;
                    bandMinByte = rowMinByte;
                    bandMaxByte = rowMaxByte;
                } else {
                    bandMinByte = Math.min(bandMinByte, rowMinByte);
                    bandMaxByte = Math.max(bandMaxByte, rowMaxByte);
                }
                bandLastChangedRow = y;
            } else if (bandTop != -1 && (y - bandLastChangedRow) > mergeThresholdPx) {
                bands.add(new Region(bandMinByte * 8, bandTop, (bandMaxByte - bandMinByte + 1) * 8,
                    bandLastChangedRow - bandTop + 1));
                bandTop = -1;
            }
        }
        if (bandTop != -1) {
            bands.add(new Region(bandMinByte * 8, bandTop, (bandMaxByte - bandMinByte + 1) * 8,
                bandLastChangedRow - bandTop + 1));
        }
        return bands;
    }

    /**
     * Step 3: unconditionally collapse to maxRegions by repeatedly merging the pair whose
     * bounding box adds the least area (bbox area minus the sum of the two regions' own areas).
     * Ties break on the lowest-index pair for determinism.
     */
    private static List<Region> collapseToMaxRegions(List<Region> bands, int maxRegions) {
        List<Region> regions = new ArrayList<>(bands);
        while (regions.size() > maxRegions) {
            int bestI = -1;
            int bestJ = -1;
            long bestCost = Long.MAX_VALUE;
            for (int i = 0; i < regions.size(); i++) {
                for (int j = i + 1; j < regions.size(); j++) {
                    long cost = mergeCost(regions.get(i), regions.get(j));
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            Region merged = boundingBox(regions.get(bestI), regions.get(bestJ));
            regions.remove(bestJ);
            regions.set(bestI, merged);
        }
        return regions;
    }

    private static long mergeCost(Region a, Region b) {
        Region bbox = boundingBox(a, b);
        long bboxArea = (long) bbox.width() * bbox.height();
        long aArea = (long) a.width() * a.height();
        long bArea = (long) b.width() * b.height();
        return bboxArea - aArea - bArea;
    }

    static Region boundingBox(Region a, Region b) {
        int x = Math.min(a.x(), b.x());
        int y = Math.min(a.y(), b.y());
        int right = Math.max(a.x() + a.width(), b.x() + b.width());
        int bottom = Math.max(a.y() + a.height(), b.y() + b.height());
        return new Region(x, y, right - x, bottom - y);
    }

    /**
     * Step 4: clamp to framebuffer bounds. x is already a multiple of 8 by construction and
     * stays within bounds (a byte column can't start past the framebuffer); width can end up
     * short of a multiple of 8 here — that's expected, not an error (see Region's javadoc).
     */
    static Region clampToBounds(Region r, int framebufferWidth, int framebufferHeight) {
        int width = Math.min(r.width(), framebufferWidth - r.x());
        int height = Math.min(r.height(), framebufferHeight - r.y());
        return new Region(r.x(), r.y(), width, height);
    }
}
