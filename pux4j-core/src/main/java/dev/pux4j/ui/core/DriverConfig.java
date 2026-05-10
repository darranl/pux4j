// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import com.pi4j.context.Context;
import jakarta.json.JsonObject;
import java.util.Objects;

/**
 * Configuration for display and touch driver factories.
 * Hardware drivers need a Pi4J {@link Context}; software drivers (PNG, emulator)
 * pass {@code null} and ignore it.
 */
public final class DriverConfig {

    private final Context    pi4j;
    private final JsonObject config;

    private DriverConfig(Context pi4j, JsonObject config) {
        this.pi4j   = pi4j;
        this.config = Objects.requireNonNull(config, "config");
    }

    /** For hardware drivers that require a Pi4J context. */
    public static DriverConfig ofHardware(Context pi4j, JsonObject config) {
        return new DriverConfig(
            Objects.requireNonNull(pi4j, "pi4j"), config);
    }

    /** For software drivers (PNG, emulator) that do not need Pi4J. */
    public static DriverConfig ofSoftware(JsonObject config) {
        return new DriverConfig(null, config);
    }

    /** Pi4J context. Null for software drivers — check before use. */
    public Context pi4j() { return pi4j; }

    public JsonObject config() { return config; }
}
