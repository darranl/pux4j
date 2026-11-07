// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.ssd1680;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Orientation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ssd1680DisplayDriverFactoryTest {

    private final Ssd1680DisplayDriverFactory factory = new Ssd1680DisplayDriverFactory();

    @Test
    void physicalOrientationIsLandscapeInverted() {
        assertEquals(Orientation.LANDSCAPE_INVERTED, factory.physicalOrientation());
    }

    @Test
    void emptyConfigResolvesToFactoryOrientation() {
        DriverConfig resolved = factory.withDefaultOrientation(DriverConfig.builder().build());
        assertEquals("LANDSCAPE_INVERTED", resolved.property("orientation", "absent"));
    }

    @Test
    void explicitOrientationWins() {
        DriverConfig config = DriverConfig.builder().property("orientation", "PORTRAIT").build();
        assertEquals("PORTRAIT", factory.withDefaultOrientation(config).property("orientation", "absent"));
    }
}
