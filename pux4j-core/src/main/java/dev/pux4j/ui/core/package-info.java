// SPDX-License-Identifier: Apache-2.0
/**
 * Low-level eInk display and touch driver API for Raspberry Pi.
 *
 * <p>Provides interfaces and value types for driving WaveShare eInk HATs via Pi4J.
 * Hardware driver implementations are discovered at runtime via
 * {@link java.util.ServiceLoader}.
 *
 * <p>For JavaFX applications rendering to eInk panels, use the {@code pux4j-fx} module
 * rather than driving this API directly.
 */
package dev.pux4j.ui.core;
