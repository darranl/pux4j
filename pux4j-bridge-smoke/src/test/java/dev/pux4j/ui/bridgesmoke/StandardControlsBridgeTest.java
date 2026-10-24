// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.runAndWaitOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock JavaFX controls and picking rules, unmodified, driven by touches: what an ordinary app
 * relies on without knowing it is on an e-ink panel.
 */
class StandardControlsBridgeTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    @Test
    void sliderThumbCanBeDragged() throws Exception {
        Slider[] slider = new Slider[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            slider[0] = new Slider(0, 100, 0);
            slider[0].setPrefWidth(200);
            slider[0].relocate(20, 50);
            return new Pane(slider[0]);
        })) {
            Node thumb = callAndWaitOnFx(() -> slider[0].lookup(".thumb"));
            int[] t = f.centre(thumb);
            f.play(Gesture.press(t[0], t[1]).dragTo(t[0] + 100, t[1]).release());
            double value = callAndWaitOnFx(slider[0]::getValue);
            assertTrue(value > 30, "slider value after a 100 px drag: " + value);
        }
    }

    /**
     * Pins the documented gap (docs/input-event-mapping.md, "Controls that open a popup are not
     * supported"): a popup needs an owner window and the bridge's scene has none. If a JavaFX
     * upgrade or a bridge change makes this pass without an exception, update the doc row.
     */
    @Test
    void comboBoxPopupFailsForWantOfAnOwnerWindow() throws Exception {
        ComboBox<String>[] combo = new ComboBox[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            combo[0] = new ComboBox<>();
            combo[0].getItems().addAll("one", "two");
            combo[0].setPrefSize(120, 30);
            combo[0].relocate(20, 20);
            return new Pane(combo[0]);
        })) {
            List<Throwable> uncaught = new CopyOnWriteArrayList<>();
            runAndWaitOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> uncaught.add(ex)));
            try {
                int[] c = f.centre(combo[0]);
                f.play(Gesture.tap(c[0], c[1]));
            } finally {
                runAndWaitOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler(null));
            }
            assertTrue(uncaught.stream().anyMatch(e -> e instanceof NullPointerException
                            && String.valueOf(e.getMessage()).contains("Owner window")),
                    uncaught.toString());
            // The control sets its showing state before the popup throws, so it stays "showing".
            assertTrue(callAndWaitOnFx(combo[0]::isShowing), "showing state after the failed popup");
        }
    }

    @Test
    void checkBoxTapToggles() throws Exception {
        CheckBox[] box = new CheckBox[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            box[0] = new CheckBox("Sound");
            box[0].relocate(20, 20);
            return new Pane(box[0]);
        })) {
            int[] c = f.centre(box[0]);
            f.play(Gesture.tap(c[0], c[1]));
            assertTrue(callAndWaitOnFx(box[0]::isSelected));
            f.play(Gesture.tap(c[0], c[1]));
            assertTrue(!callAndWaitOnFx(box[0]::isSelected));
        }
    }

    @Test
    void filtersRunBeforeHandlersAndConsumedEventsStopBubbling() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Button[] button = new Button[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            button[0] = new Button("Go");
            button[0].setPrefSize(100, 40);
            button[0].relocate(20, 20);
            button[0].addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                order.add("child handler");
                e.consume();
            });
            Pane root = new Pane(button[0]);
            root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> order.add("parent filter"));
            root.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> order.add("parent handler"));
            return root;
        })) {
            int[] c = f.centre(button[0]);
            f.play(Gesture.tap(c[0], c[1]));
            assertEquals(List.of("parent filter", "child handler"), order);
        }
    }

    @Test
    void mouseTransparentOverlayLetsTheTouchThrough() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Button[] button = new Button[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            button[0] = new Button("Go");
            button[0].setPrefSize(100, 40);
            button[0].relocate(20, 20);
            button[0].setOnAction(e -> actions.incrementAndGet());
            Region overlay = new Region();
            overlay.setPrefSize(250, 122);
            overlay.setMouseTransparent(true);
            return new Pane(button[0], overlay);
        })) {
            int[] c = f.centre(button[0]);
            f.play(Gesture.tap(c[0], c[1]));
            assertEquals(1, actions.get());
        }
    }

    @Test
    void shapesAreHitTestedOnTheirFillNotTheirBounds() throws Exception {
        AtomicInteger circleHits = new AtomicInteger();
        Circle[] circle = new Circle[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            circle[0] = new Circle(50, 60, 40);
            circle[0].setOnMouseClicked(e -> circleHits.incrementAndGet());
            return new Pane(circle[0]);
        })) {
            f.play(Gesture.tap(50, 60));
            assertEquals(1, circleHits.get(), "centre hits the circle");
            f.play(Gesture.tap(14, 24));
            assertEquals(1, circleHits.get(), "the bounding-box corner is outside the circle");
        }
    }

    @Test
    void clippedPartOfAChildIsNotPickable() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Button[] button = new Button[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            button[0] = new Button("Go");
            button[0].setPrefSize(100, 40);
            button[0].setOnAction(e -> actions.incrementAndGet());
            Pane holder = new Pane(button[0]);
            holder.setPrefSize(50, 50);
            holder.setClip(new Rectangle(50, 50));
            holder.relocate(20, 20);
            return new Pane(holder);
        })) {
            f.play(Gesture.tap(20 + 80, 20 + 20));
            assertEquals(0, actions.get(), "outside the clip");
            f.play(Gesture.tap(20 + 25, 20 + 20));
            assertEquals(1, actions.get(), "inside the clip");
        }
    }

    @Test
    void pannableScrollPaneFollowsADrag() throws Exception {
        ScrollPane[] pane = new ScrollPane[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            pane[0] = new ScrollPane(new Rectangle(600, 500));
            pane[0].setPannable(true);
            pane[0].setPrefViewportWidth(200);
            pane[0].setPrefViewportHeight(100);
            pane[0].relocate(10, 10);
            return new Pane(pane[0]);
        })) {
            f.play(Gesture.press(150, 80).dragTo(60, 30).release());
            double h = callAndWaitOnFx(pane[0]::getHvalue);
            double v = callAndWaitOnFx(pane[0]::getVvalue);
            assertTrue(h > 0 && v > 0, "scrolled by the drag: h=" + h + " v=" + v);
        }
    }
}
