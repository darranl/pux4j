// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.dice.demo;

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
 * Yahtzee-style dice rolling demo application for pux4j.
 *
 * <p>Uses the same display architecture as the counter demo:
 * <ul>
 *   <li>{@code pux4j.display.scale} — zoom factor (default 3.0)</li>
 *   <li>{@code pux4j.display.bezel} — black eInk-frame border (default true)</li>
 * </ul>
 */
public final class DiceDemoApp extends Application {

    private static final int DISPLAY_WIDTH  = 296;
    private static final int DISPLAY_HEIGHT = 128;
    private static final int BEZEL_SIZE     = 24;
    private static final int FRAME_WIDTH    = DISPLAY_WIDTH  + 2 * BEZEL_SIZE;
    private static final int FRAME_HEIGHT   = DISPLAY_HEIGHT + 2 * BEZEL_SIZE;

    @Override
    public void start(Stage stage) throws Exception {
        double  scale     = 3.0;
        boolean showBezel = true;
        for (String arg : getParameters().getRaw()) {
            if (arg.startsWith("--scale=")) {
                scale = Double.parseDouble(arg.substring("--scale=".length()));
            } else if (arg.equals("--bezel")) {
                showBezel = true;
            } else if (arg.equals("--no-bezel")) {
                showBezel = false;
            }
        }

        var loader = new FXMLLoader(getClass().getResource("dice-demo.fxml"));
        StackPane fxmlRoot = loader.load();

        if (!showBezel) {
            fxmlRoot.getStyleClass().remove("eink-frame");
        }

        var scaledRoot = new Group(fxmlRoot);
        scaledRoot.getTransforms().add(new Scale(scale, scale, 0, 0));

        int sceneW = showBezel ? FRAME_WIDTH  : DISPLAY_WIDTH;
        int sceneH = showBezel ? FRAME_HEIGHT : DISPLAY_HEIGHT;
        var scene  = new Scene(scaledRoot, sceneW * scale, sceneH * scale);

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
        Application.launch(DiceDemoApp.class, args);
    }
}
