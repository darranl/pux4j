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

    @Override
    public EInkDisplayDriver create(Pux4jContext context, DriverConfig config) {
        int width       = config.intProperty("width",  128);
        int height      = config.intProperty("height", 296);
        Orientation orientation = Orientation.valueOf(config.strProperty("orientation", "PORTRAIT"));
        String outputDir = config.strProperty("outputDir", "target/png-frames");
        return new PngEInkDisplay(width, height, orientation, outputDir);
    }
}
