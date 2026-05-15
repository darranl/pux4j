// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal.gt1151q;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

public final class Gt1151qTouchDriverFactory implements TouchDriverFactory {

    @Override
    public String name() { return "gt1151q"; }

    @Override
    public TouchDriver create(DriverConfig config) {
        return new Gt1151qTouchDriver(config);
    }
}
