// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshPolicy;
import dev.pux4j.ui.core.RefreshStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorator that logs two INFO lines for the first frame an app sends to the display, so a
 * script can measure time to first screen from outside the process: {@code first-frame-handed}
 * when the first write reaches the driver, {@code first-frame-written} when that write completes
 * (on a real panel that is after the refresh, about 3 s for a 4-gray HAT refresh). Each line
 * carries {@code epochMs}, the wall-clock time in milliseconds, so it can be compared with a
 * {@code date +%s%3N} taken before launch. Everything else is forwarded unchanged.
 */
public final class FirstFrameMarkersDriver implements EInkDisplayDriver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FirstFrameMarkersDriver.class);

    private final EInkDisplayDriver delegate;
    private final AtomicBoolean first = new AtomicBoolean(true);

    public FirstFrameMarkersDriver(EInkDisplayDriver delegate) {
        this.delegate = delegate;
    }

    @Override public int getWidth() { return delegate.getWidth(); }
    @Override public int getHeight() { return delegate.getHeight(); }
    @Override public Orientation getOrientation() { return delegate.getOrientation(); }
    @Override public DisplayCapabilities getCapabilities() { return delegate.getCapabilities(); }
    @Override public void initialize() { delegate.initialize(); }
    @Override public void reset() { delegate.reset(); }
    @Override public void sleep() { delegate.sleep(); }
    @Override public void wake() { delegate.wake(); }
    @Override public RefreshStats getRefreshStats() { return delegate.getRefreshStats(); }
    @Override public void setRefreshPolicy(RefreshPolicy policy) { delegate.setRefreshPolicy(policy); }

    @Override
    public CompletableFuture<Void> writeFrame(FrameData frame) {
        if (!first.getAndSet(false)) {
            return delegate.writeFrame(frame);
        }
        return mark(delegate::writeFrame, frame);
    }

    @Override
    public CompletableFuture<Void> writeRegion(int x, int y, int width, int height, FrameData frame) {
        if (!first.getAndSet(false)) {
            return delegate.writeRegion(x, y, width, height, frame);
        }
        return mark(f -> delegate.writeRegion(x, y, width, height, f), frame);
    }

    private CompletableFuture<Void> mark(java.util.function.Function<FrameData, CompletableFuture<Void>> write,
                                         FrameData frame) {
        long handedNs = System.nanoTime();
        log.info("first-frame-handed epochMs={}", System.currentTimeMillis());
        return write.apply(frame).whenComplete((v, error) -> {
            if (error == null) {
                log.info("first-frame-written epochMs={} writeMs={}", System.currentTimeMillis(),
                    (System.nanoTime() - handedNs) / 1_000_000);
            } else {
                log.warn("first-frame write failed: {}", error.toString());
            }
        });
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
