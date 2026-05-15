// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.ssd1680;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;

public final class Ssd1680DisplayDriverFactory implements DisplayDriverFactory {

    @Override
    public String name() { return "ssd1680"; }

    @Override
    public EInkDisplayDriver create(DriverConfig config) {
        return new Ssd1680DisplayDriver(config);
    }
}
