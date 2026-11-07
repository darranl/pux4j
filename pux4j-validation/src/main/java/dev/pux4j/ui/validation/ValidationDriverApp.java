// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.app.DriverApp;

/** Launcher entry for {@link HardwareValidationTest} ({@code pux4j validation [options]}). */
public final class ValidationDriverApp implements DriverApp {

    @Override
    public String name() {
        return "validation";
    }

    @Override
    public String description() {
        return "Interactive hardware acceptance test: display and touch drivers";
    }

    @Override
    public int run(String[] args) {
        HardwareValidationTest.main(args);
        return 0;
    }
}
