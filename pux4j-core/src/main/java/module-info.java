// SPDX-License-Identifier: Apache-2.0
/**
 * Core module for low-level eInk display and touch driver APIs.
 *
 * <p>This module defines the public interfaces and value types used by
 * display and touch drivers, plus runtime SPI contracts discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Most applications should start with package
 * {@link dev.pux4j.ui.core}, then use {@link dev.pux4j.ui.core.Pux4jContext}
 * and {@link dev.pux4j.ui.core.DisplayDriverFactory} to create a concrete
 * driver implementation.
 *
 * <h2>Quick start: display write</h2>
 * <pre>{@code
 * dev.pux4j.ui.core.DriverConfig config = dev.pux4j.ui.core.DriverConfig.builder()
 *     .strProperty("orientation", "LANDSCAPE")
 *     .intProperty("dcPin", 25)
 *     .build();
 *
 * try (dev.pux4j.ui.core.Pux4jContext context = dev.pux4j.ui.core.Pux4jContext.managed()) {
 *     dev.pux4j.ui.core.DisplayDriverFactory factory =
 *         java.util.ServiceLoader.load(dev.pux4j.ui.core.DisplayDriverFactory.class)
 *             .findFirst().orElseThrow();
 *
 *     dev.pux4j.ui.core.EInkDisplayDriver driver = factory.create(context, config);
 *     driver.initialize();
 *
 *     byte[] pixels = ...; // 1-bit packed, MSB-first, row-major
 *     driver.writeFrame(new dev.pux4j.ui.core.MonochromeFrame(
 *         pixels,
 *         dev.pux4j.ui.core.RefreshMode.FULL
 *     )).get();
 * }
 * }</pre>
 *
 * <h2>Quick start: touch polling</h2>
 * <pre>{@code
 * dev.pux4j.ui.core.DriverConfig config = dev.pux4j.ui.core.DriverConfig.builder()
 *     .intProperty("touchInterruptPin", 17)
 *     .build();
 *
 * try (dev.pux4j.ui.core.Pux4jContext context = dev.pux4j.ui.core.Pux4jContext.managed()) {
 *     dev.pux4j.ui.core.TouchDriverFactory factory =
 *         java.util.ServiceLoader.load(dev.pux4j.ui.core.TouchDriverFactory.class)
 *             .findFirst().orElseThrow();
 *
 *     dev.pux4j.ui.core.TouchDriver touch = factory.create(context, config);
 *     touch.initialize();
 *
 *     java.util.List<dev.pux4j.ui.core.TouchPoint> points = touch.readPoints();
 *     if (!points.isEmpty()) {
 *         dev.pux4j.ui.core.TouchPoint p = points.getFirst();
 *         // Consume mapped touch coordinates from p.x() / p.y().
 *     }
 * }
 * }</pre>
 */
module dev.pux4j.ui.core {
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    exports dev.pux4j.ui.core;

    uses dev.pux4j.ui.core.DisplayDriverFactory;
    uses dev.pux4j.ui.core.TouchDriverFactory;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.core.internal.PngDisplayDriverFactory;
}
