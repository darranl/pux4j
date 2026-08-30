// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * JavaFX stage hosting the eInk canvas emulator.
 *
 * <p>Usage — from {@code Application.start(Stage)}:
 * <pre>{@code
 * // Native (portrait-shaped) dimensions, not the panel's marketed landscape resolution —
 * // see EmulatedEInkDisplay's native/screen dimension split.
 * var display = new EmulatedEInkDisplay(128, 296, Orientation.LANDSCAPE,
 *     EnumSet.of(PixelFormat.MONOCHROME), EnumSet.of(RefreshMode.FULL));
 * var touch = new EmulatedTouchDriver();
 * var window = EInkEmulatorWindow.create("SSD1675A (2.9\" V2)", display, touch, primaryStage);
 * window.show();
 * }</pre>
 *
 * <p>Must be called on the JavaFX Application Thread.
 */
public final class EInkEmulatorWindow {

    private final Stage stage;

    private EInkEmulatorWindow(Stage stage) {
        this.stage = stage;
    }

    /**
     * Creates the emulator window for {@code display}, wires mouse events to {@code touch},
     * and configures {@code stage}.
     *
     * @param displayName label shown in the window title, e.g. {@code "SSD1680 (2.13\" V4)"}
     * @param display     the emulated display; receives canvas and pixel writes
     * @param touch       the emulated touch driver; receives translated mouse events
     * @param stage       the JavaFX stage to configure
     */
    public static EInkEmulatorWindow create(String displayName,
                                             EmulatedEInkDisplay display,
                                             EmulatedTouchDriver touch,
                                             Stage stage) {
        int sf = display.scaleFactor();
        int canvasW = display.screenWidth()  * sf;
        int canvasH = display.screenHeight() * sf;

        Canvas canvas = new Canvas(canvasW, canvasH);

        // Initial state: blank white panel
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvasW, canvasH);

        display.attachCanvas(canvas);

        canvas.setOnMousePressed(e  -> touch.onMousePressed(e.getX(),  e.getY(),  sf));
        canvas.setOnMouseReleased(e -> touch.onMouseReleased(e.getX(), e.getY(), sf));
        canvas.setFocusTraversable(true);

        var root = new Group(canvas);
        var scene = new Scene(root, canvasW, canvasH, Color.WHITE);
        stage.setScene(scene);
        stage.setTitle("pux4j Emulator — " + displayName
                        + " (" + display.screenWidth() + "×" + display.screenHeight() + ")"
                        + " @ " + sf + "×");
        stage.setResizable(false);

        return new EInkEmulatorWindow(stage);
    }

    /**
     * Creates the emulator window, deriving the title from the display dimensions.
     */
    public static EInkEmulatorWindow create(EmulatedEInkDisplay display,
                                             EmulatedTouchDriver touch,
                                             Stage stage) {
        return create(display.screenWidth() + "×" + display.screenHeight(), display, touch, stage);
    }

    /** Shows the emulator window. Must be called on the JavaFX Application Thread. */
    public void show() {
        stage.show();
    }
}
