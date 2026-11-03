// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApplyDefaultPropertiesTest {

    private static final String KEY = "pux4j.test.defaultPropertyKey";

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
    }

    @Test
    void unsetPropertyTakesTheApplicationDefault() {
        EInkLauncher.applyDefaultProperties(Map.of(KEY, "0.8"));
        assertEquals("0.8", System.getProperty(KEY));
    }

    @Test
    void anExplicitOptionWins() {
        System.setProperty(KEY, "0.3");
        EInkLauncher.applyDefaultProperties(Map.of(KEY, "0.8"));
        assertEquals("0.3", System.getProperty(KEY));
    }

    @Test
    void noDefaultsChangesNothing() {
        EInkLauncher.applyDefaultProperties(Map.of());
        assertNull(System.getProperty(KEY));
    }
}
