// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Counter demo application for pux4j.
 *
 * <p>The scene is laid out in eInk native pixels (296×128, landscape 2.9" V2 display).
 * Two system properties control the desktop presentation:
 * <ul>
 *   <li>{@code pux4j.display.scale} — zoom factor (default 3.0); use 1.0 for pixel-exact
 *       eInk rendering</li>
 *   <li>{@code pux4j.display.bezel} — show black eInk-frame border (default true); set false
 *       when running on actual eInk hardware where the physical panel provides its own bezel</li>
 * </ul>
 */
public final class DemoApp extends Application {

    private static final int DISPLAY_WIDTH  = 296;
    private static final int DISPLAY_HEIGHT = 128;
    private static final int BEZEL_SIZE     = 24;
    private static final int FRAME_WIDTH    = DISPLAY_WIDTH  + 2 * BEZEL_SIZE;
    private static final int FRAME_HEIGHT   = DISPLAY_HEIGHT + 2 * BEZEL_SIZE;

    @Override
    public void start(Stage stage) throws Exception {
        double scale     = Double.parseDouble(System.getProperty("pux4j.display.scale", "3.0"));
        boolean showBezel = Boolean.parseBoolean(System.getProperty("pux4j.display.bezel", "true"));

        var loader = new FXMLLoader(getClass().getResource("demo.fxml"));
        StackPane fxmlRoot = loader.load();

        if (!showBezel) {
            fxmlRoot.getStyleClass().remove("eink-frame");
        }

        var scaledRoot = new Group(fxmlRoot);
        scaledRoot.getTransforms().add(new Scale(scale, scale, 0, 0));

        int sceneW = showBezel ? FRAME_WIDTH  : DISPLAY_WIDTH;
        int sceneH = showBezel ? FRAME_HEIGHT : DISPLAY_HEIGHT;
        var scene = new Scene(scaledRoot, sceneW * scale, sceneH * scale);

        var exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> Platform.exit());
        var contextMenu = new ContextMenu(exitItem);
        scene.setOnContextMenuRequested(e ->
                contextMenu.show(scene.getWindow(), e.getScreenX(), e.getScreenY()));

        var drag = new double[]{0, 0};
        scene.setOnMousePressed(e -> {
            drag[0] = stage.getX() - e.getScreenX();
            drag[1] = stage.getY() - e.getScreenY();
        });
        scene.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + drag[0]);
            stage.setY(e.getScreenY() + drag[1]);
        });

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        Application.launch(DemoApp.class, args);
    }
}
