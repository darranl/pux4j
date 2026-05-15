// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.core {
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    exports dev.pux4j.ui.core;

    uses dev.pux4j.ui.core.DisplayDriverFactory;
    uses dev.pux4j.ui.core.TouchDriverFactory;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.core.internal.PngDisplayDriverFactory,
             dev.pux4j.ui.core.internal.ssd1675a.Ssd1675aDisplayDriverFactory,
             dev.pux4j.ui.core.internal.ssd1680.Ssd1680DisplayDriverFactory;

    provides dev.pux4j.ui.core.TouchDriverFactory
        with dev.pux4j.ui.core.internal.icnt86x.Icnt86xTouchDriverFactory,
             dev.pux4j.ui.core.internal.gt1151q.Gt1151qTouchDriverFactory;
}
