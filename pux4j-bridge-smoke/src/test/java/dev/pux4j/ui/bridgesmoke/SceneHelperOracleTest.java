// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import com.sun.javafx.scene.SceneHelper;
import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import dev.pux4j.ui.bridgesmoke.BridgeFixture.Trace;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the mouse events the bridge produces from touches with what JavaFX itself produces for
 * the same pointer positions, using the internal {@code SceneHelper.processMouseEvent} as the
 * oracle. Two identical scenes are built by the same factory: one hosted by the bridge, one fed
 * directly. Both are traced by the same scene-level filter.
 */
class SceneHelperOracleTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    private static Pane scene() {
        Button a = new Button("A");
        a.setPrefSize(80, 40);
        a.relocate(10, 20);
        Button b = new Button("B");
        b.setPrefSize(80, 40);
        b.relocate(120, 20);
        return new Pane(a, b);
    }

    /** The bridge's drag-detect distance (per axis, strictly more than); see {@code ContactSet.MULTI_CLICK_PX}. */
    private static final double BRIDGE_DRAG_HYSTERESIS_PX = 20.0;

    /**
     * Sets the drag-detect default of the oracle scene to the bridge's rule, so the position of
     * {@code DRAG_DETECTED} does not depend on the desktop's multi-click distance (see
     * {@link #withDocumentedDeviations}). The real {@code Scene} still decides when it fires
     * relative to the drag, at which target, and that it fires once.
     */
    private static void pinDragDetectToBridgeRule(Scene s) {
        double[] press = new double[2];
        s.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            press[0] = e.getSceneX();
            press[1] = e.getSceneY();
        });
        s.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> e.setDragDetect(
                Math.abs(e.getSceneX() - press[0]) > BRIDGE_DRAG_HYSTERESIS_PX
                        || Math.abs(e.getSceneY() - press[1]) > BRIDGE_DRAG_HYSTERESIS_PX));
    }

    private void compare(Gesture gesture) throws Exception {
        List<Trace.Entry> bridge;
        try (BridgeFixture f = BridgeFixture.open(out, SceneHelperOracleTest::scene)) {
            f.play(gesture);
            bridge = f.trace.all();
        }
        List<Trace.Entry> oracle = callAndWaitOnFx(() -> {
            Pane root = scene();
            Scene s = new Scene(root, BridgeFixture.width(), BridgeFixture.height());
            root.applyCss();
            root.layout();
            Trace t = new Trace();
            t.install(s);
            pinDragDetectToBridgeRule(s);
            for (Gesture.Step step : gesture.steps) {
                switch (step.kind()) {
                    case PRESS -> {
                        // As a pointing device does: the pointer moves to the contact, then presses.
                        send(s, MouseEvent.MOUSE_MOVED, step, false);
                        send(s, MouseEvent.MOUSE_PRESSED, step, true);
                    }
                    case MOVE -> send(s, MouseEvent.MOUSE_DRAGGED, step, true);
                    case RELEASE -> {
                        send(s, MouseEvent.MOUSE_RELEASED, step, false);
                        // The bridge exits everything after a lift (a touch surface has no pointer to leave).
                        send(s, MouseEvent.MOUSE_EXITED, step, false);
                    }
                }
            }
            return t.all();
        });
        assertTrue(bridge.stream().anyMatch(e -> e.type().equals("MOUSE_CLICKED")), "bridge trace: " + bridge);
        assertEquals(withDocumentedDeviations(oracle), bridge,
                "bridge (actual) vs JavaFX oracle with documented deviations (expected)");
    }

    /**
     * The oracle's trace rewritten to what the bridge is documented to do differently:
     * <ul>
     *   <li><b>No re-pick after a release.</b> JavaFX re-picks under the pointer after the release
     *       and enters whatever is there; the bridge does not, since it exits everything at once.
     *       The re-pick's {@code *_ENTERED_TARGET} events, and the exits that undo them, are dropped.</li>
     *   <li><b>Re-entering at the press.</b> After the exit that follows a lift, the next press at the
     *       same spot enters the hierarchy from the press itself: the enter events carry its button
     *       state and click count, and no {@code MOUSE_MOVED} precedes it. (A pointer that never left
     *       would not exit and re-enter at all; this is the cost of the exit-after-lift rule.)</li>
     *   <li><b>Exit events after a lift</b> are derived from the release, so they carry its click count
     *       (JavaFX gives enter/exit events a click count of 0).</li>
     *   <li><b>Drag hysteresis.</b> Not a rewrite: the oracle scene carries a filter that sets the
     *       drag-detect default on each {@code MOUSE_DRAGGED} to the bridge's rule (strictly more than
     *       20 px from the press on either axis) through the public {@code setDragDetect}. JavaFX's own
     *       default uses the toolkit's multi-click distance, which is a desktop setting (GTK: 5 px by
     *       default, 25 px under Raspberry Pi OS's default theme), so without the filter the position
     *       of {@code DRAG_DETECTED} would depend on the machine. The real {@code Scene} still decides
     *       when it fires relative to the drag, at which target, and that it fires once.</li>
     * </ul>
     */
    private static List<Trace.Entry> withDocumentedDeviations(List<Trace.Entry> oracle) {
        List<Trace.Entry> out = new ArrayList<>(reEnteredAtPress(oracle));
        oracle = List.copyOf(out);
        out.clear();
        Set<String> repicked = new HashSet<>();
        boolean afterClick = false;
        boolean repickWindow = false;
        int releaseClicks = 0;
        for (Trace.Entry e : oracle) {
            switch (e.type()) {
                case "MOUSE_RELEASED" -> releaseClicks = e.clickCount();
                case "MOUSE_CLICKED" -> {
                    afterClick = true;
                    repickWindow = true;
                    repicked.clear();
                }
                case "MOUSE_EXITED" -> repickWindow = false;
                case "MOUSE_PRESSED" -> afterClick = false;
                default -> { }
            }
            if (repickWindow && e.type().equals("MOUSE_ENTERED_TARGET")) {
                repicked.add(e.target());
                continue;
            }
            if (afterClick && e.type().equals("MOUSE_EXITED_TARGET") && repicked.contains(e.target())) {
                continue;
            }
            if (afterClick && e.type().startsWith("MOUSE_EXITED")) {
                e = new Trace.Entry(e.type(), e.target(), e.sceneX(), e.sceneY(), e.primaryDown(), releaseClicks);
            }
            out.add(e);
        }
        return out;
    }

    /** Rewrites each ENTERED…, MOVED, PRESSED run that follows an earlier exit into ENTERED… (as pressed), PRESSED. */
    private static List<Trace.Entry> reEnteredAtPress(List<Trace.Entry> trace) {
        List<Trace.Entry> out = new ArrayList<>(trace);
        for (int i = 0; i < out.size(); i++) {
            if (!out.get(i).type().equals("MOUSE_PRESSED")) {
                continue;
            }
            boolean exitedBefore = out.subList(0, i).stream().anyMatch(e -> e.type().equals("MOUSE_EXITED"));
            int moved = i - 1;
            if (!exitedBefore || moved < 0 || !out.get(moved).type().equals("MOUSE_MOVED")) {
                continue;
            }
            int cc = out.get(i).clickCount();
            out.remove(moved);
            i--;
            for (int j = moved - 1; j >= 0 && out.get(j).type().startsWith("MOUSE_ENTERED"); j--) {
                Trace.Entry e = out.get(j);
                out.set(j, new Trace.Entry(e.type(), e.target(), e.sceneX(), e.sceneY(), true, cc));
            }
        }
        return out;
    }

    private static void send(Scene s, EventType<MouseEvent> type, Gesture.Step step, boolean down) {
        boolean button = type != MouseEvent.MOUSE_MOVED && type != MouseEvent.MOUSE_EXITED;
        SceneHelper.processMouseEvent(s, new MouseEvent(type, step.x(), step.y(), step.x(), step.y(),
                button ? MouseButton.PRIMARY : MouseButton.NONE, 1,
                false, false, false, false, down, false, false, true, false, false, null));
    }

    @Test void tap() throws Exception { compare(Gesture.tap(50, 40)); }
    @Test void tapOnEmptySpace() throws Exception { compare(Gesture.tap(200, 100)); }
    @Test void dragWithinNode() throws Exception { compare(Gesture.press(20, 30).dragTo(70, 50).release()); }
    @Test void dragAcrossSiblings() throws Exception { compare(Gesture.press(50, 40).dragTo(160, 40).release()); }
    @Test void dragOffAndReleaseElsewhere() throws Exception { compare(Gesture.press(50, 40).dragTo(200, 100).release()); }
    @Test void pressOffAndDragOnto() throws Exception { compare(Gesture.press(200, 100).dragTo(50, 40).release()); }
    // Click counting cannot be pinned like the drag-detect default: the real Scene computes clickCount
    // itself, from the toolkit's multi-click distance and time (a desktop setting: GTK 5 px / 400 ms by
    // default, 25 px under Raspberry Pi OS's default theme), while the bridge uses a fixed 500 ms / 20 px.
    // So an oracle case that depends on click counting must keep its taps clearly outside that band: the
    // same spot (or within 2 px) to continue a count, more than 30 px apart to restart it. A tap 5-25 px
    // from the previous one gives a different count per desktop and would fail on some machines.
    @Test void doubleTap() throws Exception { compare(Gesture.tap(50, 40).then(Gesture.tap(50, 40))); }
}
