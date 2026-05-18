// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.demo;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.net.URL;
import java.util.ResourceBundle;

public final class DemoController implements Initializable {

    private static final int COUNTER_MIN = 0;
    private static final int COUNTER_MAX = 10;

    @FXML private Button decBtn;
    @FXML private Button incBtn;
    @FXML private Label  counterLabel;

    private final SimpleIntegerProperty counter = new SimpleIntegerProperty(COUNTER_MIN);

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        counterLabel.setMaxWidth(Double.MAX_VALUE);
        counterLabel.textProperty().bind(counter.asString());
        decBtn.disableProperty().bind(counter.isEqualTo(COUNTER_MIN));
        incBtn.disableProperty().bind(counter.isEqualTo(COUNTER_MAX));
    }

    @FXML
    private void onDecrement() {
        counter.set(counter.get() - 1);
    }

    @FXML
    private void onIncrement() {
        counter.set(counter.get() + 1);
    }
}
