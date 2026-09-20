// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.Pux4jContext;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Ssd1675aDisplayDriverFactory implements DisplayDriverFactory {

    @Override
    public String name() { return "ssd1675a"; }

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
    public EInkDisplayDriver create(Pux4jContext context, DriverConfig config) {
        return new Ssd1675aDisplayDriver(context, config);
    }

    /** hat-2in9v2/SSD1675A is physically mounted 180 degrees from hat-2in13v4/SSD1680. */
    @Override
    public Orientation physicalOrientation() { return Orientation.LANDSCAPE; }
}
