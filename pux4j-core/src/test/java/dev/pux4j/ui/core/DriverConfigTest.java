// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DriverConfigTest {

    @Test
    void builderRoundTripsProperties() {
        DriverConfig config = DriverConfig.builder()
            .property("orientation", "LANDSCAPE")
            .property("dcPin", 25)
            .build();

        assertEquals("LANDSCAPE", config.property("orientation", "unset"));
        assertEquals(25, config.property("dcPin", -1));
    }

    @Test
    void missingPropertyReturnsDefault() {
        DriverConfig config = DriverConfig.builder().build();

        assertEquals("fallback", config.property("missing", "fallback"));
        assertEquals(-1, config.property("missing", -1));
    }
}
