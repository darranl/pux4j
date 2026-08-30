// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.Pux4jContext;

public final class PngDisplayDriverFactory implements DisplayDriverFactory {

    private static final String NAME = "png";

    @Override
    public String name() { return NAME; }

    /** PNG driver is never auto-selected; use {@code --driver=png} to select it explicitly. */
    @Override
    public boolean isAvailable() { return false; }

    @Override
    public EInkDisplayDriver create(Pux4jContext context, DriverConfig config) {
        int width       = config.property("width",  128);
        int height      = config.property("height", 296);
        Orientation orientation = Orientation.valueOf(config.property("orientation", "LANDSCAPE"));
        String outputDir = config.property("outputDir", "target/png-frames");
        return new PngEInkDisplay(width, height, orientation, outputDir);
    }
}
