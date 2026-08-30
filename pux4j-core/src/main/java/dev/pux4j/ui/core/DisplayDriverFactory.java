// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * SPI for eInk display driver implementations, discovered at runtime via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Each driver JAR registers an implementation of this interface in
 * {@code META-INF/services/dev.pux4j.ui.core.DisplayDriverFactory}.
 * Callers use {@code ServiceLoader.load(DisplayDriverFactory.class)} to
 * enumerate available drivers and select one by {@link #name()}.
 */
public interface DisplayDriverFactory {

    /**
     * Returns a unique identifier for this driver (e.g. {@code "hat-2in13v4"}).
     */
    String name();

    /**
     * Returns the priority used during auto-selection. Higher values are preferred.
     * Hardware drivers return 100; the emulator returns 50; the PNG driver returns 0 (default).
     */
    default int priority() { return 0; }

    /**
     * Returns {@code true} if this driver is available in the current environment.
     * Used to filter candidates during auto-selection. Named selection bypasses this check.
     *
     * <p>Hardware drivers return {@code false} when not running on a Raspberry Pi.
     * The PNG driver returns {@code false} always, opting out of auto-selection entirely.
     * The emulator always returns {@code true}.
     */
    default boolean isAvailable() { return true; }

    /**
     * Creates a new, uninitialised {@link EInkDisplayDriver} using the given runtime context
     * and configuration. Call {@link EInkDisplayDriver#initialize()} on the returned driver
     * before use.
     *
     * @param context runtime context providing the Pi4J hardware context; may be null for
     *                software-only drivers (e.g. the PNG renderer)
     * @param config  driver configuration properties
     * @return a new driver instance
     */
    EInkDisplayDriver create(Pux4jContext context, DriverConfig config);
}
