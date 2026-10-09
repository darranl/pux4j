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
import dev.pux4j.ui.transform.RenderOptions;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Phase 6.4 part D — the "Done" criterion for the whole phase: a minimal, self-verifying
 * smoke test that wires {@link EInkBridge} to the PNG display driver and the programmatic
 * touch driver, headless, and produces frames on disk in response to injected touches.
 * Exercises the bridge's Scene Capture/Threading/Touch Handling machinery end to end, for real,
 * rather than through the package-private test seams {@code pux4j-fx}'s own unit tests use.
 *
 * <p>Not a JUnit test — {@code mvn test} stays JavaFX-toolkit-free across this project
 * (deliberately: a headless-JavaFX test rig is its own, larger undertaking, out of scope here),
 * and this needs a real {@code Scene}/{@code AnimationTimer}/touch-thread loop running for
 * real, which is exactly the point: this headless PNG/programmatic-driver loop is the
 * instrument. Run it with
 * {@code java --module-path ... -m dev.pux4j.ui.bridgesmoke/dev.pux4j.ui.bridgesmoke.BridgeSmokeTest}.
 * Exits {@code 0} if every panel's scenario passes, {@code 1} otherwise, with a summary on
 * stderr — so it is usable as a CI/pre-flight gate as well as interactively.
 *
 * <p>Runs the same scenario twice, at the two real panel geometries — different aspect
 * ratios, and (deliberately) different mounted orientations, so a transposed width/height or
 * a mirror-instead-of-rotation bug would show up as an off-centre target landing in the wrong
 * place rather than being masked by symmetry.
 */
public final class BridgeSmokeTest {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeSmokeTest.class);

    /** Short, so the scenario doesn't spend real wall-clock time waiting on the timer. */
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
                // 2.9" SSD1675A — native (portrait-shaped) framebuffer, mounted LANDSCAPE.
                new PanelConfig("ssd1675a-2in9", 128, 296, Orientation.LANDSCAPE),
                // 2.13" SSD1680 — different aspect ratio AND a different real mounted
                // orientation (see Ssd1680DisplayDriverFactory.physicalOrientation()).
                new PanelConfig("ssd1680-2in13", 122, 250, Orientation.LANDSCAPE_INVERTED));

        List<String> failures = new ArrayList<>();
        for (PanelConfig panel : panels) {
            try {
                runScenario(panel);
                LOG.info("PASS: {}", panel.name());
            } catch (Throwable t) {
                LOG.error("FAIL: {}", panel.name(), t);
                failures.add(panel.name() + ": " + t);
            }
        }

        Platform.exit();

        if (!failures.isEmpty()) {
            System.err.println("BridgeSmokeTest FAILED:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("BridgeSmokeTest PASSED for all " + panels.size() + " panel(s).");
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Scenario
    // -------------------------------------------------------------------------

    private static void runScenario(PanelConfig panel) throws Exception {
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

        // Built off the FX thread deliberately — EInkBridge.Builder.build() asserts this (see
        // EInkBridge.fromConfig()'s javadoc on why: the emulator's create() would deadlock on
        // the FX thread; this module's PNG/programmatic drivers don't touch FX at all, but the
        // rule is the same for every driver so the smoke test exercises it for real).
        EInkDisplayDriver pngDriver = DisplayDriverFactory.select("png").create(null, config);
        pngDriver.initialize();
        RecordingDisplayDriver recording = new RecordingDisplayDriver(pngDriver);

        TouchDriver touchDriver = TouchDriverFactory.select("programmatic").create(null, config);
        touchDriver.initialize();
        ProgrammaticTouchSource touchSource = (ProgrammaticTouchSource) touchDriver;

        EInkBridge bridge = EInkBridge.builder()
                .display(recording)
                .touchInLogicalSpace(touchDriver)
                // Explicit, not the Builder's default: the PNG driver supports FOUR_GRAY and
                // the default render options prefer it when a display supports it, but
                // FOUR_GRAY has no partial-refresh representation anywhere in the stack (see
                // RenderSession.prepareFourGray's javadoc) — this scenario exists specifically
                // to exercise the partial/diff/region path, which only MONOCHROME reaches.
                .renderOptions(RenderOptions.monochromeDefaults())
                .captureIntervalMs(CAPTURE_INTERVAL_MS)
                .build();

        int w = bridge.displayWidth();
        int h = bridge.displayHeight();
        LOG.info("Logical size {}x{}", w, h);

        Scenario scenario = buildScene(w, h);

        // start(Scene) must run on the FX thread (Scene.snapshot() requires it internally, and
        // it's what makes the capture timer's registration synchronous — see EInkBridge.start's
        // javadoc), so it goes through runAndWaitOnFx like every other FX-thread step below.
        try {
            runAndWaitOnFx(() -> bridge.start(scenario.scene()));

            // --- Step 1: initial frame -----------------------------------------------
            check(recording.awaitWriteCount(c -> c >= 1, CONDITION_TIMEOUT_MS),
                    "no initial frame within " + CONDITION_TIMEOUT_MS + "ms");
            checkEquals(RefreshMode.FULL, recording.events().get(0).mode(), "initial write must be FULL");

            int[] rootSize = callAndWaitOnFx(() -> new int[] {
                    (int) Math.round(scenario.root().getWidth()),
                    (int) Math.round(scenario.root().getHeight())
            });
            checkEquals(w, rootSize[0], "root width after first pulse");
            checkEquals(h, rootSize[1], "root height after first pulse");

            // --- Byte-order check (frame 1's static swatches) ------------------------
            BufferedImage frame1 = decodePng(outputDir, 1);
            int swatch1Gray = grayAt(frame1, scenario.swatch1CenterX(), scenario.swatch1CenterY());
            int swatch2Gray = grayAt(frame1, scenario.swatch2CenterX(), scenario.swatch2CenterY());
            check(swatch1Gray > 200,
                    "#FF6400 swatch must render white (luminance 134) if BGRA byte order is "
                    + "correct; got gray=" + swatch1Gray + " — looks like an R/B channel swap");
            check(swatch2Gray < 60,
                    "#0064FF swatch must render black (luminance 87) if BGRA byte order is "
                    + "correct; got gray=" + swatch2Gray + " — looks like an R/B channel swap");
            check(grayAt(frame1, scenario.mirrorX(), scenario.mirrorY()) > 200,
                    "the rectangle's 180-degree-mirrored point must stay background-coloured "
                    + "in frame 1 — a non-white value here would mean the off-centre target "
                    + "isn't where geometry says it should be");

            // --- Step 2: tap the rectangle, explicit down/up lift style --------------
            touchSource.queueTouch(new TouchPoint(1, scenario.rectCenterX(), scenario.rectCenterY(), true));
            touchSource.queueTouch(new TouchPoint(1, scenario.rectCenterX(), scenario.rectCenterY(), false));
            check(awaitCondition(() -> scenario.rectangleClicks().get() >= 1, CONDITION_TIMEOUT_MS),
                    "rectangle MOUSE_CLICKED did not fire for the explicit-lift tap");
            check(recording.awaitWriteCount(c -> c >= 2, CONDITION_TIMEOUT_MS),
                    "no write followed the rectangle tap");
            checkEquals(RefreshMode.PARTIAL, recording.events().get(1).mode(),
                    "a small toggled rectangle must produce a PARTIAL write");
            BufferedImage frame2 = decodePng(outputDir, 2);
            check(grayAt(frame2, scenario.rectCenterX(), scenario.rectCenterY()) < 60,
                    "rectangle must be black after the first toggle");
            check(grayAt(frame2, scenario.mirrorX(), scenario.mirrorY()) > 200,
                    "the mirrored point must still be background-coloured after the toggle — "
                    + "a PARTIAL write landing at the wrong (mirrored) region would show up here");

            // --- Step 3: background tap — a no-op hit on the root, not a picker miss -
            // (ScenePicker.pick falls through to the root Pane itself when nothing else is
            // under the point — a Region picks on its bounds by default — so this exercises
            // RenderSession's empty-diff detector on a dispatched-but-visually-inert tap, not
            // a genuine ScenePicker "nothing was hit" miss.)
            touchSource.queueTouch(new TouchPoint(3, scenario.backgroundX(), scenario.backgroundY(), true));
            touchSource.queueTouch(new TouchPoint(3, scenario.backgroundX(), scenario.backgroundY(), false));
            check(awaitCondition(() -> scenario.rootClicks().get() >= 1, CONDITION_TIMEOUT_MS),
                    "background tap was never dispatched (root MOUSE_CLICKED never fired)");
            check(!recording.awaitWriteCount(c -> c >= 3, NO_WRITE_TIMEOUT_MS),
                    "a background tap (no visual change) must not produce a write — "
                    + "RenderSession's empty-diff detector should have suppressed it");
            checkEquals(1, scenario.rectangleClicks().get(),
                    "the background tap must not have hit the rectangle");

            // --- Step 4: tap the rectangle again, GT1151Q-style inferred lift --------
            // No release queued — the next empty poll (nothing else queued) is dispatched
            // anyway because hasActiveContacts is true, and injectTouches infers the lift.
            // Reuses contact id 1 (step 2's id): safe here because step 2's lift was fully
            // observed (rectangleClicks reached 1, which only happens after MOUSE_CLICKED,
            // which only happens after the release) before this tap is queued — reusing an id
            // before its prior lift has been observed would instead be dispatched as a drag
            // continuation of the old press, not a new one.
            touchSource.queueTouch(new TouchPoint(1, scenario.rectCenterX(), scenario.rectCenterY(), true));
            check(awaitCondition(() -> scenario.rectangleClicks().get() >= 2, CONDITION_TIMEOUT_MS),
                    "rectangle MOUSE_CLICKED did not fire for the inferred-lift (GT1151Q-style) tap");
            check(recording.awaitWriteCount(c -> c >= 3, CONDITION_TIMEOUT_MS),
                    "no write followed the inferred-lift tap");
            checkEquals(RefreshMode.PARTIAL, recording.events().get(2).mode(),
                    "the toggle-back must also be PARTIAL");
            check(grayAt(decodePng(outputDir, 3), scenario.rectCenterX(), scenario.rectCenterY()) > 200,
                    "rectangle must be white again after the second toggle");

            // --- Step 5: forceRefresh() -----------------------------------------------
            bridge.forceRefresh();
            check(recording.awaitWriteCount(c -> c >= 4, CONDITION_TIMEOUT_MS),
                    "forceRefresh() did not produce a write even though nothing changed");
            checkEquals(RefreshMode.FULL, recording.events().get(3).mode(),
                    "forceRefresh() must always produce a FULL write");
            // Frame 3 (PARTIAL, white rect) and frame 4 (forced FULL, same unchanged content)
            // must be pixel-identical: a FULL redraw and a PARTIAL composite of the same state
            // should render the same image regardless of which refresh mode produced it.
            checkFramesEqual(outputDir, 3, 4,
                    "a forced FULL write must render identically to the PARTIAL write it "
                    + "followed, for unchanged content");

            // --- Step 6: a failed write must not leave RenderSession believing the panel
            // --- shows content it never actually received --------------------------------
            recording.failNextWrite(5, true);
            touchSource.queueTouch(new TouchPoint(4, scenario.rectCenterX(), scenario.rectCenterY(), true));
            touchSource.queueTouch(new TouchPoint(4, scenario.rectCenterX(), scenario.rectCenterY(), false));
            check(awaitCondition(() -> scenario.rectangleClicks().get() >= 3, CONDITION_TIMEOUT_MS),
                    "rectangle MOUSE_CLICKED did not fire for the pre-failure tap");
            check(recording.awaitWriteCount(c -> c >= 5, CONDITION_TIMEOUT_MS),
                    "the content never actually shown (the failed write) must not be silently "
                    + "dropped as \"unchanged\" by a later capture — write-failure recovery "
                    + "did not kick in");
            checkEquals(RefreshMode.FULL, recording.events().get(4).mode(),
                    "the recovery write after a failure must be forced FULL (reset armed)");
            check(grayAt(decodePng(outputDir, 5), scenario.rectCenterX(), scenario.rectCenterY()) < 60,
                    "the recovered frame must show the content the failed write should have shown");
            // Frame 2 (PARTIAL, black rect) and frame 5 (recovery FULL, same black-rect state)
            // must also be pixel-identical — the strongest available check that the FULL
            // recovery write actually redrew the right content, not just something non-white.
            checkFramesEqual(outputDir, 2, 5,
                    "the write-failure recovery frame must render identically to the earlier "
                    + "PARTIAL write of the same content");

            // --- Step 7: stop() is idempotent, produces no further writes --------------
            int writesBeforeStop = recording.writeCount();
            runAndWaitOnFx(bridge::stop);
            bridge.stop(); // idempotent, callable off the FX thread too
            Thread.sleep(NO_WRITE_TIMEOUT_MS);
            checkEquals(writesBeforeStop, recording.writeCount(), "stop() must not produce further writes");
        } finally {
            // Belt-and-braces: every path above already calls stop() on success, but a failed
            // check() (an AssertionError) would otherwise leave this panel's display/touch
            // threads, AnimationTimer, and PNG writer thread running while the next panel's
            // scenario starts (Part D implementation review finding M2).
            try {
                bridge.stop();
            } catch (RuntimeException e) {
                LOG.warn("bridge.stop() in the failure cleanup path also failed", e);
            }
        }
    }

    /** Asserts that two already-written frames are byte-for-byte identical PNGs — the strongest
     * available check that a PARTIAL composite and a FULL redraw of the same logical content
     * actually agree, not just that a spot-checked pixel or two happens to match. */
    private static void checkFramesEqual(Path outputDir, int seqA, int seqB, String message) throws IOException {
        byte[] a = Files.readAllBytes(outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", seqA)));
        byte[] b = Files.readAllBytes(outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", seqB)));
        check(java.util.Arrays.equals(a, b),
                message + " (frame-" + String.format(Locale.ROOT, "%04d", seqA)
                + ".png vs frame-" + String.format(Locale.ROOT, "%04d", seqB) + ".png differ)");
    }

    // -------------------------------------------------------------------------
    // Scene construction
    // -------------------------------------------------------------------------

    private record Scenario(
            Scene scene, Pane root,
            int rectCenterX, int rectCenterY,
            int mirrorX, int mirrorY,
            int swatch1CenterX, int swatch1CenterY,
            int swatch2CenterX, int swatch2CenterY,
            int backgroundX, int backgroundY,
            AtomicInteger rectangleClicks, AtomicInteger rootClicks) {}

    /**
     * Builds the scenario's scene content. Deliberately plain {@code Rectangle}s on a plain
     * {@code Pane}, not JavaFX Controls — avoids skin/CSS/animation noise that would make
     * frame-to-frame pixel diffs less predictable.
     */
    private static Scenario buildScene(int w, int h) throws Exception {
        return callAndWaitOnFx(() -> {
            Pane root = new Pane();
            root.setPrefSize(w, h);

            // Deliberately off-centre (not (w-rectSize)/2, (h-rectSize)/2): a single pixel
            // checked only at the panel's centre can't tell a correct placement from a
            // 180-degree-rotated or mirrored one, since the centre point maps to itself under
            // either transform. An off-centre target, plus the mirrorX/mirrorY check below,
            // makes that class of bug visible.
            int rectSize = Math.max(16, Math.min(w, h) / 4);
            int rectX = w / 6;
            int rectY = h / 5;
            Rectangle rect = new Rectangle(rectX, rectY, rectSize, rectSize);
            rect.setFill(Color.WHITE); // starts matching the background — invisible in frame 1
            AtomicInteger rectangleClicks = new AtomicInteger();
            rect.setOnMouseClicked(e -> {
                rectangleClicks.incrementAndGet();
                rect.setFill(rect.getFill() == Color.WHITE ? Color.BLACK : Color.WHITE);
            });

            int swatchSize = 6;
            Rectangle swatch1 = new Rectangle(4, 4, swatchSize, swatchSize);
            swatch1.setFill(Color.rgb(0xFF, 0x64, 0x00)); // luminance 134 if BGRA correct → white
            Rectangle swatch2 = new Rectangle(w - 4 - swatchSize, 4, swatchSize, swatchSize);
            swatch2.setFill(Color.rgb(0x00, 0x64, 0xFF)); // luminance 87 if BGRA correct → black

            root.getChildren().addAll(rect, swatch1, swatch2);

            AtomicInteger rootClicks = new AtomicInteger();
            root.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getTarget() == root) {
                    rootClicks.incrementAndGet();
                }
            });

            Scene scene = new Scene(root, w, h);
            scene.setFill(Color.WHITE);

            int backgroundX = 4;
            int backgroundY = h - 8;

            int rectCenterX = rectX + rectSize / 2;
            int rectCenterY = rectY + rectSize / 2;
            // The point the rectangle's centre would land on under a 180-degree rotation or a
            // mirror — must stay background-coloured for the whole scenario. Off-centre
            // placement (well clear of the swatches/background-tap point, given the margins
            // above) keeps this distinct from every other checked point.
            int mirrorX = w - 1 - rectCenterX;
            int mirrorY = h - 1 - rectCenterY;

            return new Scenario(
                    scene, root,
                    rectCenterX, rectCenterY,
                    mirrorX, mirrorY,
                    4 + swatchSize / 2, 4 + swatchSize / 2,
                    w - 4 - swatchSize / 2, 4 + swatchSize / 2,
                    backgroundX, backgroundY,
                    rectangleClicks, rootClicks);
        });
    }

    // -------------------------------------------------------------------------
    // PNG decoding
    // -------------------------------------------------------------------------

    private static BufferedImage decodePng(Path outputDir, int sequence) throws IOException {
        Path file = outputDir.resolve(String.format(Locale.ROOT, "frame-%04d.png", sequence));
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Could not decode " + file + " as an image");
        }
        return image;
    }

    /** The PNG output is 8-bit greyscale (PngEncoder), so R == G == B; any channel is the
     * grayscale sample. */
    private static int grayAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Filesystem
    // -------------------------------------------------------------------------

    /**
     * Where output frames are written, one subdirectory per panel. Resolution order: the
     * {@code pux4j.bridgeSmoke.outputDir} system property if set; otherwise a {@code
     * bridge-smoke} directory next to this class's own {@code target/classes} (so the result
     * doesn't depend on the shell's working directory when this is launched); otherwise
     * {@code ./target/bridge-smoke} relative to the current directory, with a WARN, if this
     * class's code source can't be resolved (e.g. an unusual launch setup).
     */
    private static Path baseOutputDir() {
        String override = System.getProperty("pux4j.bridgeSmoke.outputDir");
        if (override != null) {
            return Path.of(override);
        }
        try {
            Path codeSource = Path.of(
                    BridgeSmokeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // codeSource is .../pux4j-bridge-smoke/target/classes (a directory on the module
            // path) — its parent is target/, where every other Maven output already lives.
            return codeSource.getParent().resolve("bridge-smoke");
        } catch (Exception e) {
            LOG.warn("Could not resolve this class's code source; writing under "
                    + "./target/bridge-smoke relative to the current directory instead", e);
            return Path.of("target", "bridge-smoke");
        }
    }

    /** Empties (or creates) {@code dir} and asserts it is empty afterwards — a stale file from
     * a previous run must never be mistaken for a frame this run produced. Not {@code /tmp}:
     * kept under the module's own {@code target/}, per the project's temp-files convention. */
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
        try (var listing = Files.list(dir)) {
            check(listing.findAny().isEmpty(), "output dir not empty after clearing: " + dir);
        }
    }

    // -------------------------------------------------------------------------
    // FX-thread helpers
    // -------------------------------------------------------------------------

    /** Runs {@code action} on the FX thread and blocks (this, non-FX, thread) until it
     * completes, propagating any exception it threw. */
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

    // -------------------------------------------------------------------------
    // Assertions
    // -------------------------------------------------------------------------

    /** Polls {@code condition} every 5ms until it's true or {@code timeoutMs} elapses; returns
     * whether it was satisfied. No fixed sleeps used as synchronisation anywhere in this class
     * — every wait is one of these, bounded and condition-driven. */
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
