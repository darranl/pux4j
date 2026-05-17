// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.driver.hat2in9v2 {
    requires dev.pux4j.ui.core;
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.driver.hat2in9v2.ssd1675a.Ssd1675aDisplayDriverFactory;

    provides dev.pux4j.ui.core.TouchDriverFactory
        with dev.pux4j.ui.driver.hat2in9v2.icnt86x.Icnt86xTouchDriverFactory;
}
