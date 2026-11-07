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

    @Test
    void withDefaultAddsOnlyAbsentKey() {
        DriverConfig empty = DriverConfig.builder().build();
        assertEquals("LANDSCAPE", empty.withDefault("orientation", "LANDSCAPE").property("orientation", "unset"));

        DriverConfig explicit = DriverConfig.builder().property("orientation", "PORTRAIT").build();
        assertEquals("PORTRAIT", explicit.withDefault("orientation", "LANDSCAPE").property("orientation", "unset"));
    }

    @Test
    void withDefaultKeepsOtherPropertiesAndDoesNotMutateOriginal() {
        DriverConfig original = DriverConfig.builder().property("dcPin", 25).build();
        DriverConfig resolved = original.withDefault("orientation", "LANDSCAPE");

        assertEquals(25, resolved.property("dcPin", -1));
        assertEquals("unset", original.property("orientation", "unset"));
    }
}
