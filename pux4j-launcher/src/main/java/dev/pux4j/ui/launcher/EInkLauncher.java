// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

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
 * Runs a {@link SceneContent} app on an {@link EInkBridge}. Driver selection is
 * {@link EInkBridge#fromConfig()} (the {@code pux4j.*} system properties plus whichever driver
 * modules are on the module path); the app is a {@code SceneContent} provider found on the
 * module path, chosen by the first argument (or {@code -Dpux4j.app=<name>}) when there is more
 * than one. {@code --list} prints the available app names and exits.
 *
 * <p>The main thread stays alive for the whole run: drivers such as the emulator's tie their
 * window's lifetime to the thread that created them.
 */
public final class EInkLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(EInkLauncher.class);

    private EInkLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--list")) {
            findContent().forEach(c -> System.out.println(c.name()));
            return;
        }
        if (args.length > 0 && !args[0].startsWith("-")) {
            System.setProperty("pux4j.app", args[0]);
        }
        SceneContent content = selectContent();
        LOG.info("Launching '{}'", content.name());

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
        System.exit(1);
    }

    private static List<SceneContent> findContent() {
        List<SceneContent> found = new ArrayList<>();
        ServiceLoader.load(SceneContent.class).forEach(found::add);
        return found;
    }

    private static SceneContent selectContent() {
        String wanted = System.getProperty("pux4j.app");
        List<SceneContent> found = findContent();
        List<String> names = found.stream().map(SceneContent::name).toList();
        if (wanted != null) {
            return found.stream().filter(c -> c.name().equals(wanted)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No SceneContent named '" + wanted + "'; available: " + names));
        }
        if (found.size() != 1) {
            throw new IllegalStateException(found.isEmpty()
                    ? "No SceneContent provider on the module path"
                    : "Several SceneContent providers " + names + "; choose one by name (first argument)");
        }
        return found.get(0);
    }
}
