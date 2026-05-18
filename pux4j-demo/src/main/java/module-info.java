// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.demo {
    requires dev.pux4j.ui.core;
    requires dev.pux4j.ui.fx;
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.fxml;
    requires org.slf4j;

    opens dev.pux4j.ui.demo to javafx.graphics, javafx.fxml;
}
