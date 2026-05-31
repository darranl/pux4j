// SPDX-License-Identifier: Apache-2.0
/**
 * Low-level eInk display and touch driver API for Raspberry Pi.
 *
 * <p>Provides interfaces and value types for driving WaveShare eInk HATs via Pi4J.
 * Hardware driver implementations are discovered at runtime via
 * {@link java.util.ServiceLoader}.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * try (var pi4j = Pi4J.newAutoContext()) {
 *     DisplayDriverFactory factory = ServiceLoader.load(DisplayDriverFactory.class)
 *         .findFirst().orElseThrow();
 *     DriverConfig config = DriverConfig.ofHardware(pi4j, jsonConfig);
 *     EInkDisplayDriver driver = factory.create(config);
 *     driver.initialize();
 *     byte[] pixels = ...; // 1-bit packed, MSB-first, row-major
 *     driver.writeFrame(new MonochromeFrame(pixels, RefreshMode.FULL)).get();
 * }
 * }</pre>
 *
 * <p>For JavaFX applications rendering to eInk panels, use the {@code pux4j-fx} module
 * rather than driving this API directly.
 */
package dev.pux4j.ui.core;
