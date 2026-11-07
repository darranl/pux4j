// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.Orientation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ssd1675aDisplayDriverFactoryTest {

    private final Ssd1675aDisplayDriverFactory factory = new Ssd1675aDisplayDriverFactory();

    @Test
    void physicalOrientationIsLandscapeInverted() {
        assertEquals(Orientation.LANDSCAPE, factory.physicalOrientation());
    }

    @Test
    void emptyConfigResolvesToFactoryOrientation() {
        DriverConfig resolved = factory.withDefaultOrientation(DriverConfig.builder().build());
        assertEquals("LANDSCAPE", resolved.property("orientation", "absent"));
    }

    @Test
    void explicitOrientationWins() {
        DriverConfig config = DriverConfig.builder().property("orientation", "PORTRAIT").build();
        assertEquals("PORTRAIT", factory.withDefaultOrientation(config).property("orientation", "absent"));
    }
}
