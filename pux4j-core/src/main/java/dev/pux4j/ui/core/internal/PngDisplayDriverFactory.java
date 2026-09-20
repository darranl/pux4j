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
        // "width"/"height" are native (portrait-shaped) framebuffer dimensions — the same
        // space MonochromeFrame/FourGrayFrame data and writeRegion coordinates are in — not
        // the logical/on-screen dimensions produced by rotating for "orientation". Defaults
        // match the SSD1675A (2.9" V2) native framebuffer.
        int width       = config.property("width",  128);
        int height      = config.property("height", 296);
        Orientation orientation = Orientation.valueOf(config.property("orientation", "LANDSCAPE"));
        String outputDir = config.property("outputDir", "target/png-frames");
        return new PngEInkDisplay(width, height, orientation, outputDir);
    }

    /** The PNG renderer has no physical mounting; callers must supply orientation explicitly
     * via the {@code DriverConfig} "orientation" property instead (see {@link #create}). */
    @Override
    public Orientation physicalOrientation() {
        throw new UnsupportedOperationException(NAME + " has no fixed physical orientation; supply one explicitly");
    }
}
