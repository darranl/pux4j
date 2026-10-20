// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchPoint;
import dev.pux4j.ui.fx.EInkBridge;
import dev.pux4j.ui.transform.RenderOptions;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.awaitCondition;
import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.runAndWaitOnFx;

/**
 * Test fixture: a real {@link EInkBridge} (PNG display driver, {@link ScriptedTouch} touch
 * driver) hosting a Stage-less {@link Scene} — exactly how the bridge hosts an app in
 * production — plus a {@link Trace} of every mouse event the scene sees.
 *
 * <p>The tests drive the whole pipeline: touch driver → touch thread → {@code ContactSet} → FX
 * thread → {@code MouseSynthesizer} → the scene. Nothing in {@code pux4j-fx} is reached around.
 *
 * <p>Needs a display for the JavaFX toolkit: without one the tests are skipped, unless the system
 * property {@code pux4j.fxTests.required} is {@code true} (then they fail).
 */
final class BridgeFixture implements AutoCloseable {

    private static final int NATIVE_WIDTH = 122;
    private static final int NATIVE_HEIGHT = 250;
    private static boolean toolkitChecked;
    private static String toolkitProblem;

    /** Starts the JavaFX toolkit once per JVM; skips (or fails, if required) when it cannot. */
    static synchronized void requireToolkit() {
        if (!toolkitChecked) {
            toolkitChecked = true;
            CountDownLatch started = new CountDownLatch(1);
            try {
                Platform.startup(started::countDown);
                Platform.setImplicitExit(false);
                if (!started.await(10, TimeUnit.SECONDS)) {
                    toolkitProblem = "JavaFX toolkit did not start within 10s";
                }
            } catch (IllegalStateException alreadyStarted) {
                // another test class got there first
            } catch (UnsupportedOperationException | UnsatisfiedLinkError | InterruptedException e) {
                toolkitProblem = "JavaFX toolkit unavailable (no display?): " + e;
            }
        }
        if (toolkitProblem != null) {
            if (Boolean.getBoolean("pux4j.fxTests.required")) {
                throw new AssertionError(toolkitProblem);
            }
            Assumptions.abort(toolkitProblem);
        }
    }

    // ---------------------------------------------------------------------------------------

    final Scene scene;
    final Trace trace = new Trace();
    private final EInkBridge bridge;
    private final ScriptedTouch touch;

    private BridgeFixture(Scene scene, EInkBridge bridge, ScriptedTouch touch) {
        this.scene = scene;
        this.bridge = bridge;
        this.touch = touch;
    }

    /** Logical size of the panel the fixture emulates (a 2.13" in landscape). */
    static int width() { return NATIVE_HEIGHT; }
    static int height() { return NATIVE_WIDTH; }

    /**
     * Builds the scene from {@code rootFactory} (called on the FX thread), applies CSS and layout
     * so skins exist and node bounds are final, and starts the bridge.
     */
    static BridgeFixture open(Path outputDir, Supplier<Parent> rootFactory) throws Exception {
        requireToolkit();
        DriverConfig config = DriverConfig.builder()
                .property("width", NATIVE_WIDTH)
                .property("height", NATIVE_HEIGHT)
                .property("orientation", Orientation.LANDSCAPE_INVERTED.name())
                .property("outputDir", outputDir.toString())
                .build();
        EInkDisplayDriver display = DisplayDriverFactory.select("png").create(null, config);
        display.initialize();
        ScriptedTouch touch = new ScriptedTouch();
        EInkBridge bridge = EInkBridge.builder()
                .display(display)
                .touchInLogicalSpace(touch)
                .renderOptions(RenderOptions.monochromeDefaults())
                .captureIntervalMs(BridgeCheckHarness.CAPTURE_INTERVAL_MS)
                .build();
        Scene scene = callAndWaitOnFx(() -> {
            Parent root = rootFactory.get();
            Scene s = new Scene(root, bridge.displayWidth(), bridge.displayHeight());
            root.applyCss();
            root.layout();
            return s;
        });
        BridgeFixture fixture = new BridgeFixture(scene, bridge, touch);
        runAndWaitOnFx(() -> {
            fixture.trace.install(scene);
            bridge.start(scene);
        });
        return fixture;
    }

    /** Plays {@code gesture} through the touch driver and returns once the scene has seen all of it. */
    void play(Gesture gesture) throws Exception {
        touch.submit(gesture.batches());
        if (!awaitCondition(touch::settled, BridgeCheckHarness.CONDITION_TIMEOUT_MS)) {
            throw new AssertionError("touch thread did not consume the gesture within "
                    + BridgeCheckHarness.CONDITION_TIMEOUT_MS + "ms");
        }
        runAndWaitOnFx(() -> { });
    }

    /** Centre of {@code node} in scene coordinates, taken after layout. */
    int[] centre(Node node) throws Exception {
        return callAndWaitOnFx(() -> {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            return new int[] {(int) Math.round(b.getCenterX()), (int) Math.round(b.getCenterY())};
        });
    }

    @Override
    public void close() throws Exception {
        try {
            runAndWaitOnFx(bridge::stop);
        } finally {
            bridge.stop();
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A touch driver the test fills with whole gestures. The bridge's touch thread reads one batch
     * per poll and treats an empty poll as a lift, so a gesture's batches must reach the queue
     * atomically — a plain queue filled batch by batch could be polled in the gaps. This driver
     * enqueues a gesture under the lock the reader holds, and reports when the reader has
     * consumed it (plus one further poll, by which time the touch thread has already handed the
     * last batch to the FX thread).
     */
    static final class ScriptedTouch implements TouchDriver {
        private final ArrayDeque<List<TouchPoint>> queue = new ArrayDeque<>();
        private long reads;
        private long settleAt;

        @Override public void initialize() { }

        @Override public synchronized void reset() { queue.clear(); }

        @Override
        public synchronized List<TouchPoint> readTouches() {
            reads++;
            List<TouchPoint> batch = queue.poll();
            return batch != null ? batch : List.of();
        }

        synchronized void submit(List<List<TouchPoint>> batches) {
            queue.addAll(batches);
            settleAt = reads + batches.size() + 1;
        }

        synchronized boolean settled() {
            return queue.isEmpty() && reads >= settleAt;
        }
    }

    /** A single-finger gesture as a list of positions; see {@link #batches()}. */
    static final class Gesture {
        enum Kind { PRESS, MOVE, RELEASE }

        record Step(Kind kind, int x, int y) { }

        /** Largest hop between two polls — keeps the finger under the contact model's speed limit. */
        private static final int MAX_HOP = 30;
        private static final int CONTACT_ID = 1;

        final List<Step> steps = new ArrayList<>();

        static Gesture press(int x, int y) {
            Gesture g = new Gesture();
            g.steps.add(new Step(Kind.PRESS, x, y));
            return g;
        }

        static Gesture tap(int x, int y) {
            return press(x, y).release();
        }

        /** Moves to (x, y) in hops of at most {@value #MAX_HOP} px, so every hop clears the dead-band only when it should. */
        Gesture dragTo(int x, int y) {
            Step last = steps.get(steps.size() - 1);
            int dx = x - last.x();
            int dy = y - last.y();
            int hops = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / MAX_HOP));
            for (int i = 1; i <= hops; i++) {
                steps.add(new Step(Kind.MOVE, last.x() + dx * i / hops, last.y() + dy * i / hops));
            }
            return this;
        }

        Gesture release() {
            Step last = steps.get(steps.size() - 1);
            steps.add(new Step(Kind.RELEASE, last.x(), last.y()));
            return this;
        }

        /** This gesture followed immediately by {@code next} (e.g. a double tap). */
        Gesture then(Gesture next) {
            steps.addAll(next.steps);
            return this;
        }

        List<List<TouchPoint>> batches() {
            List<List<TouchPoint>> out = new ArrayList<>();
            for (Step s : steps) {
                out.add(List.of(new TouchPoint(CONTACT_ID, s.x(), s.y(), s.kind() != Kind.RELEASE)));
            }
            return out;
        }
    }

    /** Every mouse event the scene sees, as comparable strings and records. */
    static final class Trace {
        record Entry(String type, String target, long sceneX, long sceneY, boolean primaryDown, int clickCount) {
            @Override public String toString() {
                return type + " @" + target + " (" + sceneX + "," + sceneY + ")" + (primaryDown ? " down" : "")
                        + " cc=" + clickCount;
            }
        }

        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        void install(Scene scene) {
            scene.addEventFilter(MouseEvent.ANY, e -> entries.add(new Entry(
                    e.getEventType().getName(), pathOf(scene, e.getTarget()),
                    Math.round(e.getSceneX()), Math.round(e.getSceneY()), e.isPrimaryButtonDown(),
                    e.getClickCount())));
        }

        List<Entry> all() { return List.copyOf(entries); }

        /** Event types in order, leaving out the {@code *_TARGET} bubbling variants. */
        List<String> types() {
            return entries.stream().map(Entry::type).filter(t -> !t.endsWith("_TARGET")).toList();
        }

        List<String> typesAt(String target) {
            return entries.stream().filter(e -> e.target().equals(target)).map(Entry::type)
                    .filter(t -> !t.endsWith("_TARGET")).toList();
        }

        void clear() { entries.clear(); }
    }

    /** {@code "scene"}, or the child-index path from the scene's root, e.g. {@code "root/0/1"}. */
    static String pathOf(Scene scene, Object target) {
        if (target instanceof Scene) {
            return "scene";
        }
        if (!(target instanceof Node node)) {
            return String.valueOf(target);
        }
        StringBuilder path = new StringBuilder();
        Node n = node;
        while (n != null && n != scene.getRoot()) {
            Parent parent = n.getParent();
            if (parent == null) {
                return "detached:" + path;
            }
            path.insert(0, "/" + parent.getChildrenUnmodifiable().indexOf(n));
            n = parent;
        }
        return "root" + path;
    }

    /** Whether {@code target} is {@code ancestor} or one of its descendants (e.g. a Button's text). */
    static boolean isWithin(Node ancestor, Object target) {
        for (Object o = target; o instanceof Node n; o = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
