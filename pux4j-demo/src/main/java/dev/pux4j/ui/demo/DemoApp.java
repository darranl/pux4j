package dev.pux4j.ui.demo;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Reference JavaFX demo application for pux4j.
 * Phase 0: bare stub to validate the GraalVM+JavaFX pipeline before hardware is added.
 * Phase 5: full integration with EInkBridge and driver selection.
 */
public final class DemoApp extends Application {

    @Override
    public void start(Stage stage) {
        var label  = new Label("pux4j Demo");
        var button = new Button("Refresh");
        button.setOnAction(e -> label.setText("Refreshed"));

        var root  = new VBox(16, label, button);
        var scene = new Scene(root, 296, 128);
        stage.setScene(scene);
        stage.setTitle("pux4j Demo");
        stage.show();
    }

    public static void main(String[] args) {
        Application.launch(DemoApp.class, args);
    }
}
