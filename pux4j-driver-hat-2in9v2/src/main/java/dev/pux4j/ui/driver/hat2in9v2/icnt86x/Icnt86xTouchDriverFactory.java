// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Icnt86xTouchDriverFactory implements TouchDriverFactory {

    @Override
    public String name() { return "icnt86x"; }

    @Override
    public int priority() { return 100; }

    @Override
    public boolean isAvailable() {
        try {
            return Files.readString(Path.of("/proc/device-tree/model")).startsWith("Raspberry Pi");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public TouchDriver create(Pux4jContext context, DriverConfig config) {
        return new Icnt86xTouchDriver(context, config);
    }
}
