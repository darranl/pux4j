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
     * Selects a factory by name, or — when {@code requestedName} is null — the
     * highest-priority factory reporting {@link #isAvailable()}.
     *
     * @param requestedName the name to select, or {@code null} to auto-select
     * @return the selected factory
     * @throws IllegalStateException if no match is found, listing what was seen
     */
    static DisplayDriverFactory select(String requestedName) {
        return ServiceLoaderUtil.selectProvider(
                DisplayDriverFactory.class,
                DisplayDriverFactory::name,
                DisplayDriverFactory::priority,
                DisplayDriverFactory::isAvailable,
                requestedName,
                "display driver factory");
    }

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

    /**
     * Returns this display's fixed physical mounting orientation — the panel's real,
     * bonded-at-manufacture orientation relative to its chip's native (portrait) framebuffer.
     * Mirrors {@link TouchDriverFactory#touchCalibration}: a fixed hardware fact owned by the
     * factory rather than supplied separately by every caller, which is what previously let it
     * drift out of sync across build profiles, launcher scripts, and test defaults.
     *
     * <p>A real hardware driver or the emulator returns a fixed constant. Deliberately not a
     * default method: a driver with no single fixed physical orientation (e.g. the PNG
     * renderer, which has no physical mounting at all) must say so explicitly by throwing
     * {@link UnsupportedOperationException} itself, so a new driver that forgets this method
     * entirely fails to compile rather than failing at runtime on hardware.
     *
     * @return this display's fixed physical mounting orientation
     * @throws UnsupportedOperationException if this driver has no fixed physical orientation
     */
    Orientation physicalOrientation();
}
