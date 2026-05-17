// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

public final class Icnt86xTouchDriverFactory implements TouchDriverFactory {

    @Override
    public String name() { return "icnt86x"; }

    @Override
    public TouchDriver create(DriverConfig config) {
        return new Icnt86xTouchDriver(config);
    }
}
