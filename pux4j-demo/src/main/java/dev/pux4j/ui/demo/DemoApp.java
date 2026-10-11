// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Desktop window for the counter demo, laid out for a 296×128 display. The app itself knows
 * nothing about eInk drivers: to run it on the emulator or a panel, bundle {@link DemoContent}
 * with {@code pux4j-launcher} and the desired drivers instead.
 *
 * <p>Options: {@code --scale=N} (zoom, default 3.0; 1 is pixel-exact) and {@code --no-bezel}
 * (drop the black eInk-frame border).
 */
public final class DemoApp extends Application {

    private static final int DISPLAY_WIDTH  = 296;
    private static final int DISPLAY_HEIGHT = 128;
    private static final int BEZEL_SIZE     = 24;

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

        StackPane root = new DemoContent().createRoot();
        if (showBezel) {
            root.getStyleClass().add("eink-frame");
        }
        int bezel  = showBezel ? BEZEL_SIZE : 0;
        int frameW = DISPLAY_WIDTH  + 2 * bezel;
        int frameH = DISPLAY_HEIGHT + 2 * bezel;
        // The FXML fixes no size; the Group lays the root out at its pref size.
        root.setPrefSize(frameW, frameH);

        var scaledRoot = new Group(root);
        scaledRoot.getTransforms().add(new Scale(scale, scale, 0, 0));
        var scene = new Scene(scaledRoot, frameW * scale, frameH * scale);

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
