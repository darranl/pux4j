// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.driver.hat2in13v4 {
    requires dev.pux4j.ui.core;
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.driver.hat2in13v4.ssd1680.Ssd1680DisplayDriverFactory;

    provides dev.pux4j.ui.core.TouchDriverFactory
        with dev.pux4j.ui.driver.hat2in13v4.gt1151q.Gt1151qTouchDriverFactory;
}
