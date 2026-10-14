// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.ProgrammaticTouchSource;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.core.TouchPoint;
import dev.pux4j.ui.fx.EInkBridge;
import dev.pux4j.ui.fx.SceneContent;
import dev.pux4j.ui.transform.RenderOptions;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Shared harness for the headless "real app on the bridge" checks. For each real panel geometry
 * it builds an {@link EInkBridge} on the headless PNG display driver and the programmatic touch
 * driver, loads the {@link SceneContent} with the requested name (found via {@link ServiceLoader}),
 * starts the bridge, and hands a {@link Session} to an app-specific {@link AppScript} that taps
 * the real controls and asserts on the result. Frames are written as PNGs under
 * {@code target/<checkName>/<panel>/} so a human can look at them.
 *
 * <p>Monochrome render options are used for both panels: the real 2.13" is monochrome-only, and
 * the demos' looks are identical in the mono path on the 2.9".
 */
public final class BridgeCheckHarness {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeCheckHarness.class);

    static final long CAPTURE_INTERVAL_MS = 100L;
    static final long CONDITION_TIMEOUT_MS = 5_000L;
    static final long NO_WRITE_TIMEOUT_MS = 3 * CAPTURE_INTERVAL_MS + 500L;

    /** App-specific part of a check: drive and assert on one hosted panel. */
    @FunctionalInterface
    public interface AppScript {
        void run(Session session) throws Exception;
    }

    private record PanelConfig(String name, int nativeWidth, int nativeHeight, Orientation orientation) {}

    /** Everything a script needs for one panel. */
    public record Session(Scene scene, EInkBridge bridge, RecordingDisplayDriver recording,
                          ProgrammaticTouchSource touch, Path outputDir, int width, int height) {}

    private BridgeCheckHarness() {}

    /**
     * Runs {@code script} against the content named {@code contentName} on every panel, then
     * exits the JVM: 0 if all panels pass, 1 otherwise.
     */
    public static void runAndExit(String checkName, String contentName, AppScript script) throws Exception {
        CountDownLatch startupLatch = new CountDownLatch(1);
        Platform.startup(startupLatch::countDown);
        Platform.setImplicitExit(false);
        if (!startupLatch.await(10, TimeUnit.SECONDS)) {
            System.err.println("JavaFX toolkit did not start within 10s — no display server?");
            System.exit(1);
            return;
        }

        List<PanelConfig> panels = List.of(
                new PanelConfig("ssd1675a-2in9", 128, 296, Orientation.LANDSCAPE),
                new PanelConfig("ssd1680-2in13", 122, 250, Orientation.LANDSCAPE_INVERTED));

        List<String> failures = new ArrayList<>();
        for (PanelConfig panel : panels) {
            try {
                runPanel(checkName, contentName, script, panel);
                LOG.info("PASS: {}", panel.name());
            } catch (Throwable t) {
                LOG.error("FAIL: {}", panel.name(), t);
                failures.add(panel.name() + ": " + t);
            }
        }
        Platform.exit();

        if (!failures.isEmpty()) {
            System.err.println(checkName + " FAILED:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println(checkName + " PASSED for all " + panels.size() + " panel(s). Frames in "
                + baseOutputDir(checkName).toAbsolutePath());
        System.exit(0);
    }

    private static void runPanel(String checkName, String contentName, AppScript script, PanelConfig panel)
            throws Exception {
        LOG.info("=== {} (native {}x{}, {}) ===", panel.name(), panel.nativeWidth(), panel.nativeHeight(),
                panel.orientation());

        Path outputDir = baseOutputDir(checkName).resolve(panel.name());
        prepareOutputDir(outputDir);

        DriverConfig config = DriverConfig.builder()
                .property("width", panel.nativeWidth())
                .property("height", panel.nativeHeight())
                .property("orientation", panel.orientation().name())
                .property("outputDir", outputDir.toString())
                .build();

        EInkDisplayDriver pngDriver = DisplayDriverFactory.select("png").create(null, config);
        pngDriver.initialize();
        RecordingDisplayDriver recording = new RecordingDisplayDriver(pngDriver);

        TouchDriver touchDriver = TouchDriverFactory.select("programmatic").create(null, config);
        touchDriver.initialize();
        ProgrammaticTouchSource touchSource = (ProgrammaticTouchSource) touchDriver;

        EInkBridge bridge = EInkBridge.builder()
                .display(recording)
                .touchInLogicalSpace(touchDriver)
                .renderOptions(RenderOptions.monochromeDefaults())
                .captureIntervalMs(CAPTURE_INTERVAL_MS)
                .build();

        int w = bridge.displayWidth();
        int h = bridge.displayHeight();
        LOG.info("Logical size {}x{}", w, h);

        SceneContent content = selectContent(contentName);
        AtomicReference<Scene> sceneRef = new AtomicReference<>();
        callAndWaitOnFx(() -> {
            sceneRef.set(new Scene(content.createRoot(), w, h));
            return null;
        });
        Scene scene = sceneRef.get();

        try {
            runAndWaitOnFx(() -> bridge.start(scene));
            script.run(new Session(scene, bridge, recording, touchSource, outputDir, w, h));
            runAndWaitOnFx(bridge::stop);
            bridge.stop();
        } finally {
            try {
                bridge.stop();
            } catch (RuntimeException e) {
                LOG.warn("bridge.stop() in the cleanup path also failed", e);
            }
        }
    }

    // -------------------------------------------------------------------------

    record Rect(int x, int y, int w, int h) {
        int cx() { return x + w / 2; }
        int cy() { return y + h / 2; }
        @Override public String toString() { return "[" + x + "," + y + " " + w + "x" + h + "]"; }
    }

    static Rect rectOf(Node node) throws Exception {
        return callAndWaitOnFx(() -> {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            return new Rect((int) Math.round(b.getMinX()), (int) Math.round(b.getMinY()),
                    (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()));
        });
    }

    static void tap(ProgrammaticTouchSource source, int id, Rect target) {
        source.queueTouch(new TouchPoint(id, target.cx(), target.cy(), true));
        source.queueTouch(new TouchPoint(id, target.cx(), target.cy(), false));
    }

    static String textOf(Label label) {
        try {
            return callAndWaitOnFx(label::getText);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T extends Node> T lookup(Scene scene, String selector, Class<T> type) throws Exception {
        Node node = callAndWaitOnFx(() -> scene.getRoot().lookup(selector));
        check(type.isInstance(node), "no " + type.getSimpleName() + " matching " + selector + " in the scene");
        return type.cast(node);
    }

    private static SceneContent selectContent(String name) {
        for (SceneContent c : ServiceLoader.load(SceneContent.class)) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalStateException("no SceneContent named '" + name + "' on the module path");
    }

    static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    static BufferedImage decodePng(Path outputDir, int sequence) throws IOException {
        Path file = outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", sequence));
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Could not decode " + file + " as an image");
        }
        return image;
    }

    static int grayAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFF;
    }

    private static Path baseOutputDir(String checkName) {
        String override = System.getProperty("pux4j.demoCheck.outputDir");
        if (override != null) {
            return Path.of(override, checkName);
        }
        try {
            Path codeSource = Path.of(
                    BridgeCheckHarness.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return codeSource.getParent().resolve(checkName);
        } catch (Exception e) {
            return Path.of("target", checkName);
        }
    }

    static void prepareOutputDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Files.createDirectories(dir);
    }

    static void runAndWaitOnFx(Runnable action) throws Exception {
        callAndWaitOnFx(() -> {
            action.run();
            return null;
        });
    }

    static <T> T callAndWaitOnFx(Callable<T> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(CONDITION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("FX-thread action did not complete within " + CONDITION_TIMEOUT_MS + "ms");
        }
        if (failure.get() != null) {
            throw new Exception("FX-thread action failed", failure.get());
        }
        return result.get();
    }

    static boolean awaitCondition(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void checkEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
