// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.emulator {
    requires dev.pux4j.ui.core;
    requires javafx.controls;
    requires javafx.graphics;
    requires org.slf4j;

    exports dev.pux4j.ui.emulator;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.emulator.EmulatedDisplayDriverFactory;
    provides dev.pux4j.ui.core.TouchDriverFactory
        with dev.pux4j.ui.emulator.EmulatedTouchDriverFactory;
}
