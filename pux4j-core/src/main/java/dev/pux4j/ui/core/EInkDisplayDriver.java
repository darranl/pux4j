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
     */
    CompletableFuture<Void> writeFrame(FrameData frame);

    /**
     * Write a sub-region and trigger a partial refresh.
     * Coordinates must satisfy {@link DisplayCapabilities#partialAlignment()}.
     * Throws {@link UnsupportedOperationException} if the driver does not support
     * partial refresh or if {@code frame} is a {@link FourGrayFrame}.
     */
    CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame);
}
