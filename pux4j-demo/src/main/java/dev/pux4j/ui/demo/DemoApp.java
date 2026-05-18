// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import javafx.application.Application;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/**
 * Counter demo application for pux4j.
 *
 * <p>The scene is laid out in eInk native pixels (296×128, landscape 2.9" V2 display).
 * A configurable scale factor ({@code pux4j.display.scale}, default 3.0) zooms the scene
 * uniformly for desktop viewing without changing any layout or font sizes.
 *
 * <p>At scale 1.0 the window is pixel-exact for eInk rendering; at the default 3.0 it
 * appears as an 888×384 desktop window.
 */
public final class DemoApp extends Application {

    private static final int DISPLAY_WIDTH  = 296;
    private static final int DISPLAY_HEIGHT = 128;
    private static final int BUTTON_WIDTH   = 64;
    private static final int COUNTER_MIN    = 0;
    private static final int COUNTER_MAX    = 10;

    @Override
    public void start(Stage stage) {
        double scale = Double.parseDouble(System.getProperty("pux4j.display.scale", "3.0"));

        var counter = new SimpleIntegerProperty(COUNTER_MIN);

        var decBtn = new Button("−");
        decBtn.getStyleClass().add("counter-button");
        decBtn.setPrefSize(BUTTON_WIDTH, DISPLAY_HEIGHT);
        decBtn.setMaxSize(BUTTON_WIDTH, DISPLAY_HEIGHT);
        decBtn.disableProperty().bind(counter.isEqualTo(COUNTER_MIN));
        decBtn.setOnAction(e -> counter.set(counter.get() - 1));

        var counterLabel = new Label();
        counterLabel.textProperty().bind(counter.asString());
        counterLabel.getStyleClass().add("counter-label");
        counterLabel.setPrefHeight(DISPLAY_HEIGHT);
        counterLabel.setMaxWidth(Double.MAX_VALUE);
        counterLabel.setAlignment(Pos.CENTER);

        var incBtn = new Button("+");
        incBtn.getStyleClass().add("counter-button");
        incBtn.setPrefSize(BUTTON_WIDTH, DISPLAY_HEIGHT);
        incBtn.setMaxSize(BUTTON_WIDTH, DISPLAY_HEIGHT);
        incBtn.disableProperty().bind(counter.isEqualTo(COUNTER_MAX));
        incBtn.setOnAction(e -> counter.set(counter.get() + 1));

        var root = new HBox(decBtn, counterLabel, incBtn);
        root.setPrefSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        root.setMaxSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
        HBox.setHgrow(counterLabel, Priority.ALWAYS);

        var scaledRoot = new Group(root);
        scaledRoot.getTransforms().add(new Scale(scale, scale, 0, 0));

        var scene = new Scene(scaledRoot, DISPLAY_WIDTH * scale, DISPLAY_HEIGHT * scale);
        scene.getStylesheets().add(getClass().getResource("demo.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("pux4j Counter Demo");
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        Application.launch(DemoApp.class, args);
    }
}
