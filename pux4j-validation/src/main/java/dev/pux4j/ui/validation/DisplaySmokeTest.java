// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import com.pi4j.Pi4J;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.RefreshMode;
import jakarta.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Phase 1a hardware smoke test: initialise the SSD1675A and write a test pattern.
 * Run on Pi 500+ with the 2.9" V2 HAT attached.
 *
 * Usage: java -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest [driver]
 *   driver defaults to "ssd1675a"
 */
public final class DisplaySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(DisplaySmokeTest.class);

    private static final int WIDTH  = 128;
    private static final int HEIGHT = 296;
    private static final int FRAME_BYTES = (WIDTH / 8) * HEIGHT; // 4736

    public static void main(String[] args) throws Exception {
        String driverName = args.length > 0 ? args[0] : "ssd1675a";
        log.info("DisplaySmokeTest: driver={}", driverName);

        var factory = findFactory(driverName);
        log.info("DisplaySmokeTest: found factory {}", factory.getClass().getName());

        var pi4j = Pi4J.newAutoContext();
        try {
            var json = Json.createObjectBuilder()
                .add("orientation", "PORTRAIT")
                .build();
            var config = DriverConfig.ofHardware(pi4j, json);
            var driver = factory.create(config);

            log.info("DisplaySmokeTest: initialize");
            driver.initialize();

            // Frame 1: checkerboard — alternating 0xAA / 0x55 rows
            log.info("DisplaySmokeTest: write checkerboard frame");
            byte[] checkerboard = new byte[FRAME_BYTES];
            for (int row = 0; row < HEIGHT; row++) {
                byte fill = (row % 2 == 0) ? (byte)0xAA : 0x55;
                for (int col = 0; col < WIDTH / 8; col++) {
                    checkerboard[row * (WIDTH / 8) + col] = fill;
                }
            }
            driver.writeFrame(new MonochromeFrame(checkerboard, RefreshMode.FULL)).get();
            log.info("DisplaySmokeTest: checkerboard done, waiting 5s");
            Thread.sleep(5_000);

            // Frame 2: all white (0xFF = white in the SSD1675A bit encoding)
            log.info("DisplaySmokeTest: write all-white frame");
            byte[] allWhite = new byte[FRAME_BYTES];
            java.util.Arrays.fill(allWhite, (byte)0xFF);
            driver.writeFrame(new MonochromeFrame(allWhite, RefreshMode.FULL)).get();
            log.info("DisplaySmokeTest: all-white done");

            driver.sleep();
            log.info("DisplaySmokeTest: display asleep — PASS");
        } finally {
            pi4j.shutdown();
        }
    }

    private static DisplayDriverFactory findFactory(String name) {
        return ServiceLoader.load(DisplayDriverFactory.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(f -> f.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No DisplayDriverFactory named '" + name + "' found via ServiceLoader"));
    }
}
