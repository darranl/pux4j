// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.validation {
    requires dev.pux4j.ui.app;
    requires dev.pux4j.ui.core;
    requires dev.pux4j.ui.transform;
    requires org.slf4j;

    provides dev.pux4j.ui.app.DriverApp
        with dev.pux4j.ui.validation.SmokeDriverApp,
             dev.pux4j.ui.validation.ValidationDriverApp;
}
