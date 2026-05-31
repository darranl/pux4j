// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for display and touch driver factories.
 *
 * <p>Holds typed extracted values rather than a raw JSON object, so driver
 * implementations are decoupled from the configuration source format. JSON is one
 * supported input path; programmatic construction via {@link Builder} is the
 * alternative for test or embedded use-cases.
 *
 * <p>The Pi4J runtime context is supplied separately via {@link Pux4jContext} when
 * creating drivers, keeping configuration data and runtime lifecycle distinct.
 *
 * <p>Example — from JSON:
 * <pre>{@code
 * DriverConfig config = DriverConfig.of(Json.createObjectBuilder()
 *     .add("orientation", "LANDSCAPE")
 *     .build());
 * }</pre>
 *
 * <p>Example — programmatic:
 * <pre>{@code
 * DriverConfig config = DriverConfig.builder()
 *     .strProperty("orientation", "LANDSCAPE")
 *     .intProperty("dcPin", 25)
 *     .build();
 * }</pre>
 */
public final class DriverConfig {

    private final Map<String, Integer> intProperties;
    private final Map<String, String>  strProperties;

    private DriverConfig(Map<String, Integer> intProperties, Map<String, String> strProperties) {
        this.intProperties = Map.copyOf(intProperties);
        this.strProperties = Map.copyOf(strProperties);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a typed configuration by parsing a JSON object.
     *
     * <p>JSON number values are extracted as integers (via {@code intValue()}),
     * and JSON string values are extracted as strings. Other JSON value types
     * are stored as their string representation.
     *
     * @param json driver-specific JSON configuration; must not be null
     * @throws NullPointerException if {@code json} is null
     */
    public static DriverConfig of(JsonObject json) {
        Objects.requireNonNull(json, "json");
        return parseJson(json);
    }

    /**
     * Returns a new {@link Builder} for programmatic configuration construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Property accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the integer property for {@code key}, or {@code defaultValue} if absent.
     *
     * @param key          the configuration key
     * @param defaultValue value returned when the key is absent
     * @return the configured integer value, or {@code defaultValue}
     */
    public int intProperty(String key, int defaultValue) {
        return intProperties.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the string property for {@code key}, or {@code defaultValue} if absent.
     *
     * @param key          the configuration key
     * @param defaultValue value returned when the key is absent
     * @return the configured string value, or {@code defaultValue}
     */
    public String strProperty(String key, String defaultValue) {
        return strProperties.getOrDefault(key, defaultValue);
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    private static DriverConfig parseJson(JsonObject json) {
        Map<String, Integer> intProps = new HashMap<>();
        Map<String, String>  strProps = new HashMap<>();
        for (Map.Entry<String, jakarta.json.JsonValue> entry : json.entrySet()) {
            switch (entry.getValue().getValueType()) {
                case NUMBER -> intProps.put(entry.getKey(), ((JsonNumber) entry.getValue()).intValue());
                case STRING -> strProps.put(entry.getKey(), ((JsonString) entry.getValue()).getString());
                default     -> strProps.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return new DriverConfig(intProps, strProps);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Builder for programmatic {@link DriverConfig} construction, without requiring a JSON source.
     */
    public static final class Builder {

        private final Map<String, Integer> intProperties = new HashMap<>();
        private final Map<String, String>  strProperties = new HashMap<>();

        private Builder() {}

        /**
         * Adds an integer property.
         */
        public Builder intProperty(String key, int value) {
            intProperties.put(key, value);
            return this;
        }

        /**
         * Adds a string property.
         */
        public Builder strProperty(String key, String value) {
            strProperties.put(key, value);
            return this;
        }

        /**
         * Builds and returns the {@link DriverConfig}.
         */
        public DriverConfig build() {
            return new DriverConfig(intProperties, strProperties);
        }
    }
}
