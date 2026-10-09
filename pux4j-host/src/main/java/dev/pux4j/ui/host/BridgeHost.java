// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.host;

import dev.pux4j.ui.fx.EInkBridge;
import dev.pux4j.ui.fx.SceneContent;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;

/**
 * Hosts a {@link SceneContent} provider on an {@link EInkBridge}. Driver selection is
 * {@link EInkBridge#fromConfig()} (the {@code pux4j.*} system properties plus whichever driver
 * modules are on the module path); the application is the {@code SceneContent} provider found
 * on the module path, chosen with {@code -Dpux4j.app=<name>} when there is more than one.
 *
 * <p>The main thread stays alive for the whole run: drivers such as the emulator's tie their
 * window's lifetime to the thread that created them.
 */
public final class BridgeHost {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeHost.class);

    private BridgeHost() {
    }

    public static void main(String[] args) throws Exception {
        SceneContent content = selectContent();
        LOG.info("Hosting '{}'", content.name());

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
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "host-shutdown"));

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
        System.exit(1);
    }

    private static SceneContent selectContent() {
        String wanted = System.getProperty("pux4j.app");
        List<SceneContent> found = new ArrayList<>();
        ServiceLoader.load(SceneContent.class).forEach(found::add);
        List<String> names = found.stream().map(SceneContent::name).toList();
        if (wanted != null) {
            return found.stream().filter(c -> c.name().equals(wanted)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No SceneContent named '" + wanted + "'; available: " + names));
        }
        if (found.size() != 1) {
            throw new IllegalStateException(found.isEmpty()
                    ? "No SceneContent provider on the module path"
                    : "Several SceneContent providers " + names + "; choose with -Dpux4j.app=<name>");
        }
        return found.get(0);
    }
}
