// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.PixelFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Before this class existed, every value here validated only where it was first used deep
 * inside {@link FrameDiff}/{@link PixelTransforms} — often not on the first {@code prepare()}
 * call, sometimes not at all (a non-ascending {@code fourGrayBinBoundaries} array silently
 * produced an unreachable grey bin with no error). {@code tier2-transform.md}'s own testing
 * section states the principle: invalid configuration must fail at construction time, not
 * three calls later from an unrelated stack trace. These tests pin {@link RenderOptions.Builder#build()}
 * as the one place that happens.
 */
class RenderOptionsTest {

    private static RenderOptions.Builder validBuilder() {
        return RenderOptions.builder().pixelFormat(PixelFormat.MONOCHROME).strategy(TransformStrategy.THRESHOLD);
    }

    @Test
    void validDefaultsBuildSuccessfully() {
        validBuilder().build(); // must not throw
    }

    @Test
    void maxRegionsBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().maxRegions(0).build());
    }

    @Test
    void maxPartialsBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().maxPartials(0).build());
    }

    @Test
    void negativeMergeThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().mergeThresholdPx(-1).build());
    }

    @Test
    void fullRefreshThresholdOutsideZeroToOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> validBuilder().fullRefreshThreshold(-0.01f).build());
        assertThrows(IllegalArgumentException.class, () -> validBuilder().fullRefreshThreshold(1.01f).build());
    }

    @Test
    void fourGrayBoundariesWithWrongLengthIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().fourGrayBinBoundaries(new float[]{64f, 128f}).build());
    }

    /**
     * A non-ascending boundaries array (e.g. a transcription mistake during Q2's hardware
     * recalibration — see {@code tier2-transform.md}) previously wasn't caught at all:
     * {@code packFourGray}'s cascading comparisons just produce an unreachable bin silently.
     */
    @Test
    void nonAscendingFourGrayBoundariesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().fourGrayBinBoundaries(new float[]{192f, 128f, 64f}).build());
        assertThrows(IllegalArgumentException.class,
            () -> validBuilder().fourGrayBinBoundaries(new float[]{64f, 64f, 192f}).build());
    }
}
