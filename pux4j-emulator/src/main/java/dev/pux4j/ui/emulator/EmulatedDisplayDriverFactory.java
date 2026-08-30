// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Pux4jContext;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * ServiceLoader factory that creates a JavaFX-based eInk emulator window.
 *
 * <p>Selected automatically on any machine that is not a Raspberry Pi (priority 50,
 * always available). On Raspberry Pi the hardware drivers (priority 100) take precedence.
 *
 * <p>Reads two system properties:
 * <ul>
 *   <li>{@code pux4j.emulator.display} — display profile name ({@code ssd1675a} or
 *       {@code ssd1680}); default {@code ssd1675a}</li>
 *   <li>{@code pux4j.emulator.scale} — integer scale factor applied to the canvas;
 *       default {@code 3}</li>
 * </ul>
 *
 * <p>Both the emulated display driver and the emulated touch driver are created here and
 * shared via {@link EmulatorContext}. The touch factory ({@link EmulatedTouchDriverFactory})
 * must be invoked after this factory.
 */
public final class EmulatedDisplayDriverFactory implements DisplayDriverFactory {

    private static final Logger log = LoggerFactory.getLogger(EmulatedDisplayDriverFactory.class);

    @Override
    public String name() { return "emulator"; }

    @Override
    public int priority() { return 50; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public EInkDisplayDriver create(Pux4jContext context, DriverConfig config) {
        EmulatorDisplayProfile profile = EmulatorDisplayProfile.forName(
            System.getProperty("pux4j.emulator.display", "ssd1675a"));
        int scale = Integer.parseInt(System.getProperty("pux4j.emulator.scale", "3"));

        log.debug("Creating emulated display: profile={} scale={}", profile.profileName, scale);

        var display = new EmulatedEInkDisplay(
            profile.nativeWidth(), profile.nativeHeight(), profile.orientation,
            profile.formats, profile.modes, scale);
        var touch = new EmulatedTouchDriver();

        EmulatorContext ctx = EmulatorContext.instance();
        ctx.setDisplay(display);
        ctx.setTouch(touch);

        startFxToolkit();
        openWindow(profile, display, touch);
        watchCallerThread(Thread.currentThread());

        return display;
    }

    private static void startFxToolkit() {
        var latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException ex) {
            // FX toolkit is already running (e.g., embedded in a JavaFX app)
            log.debug("JavaFX already running; skipping Platform.startup()");
            return;
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for JavaFX startup", e);
        }
        log.debug("JavaFX toolkit started");
    }

    /**
     * Starts a virtual daemon thread that watches {@code caller}. When the caller thread
     * terminates (normally or via exception), calls {@link Platform#exit()} to close the
     * emulator window and unblock the JavaFX thread so the JVM can exit cleanly.
     */
    private static void watchCallerThread(Thread caller) {
        Thread.ofVirtual().name("emulator-exit-watcher").start(() -> {
            try {
                caller.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.debug("Application thread finished; closing emulator window");
            Platform.exit();
        });
    }

    private static void openWindow(EmulatorDisplayProfile profile,
                                   EmulatedEInkDisplay display,
                                   EmulatedTouchDriver touch) {
        // Use CompletableFuture so any exception on the FX thread propagates to the caller
        // rather than being silently swallowed by the FX uncaught-exception handler.
        var future = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                var stage = new Stage();
                stage.setOnCloseRequest(event -> {
                    log.info("Emulator window closed by user — shutting down");
                    System.exit(0);
                });
                EInkEmulatorWindow.create(profile.displayLabel, display, touch, stage).show();
                log.debug("Emulator window opened: {}", profile.displayLabel);
                future.complete(null);
            } catch (Throwable t) {
                log.error("Failed to create emulator window", t);
                future.completeExceptionally(t);
            }
        });
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for emulator window", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to create emulator window", e.getCause());
        }
    }
}
