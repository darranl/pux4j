package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.Orientation;

public final class PngDisplayDriverFactory implements DisplayDriverFactory {

    private static final String NAME = "png";

    @Override
    public String name() { return NAME; }

    @Override
    public EInkDisplayDriver create(DriverConfig config) {
        var json        = config.config();
        int width       = json.getInt("width",  128);
        int height      = json.getInt("height", 296);
        var orientation = Orientation.valueOf(json.getString("orientation", "PORTRAIT"));
        var outputDir   = json.getString("outputDir", "target/png-frames");
        return new PngEInkDisplay(width, height, orientation, outputDir);
    }
}
