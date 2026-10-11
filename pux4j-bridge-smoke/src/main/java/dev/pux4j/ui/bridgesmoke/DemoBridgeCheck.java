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
import javafx.scene.control.Button;
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
 * Pre-flight check: hosts the <em>real</em> counter demo ({@code SceneContent} named
 * {@code demo}, found via {@link ServiceLoader}) on {@link EInkBridge} with the headless PNG
 * display driver and the programmatic touch driver, at both real panel geometries, and drives
 * it with taps on the actual "+" / "−" buttons. Where {@link BridgeSmokeTest} proves the
 * bridge with a synthetic scene, this proves the demo's own FXML/CSS layout fits 296×128 and
 * 250×122 and that touch, orientation and rendering agree end to end.
 *
 * <p>Frames are written as PNGs under {@code target/demo-bridge-check/<panel>/} so a human
 * can look at them. Exits 0 on success, 1 on failure. Run with {@code pux4j-bridge-smoke/run-demo-check.sh}.
 */
public final class DemoBridgeCheck {

    private static final Logger LOG = LoggerFactory.getLogger(DemoBridgeCheck.class);

    private static final long CAPTURE_INTERVAL_MS = 100L;
    private static final long CONDITION_TIMEOUT_MS = 5_000L;
    private static final long NO_WRITE_TIMEOUT_MS = 3 * CAPTURE_INTERVAL_MS + 500L;

    private record PanelConfig(String name, int nativeWidth, int nativeHeight, Orientation orientation) {}

    public static void main(String[] args) throws Exception {
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
                runPanel(panel);
                LOG.info("PASS: {}", panel.name());
            } catch (Throwable t) {
                LOG.error("FAIL: {}", panel.name(), t);
                failures.add(panel.name() + ": " + t);
            }
        }
        Platform.exit();

        if (!failures.isEmpty()) {
            System.err.println("DemoBridgeCheck FAILED:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("DemoBridgeCheck PASSED for all " + panels.size() + " panel(s). Frames in "
                + baseOutputDir().toAbsolutePath());
        System.exit(0);
    }

    private static void runPanel(PanelConfig panel) throws Exception {
        LOG.info("=== {} (native {}x{}, {}) ===", panel.name(), panel.nativeWidth(), panel.nativeHeight(),
                panel.orientation());

        Path outputDir = baseOutputDir().resolve(panel.name());
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

        // Monochrome for both panels: the real 2.13" is monochrome-only, and the 2.9" counter
        // look is identical in the mono path (no greys are used other than the disabled state).
        EInkBridge bridge = EInkBridge.builder()
                .display(recording)
                .touchInLogicalSpace(touchDriver)
                .renderOptions(RenderOptions.monochromeDefaults())
                .captureIntervalMs(CAPTURE_INTERVAL_MS)
                .build();

        int w = bridge.displayWidth();
        int h = bridge.displayHeight();
        LOG.info("Logical size {}x{}", w, h);

        SceneContent content = selectDemo();
        AtomicReference<Scene> sceneRef = new AtomicReference<>();
        callAndWaitOnFx(() -> {
            Scene scene = new Scene(content.createRoot(), w, h);
            sceneRef.set(scene);
            return null;
        });
        Scene scene = sceneRef.get();
        Button inc = lookup(scene, "#incBtn", Button.class);
        Button dec = lookup(scene, "#decBtn", Button.class);
        Label counter = lookup(scene, "#counterLabel", Label.class);

        try {
            runAndWaitOnFx(() -> bridge.start(scene));

            // --- Frame 1: initial layout ------------------------------------------------
            check(recording.awaitWriteCount(c -> c >= 1, CONDITION_TIMEOUT_MS),
                    "no initial frame within " + CONDITION_TIMEOUT_MS + "ms");
            checkEquals(RefreshMode.FULL, recording.events().get(0).mode(), "initial write must be FULL");

            Rect incR = rectOf(inc);
            Rect decR = rectOf(dec);
            Rect labelR = rectOf(counter);
            LOG.info("Layout: dec {}  label {}  inc {}", decR, labelR, incR);
            for (Rect r : List.of(incR, decR, labelR)) {
                check(r.x >= 0 && r.y >= 0 && r.x + r.w <= w && r.y + r.h <= h,
                        "node " + r + " does not fit inside the " + w + "x" + h + " scene");
            }
            check(decR.x + decR.w <= labelR.x + 1 && labelR.x + labelR.w <= incR.x + 1,
                    "expected left-to-right order dec | label | inc, got " + decR + " " + labelR + " " + incR);
            check(labelR.w >= 40, "counter label is only " + labelR.w + "px wide — layout squeezed it");
            checkEquals("0", callAndWaitOnFx(counter::getText), "initial counter text");
            check(callAndWaitOnFx(dec::isDisabled), "the \"−\" button must start disabled at 0");

            BufferedImage frame1 = decodePng(outputDir, 1);
            // Orientation/handedness: the enabled "+" button has a 3px black border; its left
            // border column must be dark. A rotated/mirrored frame would put something else
            // (white background or the label) there.
            int incBorderGray = grayAt(frame1, incR.x + 1, incR.cy());
            check(incBorderGray < 60, "the \"+\" button's left border pixel (" + (incR.x + 1) + ","
                    + incR.cy() + ") should be black, was gray=" + incBorderGray
                    + " — frame orientation or layout is wrong; see frame-0001.png");
            // The disabled button's #909090 border is deliberately NOT asserted: on a
            // monochrome panel THRESHOLD turns it white (the disabled look is not settled yet).
            LOG.info("Disabled \"−\" border pixel gray={} (not asserted; the disabled look is not settled)",
                    grayAt(frame1, decR.x + 1, decR.cy()));

            // --- Tap "+" -------------------------------------------------------------------
            tap(touchSource, 1, incR);
            check(awaitCondition(() -> "1".equals(textOf(counter)), CONDITION_TIMEOUT_MS),
                    "tapping the \"+\" button's centre did not increment the counter (text="
                    + textOf(counter) + ") — touch coordinates and the frame disagree");
            check(recording.awaitWriteCount(c -> c >= 2, CONDITION_TIMEOUT_MS), "no write followed the \"+\" tap");
            check(!callAndWaitOnFx(dec::isDisabled), "\"−\" must be enabled once the counter is 1");
            BufferedImage frame2 = decodePng(outputDir, recording.writeCount());
            check(!sameImage(frame1, frame2), "frame did not change after incrementing");

            // --- Tap "−" (now enabled) ------------------------------------------------------
            int writesBefore = recording.writeCount();
            tap(touchSource, 2, decR);
            check(awaitCondition(() -> "0".equals(textOf(counter)), CONDITION_TIMEOUT_MS),
                    "tapping the \"−\" button's centre did not decrement the counter (text="
                    + textOf(counter) + ")");
            check(recording.awaitWriteCount(c -> c > writesBefore, CONDITION_TIMEOUT_MS),
                    "no write followed the \"−\" tap");
            check(callAndWaitOnFx(dec::isDisabled), "\"−\" must be disabled again at 0");

            // --- Tap the label (no handler): counter unchanged ---------------------------------
            tap(touchSource, 3, labelR);
            Thread.sleep(NO_WRITE_TIMEOUT_MS);
            checkEquals("0", textOf(counter), "tapping the label must not change the counter");

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

    private record Rect(int x, int y, int w, int h) {
        int cx() { return x + w / 2; }
        int cy() { return y + h / 2; }
        @Override public String toString() { return "[" + x + "," + y + " " + w + "x" + h + "]"; }
    }

    private static Rect rectOf(Node node) throws Exception {
        return callAndWaitOnFx(() -> {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            return new Rect((int) Math.round(b.getMinX()), (int) Math.round(b.getMinY()),
                    (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()));
        });
    }

    private static void tap(ProgrammaticTouchSource source, int id, Rect target) {
        source.queueTouch(new TouchPoint(id, target.cx(), target.cy(), true));
        source.queueTouch(new TouchPoint(id, target.cx(), target.cy(), false));
    }

    private static String textOf(Label label) {
        try {
            return callAndWaitOnFx(label::getText);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T extends Node> T lookup(Scene scene, String selector, Class<T> type) throws Exception {
        Node node = callAndWaitOnFx(() -> scene.getRoot().lookup(selector));
        check(type.isInstance(node), "no " + type.getSimpleName() + " matching " + selector + " in the demo scene");
        return type.cast(node);
    }

    private static SceneContent selectDemo() {
        for (SceneContent c : ServiceLoader.load(SceneContent.class)) {
            if (c.name().equals("demo")) {
                return c;
            }
        }
        throw new IllegalStateException("no SceneContent named 'demo' on the module path");
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
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

    private static BufferedImage decodePng(Path outputDir, int sequence) throws IOException {
        Path file = outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", sequence));
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Could not decode " + file + " as an image");
        }
        return image;
    }

    private static int grayAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFF;
    }

    private static Path baseOutputDir() {
        String override = System.getProperty("pux4j.demoCheck.outputDir");
        if (override != null) {
            return Path.of(override);
        }
        try {
            Path codeSource = Path.of(
                    DemoBridgeCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return codeSource.getParent().resolve("demo-bridge-check");
        } catch (Exception e) {
            return Path.of("target", "demo-bridge-check");
        }
    }

    private static void prepareOutputDir(Path dir) throws IOException {
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

    private static void runAndWaitOnFx(Runnable action) throws Exception {
        callAndWaitOnFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callAndWaitOnFx(Callable<T> action) throws Exception {
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

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " — expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
