// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import dev.pux4j.ui.bridgesmoke.BridgeFixture.TouchTrace.Entry;
import javafx.scene.Group;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.runAndWaitOnFx;
import static dev.pux4j.ui.bridgesmoke.BridgeFixture.down;
import static dev.pux4j.ui.bridgesmoke.BridgeFixture.up;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TouchEvent} sets and grabs, end to end through a real {@code Scene}: touch driver →
 * bridge → the scene's event dispatch. Two squares, {@code A} (root/0, centred at {@link #AX}) and
 * {@code B} (root/1, centred at {@link #BX}), sit in a {@link Group} so the space between them
 * belongs to the {@code Scene}. The trace is a scene-level filter, so it sees every event whatever
 * the handlers do; handlers that need to observe their own view of the events record it themselves.
 * Moves are at least 25 px, clear of the contact model's 20 px dead-band, and single-finger drags
 * go in hops of at most 30 px. {@code TouchOracleTest} checks the same machinery against JavaFX.
 */
class TouchBridgeTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    private static final int AX = 40, BX = 190, Y = 60;

    /** Marks an exception that is an {@code Error}, which the synthesizer deliberately lets propagate. */
    private static final class TestError extends Error {
        TestError(String message) { super(message); }
    }

    private static Rectangle square(double x) {
        Rectangle r = new Rectangle(80, 80);
        r.setX(x - 40);
        r.setY(Y - 40);
        return r;
    }

    private BridgeFixture open(Rectangle[] holder) throws Exception {
        return BridgeFixture.open(out, () -> {
            holder[0] = square(AX);
            holder[1] = square(BX);
            return new Group(holder[0], holder[1]);
        });
    }

    private static List<String> targets(List<Entry> entries) {
        return entries.stream().map(Entry::target).toList();
    }

    private static List<String> typeAndId(List<Entry> entries) {
        return entries.stream().map(e -> e.type().replace("TOUCH_", "") + e.pointId()).toList();
    }

    @Test
    void aTapIsPressedThenReleasedAtTheNodeWithItsGrab() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.play(Gesture.tap(AX, Y));
            List<Entry> e = f.touchTrace.all();
            assertEquals(List.of("PRESSED", "RELEASED"), f.touchTrace.types());
            assertEquals(List.of("root/0", "root/0"), targets(e));
            assertEquals("root/0", e.get(0).grabbed());
            // The grab is dropped after the release set; the release itself still carries it.
            assertEquals("root/0", e.get(1).grabbed());
            assertTrue(e.stream().allMatch(x -> x.touchCount() == 1), e.toString());
        }
    }

    @Test
    void tapOnEmptySpaceGoesToTheScene() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.play(Gesture.tap(120, Y));
            assertEquals(List.of("scene", "scene"), targets(f.touchTrace.all()));
        }
    }

    @Test
    void twoContactsShareOneSetAndEachGoesToItsOwnNode() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.playFrames(List.of(
                    List.of(down(1, AX, Y)),
                    List.of(down(1, AX, Y), down(2, BX, Y)),
                    List.of(up(1, AX, Y), down(2, BX, Y)),
                    List.of(up(2, BX, Y))));
            List<Entry> e = f.touchTrace.all();
            // Four sets: one event per point, in id order within a set.
            assertEquals(List.of("PRESSED1", "STATIONARY1", "PRESSED2", "RELEASED1", "STATIONARY2", "RELEASED2"),
                    typeAndId(e));
            assertEquals(List.of("root/0", "root/0", "root/1", "root/0", "root/1", "root/1"), targets(e));
            assertEquals(List.of(1, 2, 2, 2, 2, 1), e.stream().map(Entry::touchCount).toList());
            // The events of one set share its id; each set has its own.
            assertEquals(e.get(1).setId(), e.get(2).setId());
            assertEquals(e.get(3).setId(), e.get(4).setId());
            assertNotEquals(e.get(0).setId(), e.get(1).setId());
            assertNotEquals(e.get(2).setId(), e.get(3).setId());
            assertNotEquals(e.get(4).setId(), e.get(5).setId());
        }
    }

    @Test
    void aHeldContactIsStationaryWhileAnotherMoves() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.playFrames(List.of(
                    List.of(down(1, AX, Y)),
                    List.of(down(1, AX, Y), down(2, BX, Y)),
                    List.of(down(1, AX, Y), down(2, BX + 25, Y)),
                    List.of(up(1, AX, Y), up(2, BX + 25, Y))));
            List<Entry> a = f.touchTrace.forPoint(1);
            List<Entry> b = f.touchTrace.forPoint(2);
            assertEquals(List.of("PRESSED", "STATIONARY", "STATIONARY", "RELEASED"),
                    a.stream().map(Entry::state).toList());
            assertEquals(List.of("PRESSED", "MOVED", "RELEASED"), b.stream().map(Entry::state).toList());
            // The held point's second STATIONARY is in the set that carries the other's MOVE.
            assertEquals(a.get(2).setId(), b.get(1).setId());
        }
    }

    @Test
    void aGrabbedPointKeepsGoingToItsNodeOffTheNode() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.play(Gesture.press(AX, Y).dragTo(BX, Y).release());
            List<Entry> e = f.touchTrace.all();
            assertTrue(e.stream().allMatch(x -> x.target().equals("root/0")), e.toString());
            assertEquals("PRESSED", f.touchTrace.types().get(0));
            assertTrue(f.touchTrace.types().contains("MOVED"));
            assertEquals("RELEASED", f.touchTrace.types().get(e.size() - 1));
        }
    }

    @Test
    void aHandlerThatUngrabsLetsTheNextEventsFindWhatIsUnderTheContact() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            runAndWaitOnFx(() -> sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab()));
            f.play(Gesture.press(AX, Y).dragTo(BX, Y).release());
            // Hops of 30 px from 40: 70 (on A), 100 and 130 (the gap), 160 and 190 (on B), then the release.
            assertEquals(List.of("root/0", "root/0", "scene", "scene", "root/1", "root/1", "root/1"),
                    targets(f.touchTrace.all()));
        }
    }

    @Test
    void grabOnAnUngrabbedPointKeepsItForTheNode() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            // A lets go on press; B, once the contact is over it, takes the point with the no-argument grab().
            runAndWaitOnFx(() -> {
                sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> e.getTouchPoint().ungrab());
                sq[1].addEventHandler(TouchEvent.TOUCH_MOVED, e -> e.getTouchPoint().grab());
            });
            f.play(Gesture.press(AX, Y).dragTo(BX + 20, Y).dragTo(AX, Y).release());
            List<Entry> e = f.touchTrace.all();
            int firstB = targets(e).indexOf("root/1");
            assertTrue(firstB > 0, "the contact was elsewhere before it reached B: " + e);
            // From B's grab on, the contact stays with B all the way back over A, to the release.
            assertTrue(targets(e).subList(firstB, e.size()).stream().allMatch("root/1"::equals), e.toString());
            assertTrue(e.size() - firstB >= 6, "the contact came back over A: " + e);
        }
    }

    @Test
    void grabOnAParentTakesTheGrabForTheParent() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            // grab() with no argument grabs the handler's node: here the Group, as the press bubbles up to it.
            runAndWaitOnFx(() -> ((Group) sq[0].getParent()).addEventHandler(TouchEvent.TOUCH_PRESSED,
                    e -> e.getTouchPoint().grab()));
            f.play(Gesture.press(AX, Y).dragTo(BX, Y).release());
            List<Entry> e = f.touchTrace.all();
            assertEquals("root/0", e.get(0).target());
            assertTrue(targets(e).subList(1, e.size()).stream().allMatch("root"::equals), e.toString());
        }
    }

    @Test
    void aHandlerCanGrabAnotherPointForItsOwnNode() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            // When A sees the second finger arrive it takes that finger's point too.
            runAndWaitOnFx(() -> sq[0].addEventHandler(TouchEvent.TOUCH_STATIONARY, e -> e.getTouchPoints().stream()
                    .filter(p -> p.getId() != e.getTouchPoint().getId())
                    .forEach(p -> p.grab(sq[0]))));
            f.playFrames(List.of(
                    List.of(down(1, AX, Y)),
                    List.of(down(1, AX, Y), down(2, BX, Y)),
                    List.of(down(1, AX, Y), down(2, BX + 25, Y)),
                    List.of(up(1, AX, Y), up(2, BX + 25, Y))));
            List<Entry> b = f.touchTrace.forPoint(2);
            // Targets are chosen before any handler runs, so the press still goes to B, though A's handler
            // (earlier in the same set) already took the grab; later events, MOVED and RELEASED, follow it.
            assertEquals(List.of("PRESSED", "MOVED", "RELEASED"), b.stream().map(x -> x.state()).toList());
            assertEquals(List.of("root/1", "root/0", "root/0"), targets(b));
            assertEquals("root/0", b.get(0).grabbed());
        }
    }

    @Test
    void aConsumedTouchEventDoesNotStopTheOtherPoint() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            List<String> seenByParent = new CopyOnWriteArrayList<>();
            runAndWaitOnFx(() -> {
                sq[0].addEventHandler(TouchEvent.ANY, TouchEvent::consume);
                sq[0].getParent().addEventHandler(TouchEvent.ANY,
                        e -> seenByParent.add(e.getEventType().getName() + e.getTouchPoint().getId()));
            });
            f.playFrames(List.of(
                    List.of(down(1, AX, Y)),
                    List.of(down(1, AX, Y), down(2, BX, Y)),
                    List.of(up(1, AX, Y), up(2, BX, Y))));
            // A consumed its own point's events, so the parent saw only B's; B's were not held back.
            assertEquals(List.of("TOUCH_PRESSED2", "TOUCH_RELEASED2"), seenByParent);
        }
    }

    @Test
    void aConsumedTouchEventDoesNotStopTheMouseStream() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            runAndWaitOnFx(() -> sq[0].addEventHandler(TouchEvent.ANY, TouchEvent::consume));
            f.play(Gesture.tap(AX, Y));
            assertTrue(f.trace.typesAt("root/0").contains("MOUSE_CLICKED"), f.trace.all().toString());
        }
    }

    @Test
    void aNodeHandlingTouchAndMouseSeesBothStreamsTouchFirst() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            List<String> seen = new CopyOnWriteArrayList<>();
            runAndWaitOnFx(() -> {
                sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> seen.add("touch"));
                sq[0].addEventHandler(MouseEvent.MOUSE_PRESSED,
                        e -> seen.add(e.isSynthesized() ? "mouse(synthesized)" : "mouse"));
            });
            f.play(Gesture.tap(AX, Y));
            assertEquals(List.of("touch", "mouse(synthesized)"), seen);
        }
    }

    @Test
    void aRemovedGrabTargetKeepsReceivingItsPointsEvents() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            List<String> seen = new CopyOnWriteArrayList<>();
            runAndWaitOnFx(() -> {
                sq[0].addEventHandler(TouchEvent.ANY, e -> seen.add(e.getEventType().getName()));
                sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e ->
                        ((Group) sq[0].getParent()).getChildren().remove(sq[0]));
            });
            f.play(Gesture.press(AX, Y).dragTo(AX + 30, Y).release());
            assertEquals(List.of("TOUCH_PRESSED", "TOUCH_MOVED", "TOUCH_RELEASED"), seen);
            // The scene's filter is no longer in the detached node's dispatch chain: it saw only the press,
            // which was dispatched before the handler removed the node.
            assertEquals(List.of("PRESSED"), f.touchTrace.types());
        }
    }

    @Test
    void aThrowingTouchHandlerDoesNotStopTheMouseStream() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            List<Throwable> uncaught = new CopyOnWriteArrayList<>();
            captureUncaught(uncaught);
            try {
                runAndWaitOnFx(() -> sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
                    throw new IllegalStateException("boom");
                }));
                f.play(Gesture.tap(AX, Y));
            } finally {
                restoreUncaught();
            }
            // The synthesizer catches a RuntimeException per handler, reports it, and carries on with the set.
            assertEquals(1, uncaught.size(), uncaught.toString());
            assertEquals("boom", uncaught.get(0).getMessage());
            assertEquals(List.of("PRESSED", "RELEASED"), f.touchTrace.types());
            assertTrue(f.trace.typesAt("root/0").contains("MOUSE_CLICKED"), f.trace.all().toString());
        }
    }

    @Test
    void anErrorFromATouchHandlerStillLetsTheMouseSideRunAndTheNextTapWork() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            List<Throwable> uncaught = new CopyOnWriteArrayList<>();
            captureUncaught(uncaught);
            try {
                runAndWaitOnFx(() -> sq[0].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
                    e.getTouchPoint().ungrab();
                    throw new TestError("fatal");
                }));
                // An Error propagates out of the touch synthesis, so the grab processing for that set is
                // skipped; EInkBridge.injectTouches still runs the mouse side.
                f.play(Gesture.tap(AX, Y));
                assertEquals(1, uncaught.size(), uncaught.toString());
                assertTrue(uncaught.get(0) instanceof TestError, uncaught.toString());
                assertTrue(f.trace.typesAt("root/0").contains("MOUSE_CLICKED"), f.trace.all().toString());
                f.touchTrace.clear();
                runAndWaitOnFx(() -> sq[1].addEventHandler(TouchEvent.TOUCH_PRESSED, e -> { }));
                f.play(Gesture.tap(BX, Y));
            } finally {
                restoreUncaught();
            }
            assertEquals(List.of("root/1", "root/1"), targets(f.touchTrace.all()));
            assertEquals(1, uncaught.size(), "the second tap raised nothing: " + uncaught);
        }
    }

    @Test
    void eachTapPicksAfreshAfterTheLastWasReleased() throws Exception {
        Rectangle[] sq = new Rectangle[2];
        try (BridgeFixture f = open(sq)) {
            f.play(Gesture.tap(AX, Y));
            f.touchTrace.clear();
            f.play(Gesture.tap(BX, Y));
            assertEquals(List.of("root/1", "root/1"), targets(f.touchTrace.all()));
        }
    }

    /** Routes the FX thread's uncaught exceptions into {@code sink}; undo with {@link #restoreUncaught()}. */
    private void captureUncaught(List<Throwable> sink) throws Exception {
        runAndWaitOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> sink.add(ex)));
    }

    /** Back to the default handler ({@code null} means the thread group's, which is what the FX thread had). */
    private void restoreUncaught() throws Exception {
        runAndWaitOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler(null));
    }
}
