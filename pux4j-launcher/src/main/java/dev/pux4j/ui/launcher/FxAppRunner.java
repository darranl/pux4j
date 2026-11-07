// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import dev.pux4j.ui.fx.EInkBridge;
import dev.pux4j.ui.fx.SceneContent;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/** Runs a {@link SceneContent} on an {@link EInkBridge}. The only launcher class that uses JavaFX. */
final class FxAppRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FxAppRunner.class);

    private FxAppRunner() {
    }

    /**
     * Starts the toolkit and the bridge and shows the app. Normally never returns (the process
     * ends with the emulator window or a signal); returns {@code 1} only if the app failed to start.
     */
    static int run(SceneContent content) throws Exception {
        LOG.info("Launching '{}'", content.name());
        EInkLauncher.applyDefaultProperties(content.defaultProperties());

        // Toolkit first (drivers such as the emulator open windows through it), but drivers
        // are constructed here, off the FX thread — see EInkBridge's class javadoc.
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);

        EInkBridge bridge;
        try {
            bridge = EInkBridge.fromConfig();
        } catch (RuntimeException e) {
            Platform.exit();
            throw e;
        }
        // Idempotent; runs on Ctrl-C/SIGTERM so the panel is put to sleep.
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "launcher-shutdown"));

        CountDownLatch failed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = content.createRoot();
                bridge.start(new Scene(root, bridge.displayWidth(), bridge.displayHeight()));
            } catch (Throwable t) {
                LOG.error("Failed to start '{}'", content.name(), t);
                failed.countDown();
            }
        });
        // Normal exit is the emulator window closing or a signal; only a failed start returns.
        failed.await();
        bridge.stop();
        Platform.exit();
        return 1;
    }
}
