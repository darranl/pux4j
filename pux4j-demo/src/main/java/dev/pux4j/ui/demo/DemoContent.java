// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import dev.pux4j.ui.fx.SceneContent;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.StackPane;

import java.io.IOException;

/** The counter demo's UI, independent of how it is hosted (desktop window or eInk bridge). */
public final class DemoContent implements SceneContent {

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public StackPane createRoot() throws IOException {
        StackPane root = new FXMLLoader(DemoContent.class.getResource("demo.fxml")).load();
        // The FXML's black "eink-frame" bezel is a desktop-preview decoration only.
        root.getStyleClass().remove("eink-frame");
        return root;
    }
}
