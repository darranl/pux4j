// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.app.DriverApp;

/** Launcher entry for {@link DisplaySmokeTest} ({@code pux4j smoke [driver]}). */
public final class SmokeDriverApp implements DriverApp {

    @Override
    public String name() {
        return "smoke";
    }

    @Override
    public String description() {
        return "Display smoke test: raw display driver, no bridge";
    }

    @Override
    public int run(String[] args) throws Exception {
        DisplaySmokeTest.main(args);
        return 0;
    }
}
