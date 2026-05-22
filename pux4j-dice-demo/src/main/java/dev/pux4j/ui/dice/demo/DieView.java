// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.dice.demo;

import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * A resizable Pane that renders a single die face using JavaFX scene-graph shapes.
 *
 * <p>Uses {@link Rectangle} nodes for the background border and pips so that only
 * the ES2 fill/draw parallelogram shaders are needed — both are already registered
 * in reachability-metadata.json for the counter demo native image.
 *
 * <p>Not loaded from FXML — created programmatically by {@link DiceController}.
 */
final class DieView extends Pane {

    /** Pip grid positions (row, col) for values 1–6. Row and col are 0–2. */
    private static final int[][][] PIPS = {
        {},                                              // 0 — blank
        {{1, 1}},                                        // 1
        {{0, 2}, {2, 0}},                                // 2
        {{0, 2}, {1, 1}, {2, 0}},                        // 3
        {{0, 0}, {0, 2}, {2, 0}, {2, 2}},                // 4
        {{0, 0}, {0, 2}, {1, 1}, {2, 0}, {2, 2}},        // 5
        {{0, 0}, {0, 2}, {1, 0}, {1, 2}, {2, 0}, {2, 2}} // 6
    };

    private int     value = 0;
    private boolean saved = false;

    DieView() {
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    int     getValue()        { return value; }
    boolean isSaved()         { return saved; }
    void    setValue(int v)   { value = v; }
    void    setSaved(boolean s) { saved = s; }

    void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        getChildren().clear();

        Color bg = saved ? Color.BLACK : Color.WHITE;
        Color fg = saved ? Color.WHITE : Color.BLACK;

        double margin      = Math.min(w, h) * 0.06;
        double dw          = w - 2 * margin;
        double dh          = h - 2 * margin;
        double borderWidth = Math.max(1.5, Math.min(w, h) * 0.04);
        double arc         = Math.min(dw, dh) * 0.18;

        // Border rectangle (drawn as outer rounded rect filled with fg colour).
        Rectangle border = new Rectangle(margin, margin, dw, dh);
        border.setArcWidth(arc);
        border.setArcHeight(arc);
        border.setFill(fg);

        // Inner face (slightly smaller rounded rect filled with bg colour).
        double innerArc = Math.max(0, arc - borderWidth);
        Rectangle face = new Rectangle(margin + borderWidth, margin + borderWidth,
                                       dw - 2 * borderWidth, dh - 2 * borderWidth);
        face.setArcWidth(innerArc);
        face.setArcHeight(innerArc);
        face.setFill(bg);

        getChildren().addAll(border, face);

        if (value > 0) {
            double pipR = Math.min(dw, dh) * 0.11;
            double pad  = Math.min(dw, dh) * 0.22;
            for (int[] pos : PIPS[value]) {
                double cx = margin + pad + pos[1] * (dw - 2 * pad) / 2.0;
                double cy = margin + pad + pos[0] * (dh - 2 * pad) / 2.0;
                Circle dot = new Circle(cx, cy, pipR);
                dot.setFill(fg);
                getChildren().add(dot);
            }
        }
    }
}
