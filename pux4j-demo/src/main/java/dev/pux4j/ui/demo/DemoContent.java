// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import dev.pux4j.ui.fx.SceneContent;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.util.Map;

/** The counter demo's UI, independent of how it is hosted (desktop window or eInk bridge). */
public final class DemoContent implements SceneContent {

    @Override
    public String name() {
        return "demo";
    }

    /**
     * Pressing a button inverts it and the release then changes the button and the number at once,
     * so the changed area spans more than the default half of the panel. A larger limit keeps
     * those updates partial (about a second, no flash) instead of forcing a full refresh.
     */
    @Override
    public Map<String, String> defaultProperties() {
        return Map.of("pux4j.refresh.fullRefreshThreshold", "0.8");
    }

    @Override
    public StackPane createRoot() throws IOException {
        StackPane root = new FXMLLoader(DemoContent.class.getResource("demo.fxml")).load();
        // The FXML's black "eink-frame" bezel is a desktop-preview decoration only.
        root.getStyleClass().remove("eink-frame");
        return root;
    }
}
