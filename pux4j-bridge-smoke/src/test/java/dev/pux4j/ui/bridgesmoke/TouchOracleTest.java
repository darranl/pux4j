// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.TouchTrace;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the touch events the bridge produces with what a {@code Scene} produces itself when its
 * peer listener is fed the same touch sets, handlers included. A stage-less scene has no peer, so
 * the package-private {@code Scene$ScenePeerListener} is constructed by reflection (the tests run
 * on the class path, so no {@code --add-opens} is needed). Each case is written once, as touch
 * sets with raw driver ids (7 and 9, which JavaFX and the contact model both renumber); the bridge
 * is fed the same sets as driver frames. Everything the trace records is compared: type, target,
 * point id and state, touch count, set id, grab, scene position and the pick result's node. Moves
 * stay clear of the contact model's dead-band, which the listener has no counterpart for.
 * Both runtimes are JavaFX 23.0.1 (the build's version); {@code processTouchEvent} and
 * {@code touchEventBegin/Next/End} are unchanged in 27.
 */
class TouchOracleTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    private static final TouchPoint.State P = TouchPoint.State.PRESSED, M = TouchPoint.State.MOVED,
            S = TouchPoint.State.STATIONARY, R = TouchPoint.State.RELEASED;

    private static Group root(Consumer<Group> handlers) {
        Rectangle a = new Rectangle(80, 80);
        a.setX(0);
        a.setY(20);
        Rectangle b = new Rectangle(80, 80);
        b.setX(150);
        b.setY(20);
        Group g = new Group(a, b);
        handlers.accept(g);
        return g;
    }

    private static Rectangle a(Group g) { return (Rectangle) g.getChildren().get(0); }

    private static Rectangle b(Group g) { return (Rectangle) g.getChildren().get(1); }

    /** One point of a touch set: the oracle's listener is fed these; the bridge gets the matching driver frame. */
    record Pt(TouchPoint.State state, int id, int x, int y) { }

    private static Pt pt(TouchPoint.State state, int id, int x, int y) { return new Pt(state, id, x, y); }

    /** The driver frame for a set: every point is down, except those released in it. */
    private static List<dev.pux4j.ui.core.TouchPoint> frame(List<Pt> set) {
        return set.stream().map(p -> new dev.pux4j.ui.core.TouchPoint(p.id(), p.x(), p.y(), p.state() != R)).toList();
    }

    private static final class Oracle {
        private final Object listener;
        private final Method begin;
        private final Method next;
        private final Method end;

        Oracle(Scene scene) throws Exception {
            Class<?> type = Class.forName("javafx.scene.Scene$ScenePeerListener");
            Constructor<?> ctor = type.getDeclaredConstructor(Scene.class);
            ctor.setAccessible(true);
            listener = ctor.newInstance(scene);
            begin = type.getDeclaredMethod("touchEventBegin", long.class, int.class, boolean.class,
                    boolean.class, boolean.class, boolean.class, boolean.class);
            next = type.getDeclaredMethod("touchEventNext", TouchPoint.State.class, long.class,
                    double.class, double.class, double.class, double.class);
            end = type.getDeclaredMethod("touchEventEnd");
            for (Method m : List.of(begin, next, end)) {
                m.setAccessible(true);
            }
        }

        void feed(List<Pt> set) throws Exception {
            begin.invoke(listener, 0L, set.size(), true, false, false, false, false);
            for (Pt p : set) {
                next.invoke(listener, p.state(), (long) p.id(), (double) p.x(), (double) p.y(),
                        (double) p.x(), (double) p.y());
            }
            end.invoke(listener);
        }
    }

    private static List<String> shape(List<TouchTrace.Entry> entries) {
        return entries.stream().map(TouchTrace.Entry::toString).toList();
    }

    private void compare(List<List<Pt>> sets, Consumer<Group> handlers) throws Exception {
        List<TouchTrace.Entry> bridge;
        try (BridgeFixture f = BridgeFixture.open(out, () -> root(handlers))) {
            f.playFrames(sets.stream().map(TouchOracleTest::frame).toList());
            bridge = f.touchTrace.all();
        }
        TouchTrace trace = new TouchTrace();
        callAndWaitOnFx(() -> {
            Group root = root(handlers);
            Scene s = new Scene(root, BridgeFixture.width(), BridgeFixture.height());
            root.applyCss();
            root.layout();
            trace.install(s);
            try {
                Oracle oracle = new Oracle(s);
                for (List<Pt> set : sets) {
                    oracle.feed(set);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });
        assertEquals(shape(trace.all()), shape(bridge));
    }

    private static void none(Group g) { }

    /** Sets for one finger going from x0 to x1 along y = 60 in 30 px hops, pressed and released. */
    private static List<List<Pt>> drag(int id, int x0, int x1) {
        List<List<Pt>> sets = new ArrayList<>();
        sets.add(List.of(pt(P, id, x0, 60)));
        int hops = (int) Math.ceil(Math.abs(x1 - x0) / 30.0);
        int x = x0;
        for (int i = 1; i <= hops; i++) {
            x = x0 + (x1 - x0) * i / hops;
            sets.add(List.of(pt(M, id, x, 60)));
        }
        sets.add(List.of(pt(R, id, x, 60)));
        return sets;
    }

    @Test
    void tapMatches() throws Exception {
        compare(List.of(List.of(pt(P, 7, 40, 60)), List.of(pt(R, 7, 40, 60))), TouchOracleTest::none);
    }

    @Test
    void tapOnEmptySpaceMatches() throws Exception {
        compare(List.of(List.of(pt(P, 7, 115, 60)), List.of(pt(R, 7, 115, 60))), TouchOracleTest::none);
    }

    @Test
    void dragOffTheNodeMatches() throws Exception {
        compare(drag(7, 40, 190), TouchOracleTest::none);
    }

    private static final List<List<Pt>> TWO_FINGERS = List.of(
            List.of(pt(P, 7, 40, 60)),
            List.of(pt(S, 7, 40, 60), pt(P, 9, 190, 60)),
            List.of(pt(S, 7, 40, 60), pt(M, 9, 215, 60)),
            List.of(pt(R, 7, 40, 60), pt(S, 9, 215, 60)),
            List.of(pt(R, 9, 215, 60)));

    @Test
    void twoFingersMatch() throws Exception {
        compare(TWO_FINGERS, TouchOracleTest::none);
    }

    @Test
    void ungrabOnPressMatches() throws Exception {
        compare(drag(7, 40, 190),
                g -> a(g).addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab()));
    }

    @Test
    void grabOnAnUngrabbedPointMatches() throws Exception {
        // Out over B and back over A in one gesture: the release of the first leg and the press of the second are dropped.
        List<List<Pt>> sets = new ArrayList<>(drag(7, 40, 210));
        sets.remove(sets.size() - 1);
        List<List<Pt>> back = drag(7, 210, 40);
        sets.addAll(back.subList(1, back.size()));
        compare(sets, g -> {
            a(g).addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab());
            b(g).addEventHandler(TouchEvent.TOUCH_MOVED, e -> e.getTouchPoint().grab());
        });
    }

    @Test
    void grabByAParentMatches() throws Exception {
        compare(drag(7, 40, 190), g -> g.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().grab()));
    }

    @Test
    void crossPointGrabMatches() throws Exception {
        compare(TWO_FINGERS, g -> a(g).addEventHandler(TouchEvent.TOUCH_STATIONARY, e -> e.getTouchPoints().stream()
                .filter(p -> p.getId() != e.getTouchPoint().getId())
                .forEach(p -> p.grab(a(g)))));
    }

    @Test
    void consumedEventsMatch() throws Exception {
        compare(TWO_FINGERS, g -> a(g).addEventHandler(TouchEvent.ANY, TouchEvent::consume));
    }

    @Test
    void aParentGrabbingTheSecondPointForAMatches() throws Exception {
        // A handler on the parent moves the second point's grab to A as soon as it is pressed.
        compare(TWO_FINGERS, g -> g.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
            if (e.getTouchPoint().getId() == 2) {
                e.getTouchPoint().grab(a(g));
            }
        }));
    }
}
