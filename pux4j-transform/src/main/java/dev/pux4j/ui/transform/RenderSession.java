// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.AlignmentConstraints;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.FourGrayFrame;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.RefreshMode;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Stateful per-display render session. Holds the previous frame for diff and tracks
 * the consecutive partial refresh count.
 * Not thread-safe — the caller serialises access (display thread in {@code pux4j-fx};
 * isolate-local session in {@code pux4j-native}).
 */
public final class RenderSession {

    private final DisplayCapabilities caps;
    private final OrientationMapping  orientationMapping;
    private final RenderOptions       options;

    private FrameData previous;
    private int        partialCount;

    private RenderSession(DisplayCapabilities caps, OrientationMapping orientationMapping,
                          RenderOptions options) {
        this.caps               = caps;
        this.orientationMapping = orientationMapping;
        this.options            = options;
    }

    public static RenderSession create(DisplayCapabilities caps, OrientationMapping orientationMapping,
                                       RenderOptions options) {
        validate(caps, options);
        return new RenderSession(caps, orientationMapping, options);
    }

    /**
     * Transforms the logical-space BGRA buffer and diffs it against the previous frame.
     * Returns empty if nothing changed; returns present with the work item otherwise.
     *
     * @param bgra BYTE_BGRA pixel data, {@code orientationMapping.logicalWidth() *
     *             orientationMapping.logicalHeight() * 4} bytes
     */
    public Optional<RenderResult> prepare(MemorySegment bgra) {
        FrameData packed = PixelTransforms.transform(bgra, options.pixelFormat(), options.strategy(),
            orientationMapping, options.fourGrayBinBoundaries());

        return packed instanceof FourGrayFrame fg
            ? prepareFourGray(fg)
            : prepareMonochrome((MonochromeFrame) packed);
    }

    /** Discard frame history. Use after a hardware reset or sleep/wake cycle, when the
     * display's physical state no longer matches the last known frame. */
    public void reset() {
        previous = null;
        partialCount = 0;
    }

    /**
     * FOUR_GRAY has no partial-refresh representation anywhere in the stack (no RefreshMode
     * field, every writeRegion implementation rejects it) — short-circuit before any of the
     * diff/region machinery below, which is typed to MonochromeFrame precisely so it can't be
     * reached from here by accident.
     */
    private Optional<RenderResult> prepareFourGray(FourGrayFrame next) {
        if (previous instanceof FourGrayFrame prev
                && Arrays.equals(prev.bwPlane(), next.bwPlane())
                && Arrays.equals(prev.redPlane(), next.redPlane())) {
            return Optional.empty();
        }
        previous = next;
        return Optional.of(new RenderResult(next, new RefreshDecision(RefreshMode.FULL, Optional.empty())));
    }

    private Optional<RenderResult> prepareMonochrome(MonochromeFrame next) {
        if (!(previous instanceof MonochromeFrame prev)) {
            previous = next;
            return Optional.of(new RenderResult(next, new RefreshDecision(RefreshMode.FULL, Optional.empty())));
        }

        List<Region> candidates = FrameDiff.diff(prev, next,
            orientationMapping.framebufferWidth(), orientationMapping.framebufferHeight(),
            options.mergeThresholdPx(), options.maxRegions());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // RefreshDecision/RenderResult carry exactly one Region — options.maxRegions() > 1 only
        // controls how many bands FrameDiff.diff is allowed to return before collapsing; when
        // more than one still comes back, fold them into one bounding box here. (No driver in
        // this codebase supports more than one writeRegion() call per update; see
        // tier2-transform.md's "Refresh strategy application" for the multi-region caveat.)
        Region finalRegion = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            finalRegion = FrameDiff.boundingBox(finalRegion, candidates.get(i));
        }

        long changedArea = (long) finalRegion.width() * finalRegion.height();
        long panelArea = (long) orientationMapping.framebufferWidth() * orientationMapping.framebufferHeight();

        // The chosen RefreshMode is computed once, into this local, and used to build BOTH the
        // returned MonochromeFrame's mode() and the RefreshDecision's mode() below — see
        // tier2-transform.md's "Refresh mode is set in exactly one place" for why that
        // structural tie (not just a convention) is what keeps the two fields from disagreeing.
        RefreshMode mode;
        Optional<Region> decisionRegion;
        if (!caps.supportedRefreshModes().contains(RefreshMode.PARTIAL)
                || changedArea > options.fullRefreshThreshold() * panelArea
                || partialCount >= options.maxPartials()) {
            mode = RefreshMode.FULL;
            partialCount = 0;
            decisionRegion = Optional.empty();
        } else {
            mode = RefreshMode.PARTIAL;
            partialCount++;
            decisionRegion = Optional.of(finalRegion);
        }
        if (mode == RefreshMode.FULL && options.preferFastRefresh()
                && caps.supportedRefreshModes().contains(RefreshMode.FAST)) {
            mode = RefreshMode.FAST;
        }

        // A PARTIAL result gets its own freshly-cropped array (cropRegion always allocates).
        // A FULL/FAST result deliberately reuses next.data() as-is — the same array this
        // session then retains below as `previous`, the next call's diff baseline. No code in
        // this codebase mutates a MonochromeFrame's data after construction, so this aliasing
        // is safe today, but it means RenderResult.frame().data() must be treated as read-only
        // by every caller (a `pux4j-native` caller handing this array to native code should
        // copy it first, not write through it).
        byte[] resultData = decisionRegion.isPresent()
            ? cropRegion(next.data(), orientationMapping.framebufferWidth(), finalRegion)
            : next.data();

        previous = next;
        return Optional.of(new RenderResult(new MonochromeFrame(resultData, mode),
            new RefreshDecision(mode, decisionRegion)));
    }

    /**
     * Region frame contract: only region.x() is guaranteed a multiple of 8 (see Region's
     * javadoc) — region.width() may be short of one after framebuffer-bounds clamping. The crop
     * is still a whole-byte, no-shift copy: x/8 is exact, so each row copies ceil(width/8)
     * consecutive bytes starting there.
     */
    private static byte[] cropRegion(byte[] fullFrameNative, int framebufferWidth, Region region) {
        if (region.x() % 8 != 0) {
            throw new AssertionError("Region.x() must be a multiple of 8, got " + region.x());
        }
        int srcRowBytes = (framebufferWidth + 7) / 8;
        int dstRowBytes = (region.width() + 7) / 8;
        int srcByteOffset = region.x() / 8;
        byte[] out = new byte[dstRowBytes * region.height()];
        for (int row = 0; row < region.height(); row++) {
            int srcBase = (region.y() + row) * srcRowBytes + srcByteOffset;
            System.arraycopy(fullFrameNative, srcBase, out, row * dstRowBytes, dstRowBytes);
        }
        return out;
    }

    private static void validate(DisplayCapabilities caps, RenderOptions options) {
        PixelFormat fmt      = options.pixelFormat();
        TransformStrategy st = options.strategy();

        if (!caps.supports(fmt)) {
            throw new IllegalArgumentException(
                "Display does not support pixel format " + fmt);
        }
        if (fmt != PixelFormat.MONOCHROME && fmt != PixelFormat.FOUR_GRAY) {
            throw new IllegalArgumentException(
                "PixelFormat " + fmt + " is not supported by pux4j-transform (only MONOCHROME and FOUR_GRAY)");
        }
        boolean monoStrategy = st == TransformStrategy.THRESHOLD
            || st == TransformStrategy.FLOYD_STEINBERG
            || st == TransformStrategy.ORDERED_2X2;
        boolean fourGrayStrategy = st == TransformStrategy.FOUR_GRAY_BINNING;

        if (fmt == PixelFormat.MONOCHROME && fourGrayStrategy) {
            throw new IllegalArgumentException(
                "FOUR_GRAY_BINNING strategy requires FOUR_GRAY pixel format");
        }
        if (fmt == PixelFormat.FOUR_GRAY && monoStrategy) {
            throw new IllegalArgumentException(
                "Strategy " + st + " requires MONOCHROME pixel format");
        }

        if (caps.supportedRefreshModes().contains(RefreshMode.PARTIAL)) {
            AlignmentConstraints alignment = caps.partialAlignment().orElseThrow(() ->
                new IllegalArgumentException(
                    "Display supports PARTIAL refresh but declares no partialAlignment()"));
            if (alignment.xStepPx() != 8) {
                throw new IllegalArgumentException(
                    "pux4j-transform only supports byte-aligned partial refresh (xStepPx == 8); "
                        + "display declares xStepPx == " + alignment.xStepPx());
            }
        }
    }
}
