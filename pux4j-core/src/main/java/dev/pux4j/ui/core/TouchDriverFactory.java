// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * SPI for touch driver implementations, discovered at runtime via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Each driver JAR registers an implementation of this interface in
 * {@code META-INF/services/dev.pux4j.ui.core.TouchDriverFactory}.
 * Callers use {@code ServiceLoader.load(TouchDriverFactory.class)} to
 * enumerate available drivers and select one by {@link #name()}.
 */
public interface TouchDriverFactory {

    /**
     * Returns a unique identifier for this touch driver (e.g. {@code "icnt86x"}).
     */
    String name();

    /**
     * Creates a new, uninitialised {@link TouchDriver} using the given runtime context
     * and configuration. Call {@link TouchDriver#initialize()} on the returned driver
     * before use.
     *
     * @param context runtime context providing the Pi4J hardware context
     * @param config  driver configuration properties
     * @return a new driver instance
     */
    TouchDriver create(Pux4jContext context, DriverConfig config);
}
