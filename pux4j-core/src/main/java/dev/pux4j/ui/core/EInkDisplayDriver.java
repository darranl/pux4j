// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import java.util.concurrent.CompletableFuture;

public interface EInkDisplayDriver {

    int getWidth();
    int getHeight();
    Orientation getOrientation();
    DisplayCapabilities getCapabilities();

    void initialize();
    void reset();
    void sleep();
    void wake();

    /**
     * Write pixel data and trigger a display refresh.
     * The SPI transfer and refresh command execute synchronously on the calling thread
     * before the future is returned. The future completes when BUSY clears.
     *
     * <p>Before dispatching, the driver evaluates the installed {@link RefreshPolicy}.
     * If the policy returns {@code true}, a FULL refresh is performed instead of the
     * requested mode, and the refresh counters are reset.
     */
    CompletableFuture<Void> writeFrame(FrameData frame);

    /**
     * Write a sub-region and trigger a partial refresh.
     * Coordinates must satisfy {@link DisplayCapabilities#partialAlignment()}.
     * Throws {@link UnsupportedOperationException} if the driver does not support
     * partial refresh or if {@code frame} is a {@link FourGrayFrame}.
     */
    CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame);

    /**
     * Returns a snapshot of the driver's refresh activity counters.
     * Counters reset to zero after every FULL refresh.
     */
    default RefreshStats getRefreshStats() {
        return new RefreshStats(0, 0, 0L);
    }

    /**
     * Installs a {@link RefreshPolicy} that the driver evaluates before each
     * {@link #writeFrame} call. The default policy is {@link RefreshPolicy#NEVER}.
     *
     * <p>Use {@link RefreshPolicy#afterPartials(int)} for threshold-based upgrades,
     * or supply a custom lambda to compose any behaviour you need.
     */
    default void setRefreshPolicy(RefreshPolicy policy) {
        // no-op for drivers that do not implement policy
    }
}

