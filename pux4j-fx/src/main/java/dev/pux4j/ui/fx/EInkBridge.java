// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.OrientationMapping;
import dev.pux4j.ui.core.PixelFormat;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.TouchCalibration;
import dev.pux4j.ui.core.TouchCoordinateMapper;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.transform.RenderOptions;
import dev.pux4j.ui.transform.RenderSession;
import dev.pux4j.ui.transform.TransformStrategy;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * JavaFX bridge entry point. Wires a stage-less JavaFX {@code Scene} to an
 * {@link EInkDisplayDriver} and (optionally) a {@link TouchDriver}, capturing the scene
 * on a timer and/or on {@code requestRefresh()}, pushing the result through
 * {@link RenderSession}, and translating touch input back into synthetic
 * {@code MouseEvent}s. See {@code notes/design/javafx-bridge.md} in the parent repo for
 * the full design.
 *
 * <p>Construction (this class, both {@link #fromConfig()} and {@link Builder#build()}) may
 * create hardware/emulator drivers and must not run on the JavaFX application thread — see
 * the "Threading" section of {@code notes/design/javafx-bridge.md} for the deadlock this
 * avoids ({@code pux4j-emulator}'s display driver factory does {@code Platform.runLater()}
 * followed by {@code future.get()} inside {@code create()}, which never completes if
 * {@code create()} itself runs on the FX thread). {@code Application.init()} is the intended
 * call site.
 *
 * <p>Scene capture, the display/touch threads, and {@code start()}/{@code stop()}/
 * {@code requestRefresh()} are implemented in a later phase (Phase 6.4 parts B/C); this
 * phase covers driver selection, configuration, and session construction only.
 */
public final class EInkBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EInkBridge.class);

    private static final long DEFAULT_CAPTURE_INTERVAL_MS = 200L;

    private final Pux4jContext context;
    private final EInkDisplayDriver display;
    private final TouchDriver touchDriver;
    private final TouchCoordinateMapper touchMapper;
    private final OrientationMapping orientationMapping;
    private final RenderSession session;
    private final long captureIntervalMs;

    private EInkBridge(
            Pux4jContext context,
            EInkDisplayDriver display,
            TouchDriver touchDriver,
            TouchCoordinateMapper touchMapper,
            OrientationMapping orientationMapping,
            RenderSession session,
            long captureIntervalMs) {
        this.context = context;
        this.display = display;
        this.touchDriver = touchDriver;
        this.touchMapper = touchMapper;
        this.orientationMapping = orientationMapping;
        this.session = session;
        this.captureIntervalMs = captureIntervalMs;
    }

    // -------------------------------------------------------------------------
    // Display geometry
    // -------------------------------------------------------------------------

    /**
     * Logical (on-screen) width — what the application should size its {@code Scene} to.
     */
    public int displayWidth() {
        return orientationMapping.logicalWidth();
    }

    /**
     * Logical (on-screen) height — what the application should size its {@code Scene} to.
     */
    public int displayHeight() {
        return orientationMapping.logicalHeight();
    }

    /**
     * Native framebuffer width, as addressed by the display controller. Diagnostics only —
     * applications lay out against {@link #displayWidth()}.
     */
    public int framebufferWidth() {
        return orientationMapping.framebufferWidth();
    }

    /**
     * Native framebuffer height, as addressed by the display controller. Diagnostics only —
     * applications lay out against {@link #displayHeight()}.
     */
    public int framebufferHeight() {
        return orientationMapping.framebufferHeight();
    }

    /**
     * Package-private — not part of the public API (kept minimal per AGENT.md); exists for
     * tests to observe whether a touch driver was wired without exposing the driver itself.
     */
    boolean hasTouch() {
        return touchDriver != null;
    }

    /**
     * Package-private — not part of the public API; exists for tests to pin the touch
     * mapper's geometry. Logical vs. framebuffer dimensions are an easy transposition to get
     * wrong when building the mapper, and {@link #hasTouch()} alone can't catch that.
     */
    TouchCoordinateMapper touchMapper() {
        return touchMapper;
    }

    // -------------------------------------------------------------------------
    // Construction — configuration-driven
    // -------------------------------------------------------------------------

    /**
     * Selects, creates, and initialises the display driver (and, if available, a touch
     * driver) from system properties — see the Configuration section of
     * {@code notes/design/javafx-bridge.md}. Must be called off the JavaFX application
     * thread (e.g. from {@code Application.init()}).
     *
     * <p>Recognised system properties: {@code pux4j.display.driver}, {@code pux4j.touch.driver},
     * {@code pux4j.display.orientation} (defaults to the selected display factory's
     * {@link DisplayDriverFactory#physicalOrientation()} when unset — see below),
     * {@code pux4j.refresh.maxPartials}, {@code pux4j.refresh.maxRegions},
     * {@code pux4j.refresh.fullRefreshThreshold}, {@code pux4j.transform.strategy},
     * {@code pux4j.transform.pixelFormat} (or {@code auto} for
     * {@link DisplayCapabilities#preferredFormat()}).
     *
     * <p>Orientation: every real driver reads a plain {@code "orientation"} string property
     * from {@link DriverConfig} at construction time (there is no post-construction setter),
     * and defaults it to {@code PORTRAIT} if absent — which does not generally match how the
     * panel is physically mounted. So unless {@code pux4j.display.orientation} is set
     * explicitly, this method threads the factory's own
     * {@link DisplayDriverFactory#physicalOrientation()} through instead, the same way
     * {@code DisplaySmokeTest}/{@code HardwareValidationTest} already do. Drivers with no
     * fixed physical mounting (e.g. the PNG driver) throw
     * {@link UnsupportedOperationException} from {@code physicalOrientation()}; that case
     * falls through to the driver's own default.
     *
     * @throws IllegalStateException if construction runs on the FX application thread, or if
     *         no display driver matches (an explicitly requested touch driver failing to
     *         match, or failing to initialise, also throws; an unrequested/auto-selected
     *         touch driver that fails for any reason is treated as display-only and logged,
     *         not thrown)
     * @throws IllegalArgumentException if {@code pux4j.transform.pixelFormat}/
     *         {@code pux4j.transform.strategy} names an unknown enum constant
     * @throws NumberFormatException if a numeric refresh-tuning property is malformed
     */
    public static EInkBridge fromConfig() {
        assertOffFxThread();

        String displayName = System.getProperty("pux4j.display.driver");
        String touchName = System.getProperty("pux4j.touch.driver");

        DisplayDriverFactory displayFactory = DisplayDriverFactory.select(displayName);
        LOG.info("Selected display driver: {}", displayFactory.name());

        DriverConfig.Builder configBuilder = DriverConfig.builder();
        String orientationProp = System.getProperty("pux4j.display.orientation");
        if (orientationProp != null) {
            configBuilder.property("orientation", orientationProp);
        } else {
            try {
                configBuilder.property("orientation", displayFactory.physicalOrientation().name());
            } catch (UnsupportedOperationException e) {
                // No fixed physical mounting (e.g. the PNG driver) — let its own DriverConfig
                // default apply.
            }
        }
        DriverConfig config = configBuilder.build();

        Pux4jContext context = Pux4jContext.managed();
        EInkDisplayDriver display = null;
        try {
            display = displayFactory.create(context, config);
            display.initialize();

            OrientationMapping orientationMapping =
                    OrientationMapping.of(display.getWidth(), display.getHeight(), display.getOrientation());
            LOG.info(
                    "Display geometry: logical {}x{}, framebuffer {}x{}, orientation {}",
                    orientationMapping.logicalWidth(), orientationMapping.logicalHeight(),
                    orientationMapping.framebufferWidth(), orientationMapping.framebufferHeight(),
                    display.getOrientation());

            TouchDriver touchDriver = null;
            TouchCoordinateMapper touchMapper = null;
            if (touchName != null) {
                TouchWiring wiring =
                        wireTouch(TouchDriverFactory.select(touchName), context, config, orientationMapping);
                touchDriver = wiring.driver();
                touchMapper = wiring.mapper();
            } else {
                try {
                    TouchWiring wiring =
                            wireTouch(TouchDriverFactory.select(null), context, config, orientationMapping);
                    touchDriver = wiring.driver();
                    touchMapper = wiring.mapper();
                } catch (RuntimeException e) {
                    LOG.info("No touch driver available; running display-only. ({})", e.getMessage());
                }
            }

            RenderOptions renderOptions = renderOptionsFromSystemProperties(display.getCapabilities());
            RenderSession session = RenderSession.create(display.getCapabilities(), orientationMapping, renderOptions);

            return new EInkBridge(
                    context, display, touchDriver, touchMapper, orientationMapping, session,
                    DEFAULT_CAPTURE_INTERVAL_MS);
        } catch (RuntimeException e) {
            closeQuietly(display, e);
            context.close();
            throw e;
        }
    }

    /** Result of {@link #wireTouch}: a created, initialised touch driver plus its mapper. */
    private record TouchWiring(TouchDriver driver, TouchCoordinateMapper mapper) {}

    /**
     * Creates and initialises the touch driver from the given (already-selected) factory,
     * plus a matching {@link TouchCoordinateMapper} built from the factory's
     * {@link TouchDriverFactory#touchCalibration(int, int)}. Pulled out of
     * {@link #fromConfig()} so its explicit-name and auto-select branches — which differ only
     * in how they handle failure, not in what they do on success — share one body instead of
     * drifting apart.
     */
    private static TouchWiring wireTouch(
            TouchDriverFactory factory, Pux4jContext context, DriverConfig config, OrientationMapping mapping) {
        LOG.info("Selected touch driver: {}", factory.name());
        TouchDriver driver = factory.create(context, config);
        driver.initialize();
        TouchCalibration calibration = factory.touchCalibration(mapping.logicalWidth(), mapping.logicalHeight());
        TouchCoordinateMapper mapper =
                new TouchCoordinateMapper(mapping.logicalWidth(), mapping.logicalHeight(), calibration);
        return new TouchWiring(driver, mapper);
    }

    /**
     * Releases a partially-constructed display driver on a {@code fromConfig()} failure path
     * (e.g. the driver itself initialised fine but touch selection then threw). Best-effort:
     * exceptions from {@code sleep()}/{@code close()} are attached as suppressed rather than
     * replacing the original failure, since the original is the one the caller needs to see.
     */
    private static void closeQuietly(EInkDisplayDriver display, RuntimeException primary) {
        if (display == null) {
            return;
        }
        try {
            display.sleep();
        } catch (RuntimeException suppressed) {
            primary.addSuppressed(suppressed);
        }
        if (display instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception suppressed) {
                primary.addSuppressed(suppressed);
            }
        }
    }

    private static RenderOptions renderOptionsFromSystemProperties(DisplayCapabilities caps) {
        String pixelFormatProp = System.getProperty("pux4j.transform.pixelFormat");
        PixelFormat pixelFormat = (pixelFormatProp == null || "auto".equalsIgnoreCase(pixelFormatProp))
                ? caps.preferredFormat()
                : PixelFormat.valueOf(pixelFormatProp);

        RenderOptions.Builder builder = pixelFormatDefaults(pixelFormat);

        String strategyProp = System.getProperty("pux4j.transform.strategy");
        if (strategyProp != null) {
            builder.strategy(TransformStrategy.valueOf(strategyProp));
        }

        String maxPartialsProp = System.getProperty("pux4j.refresh.maxPartials");
        if (maxPartialsProp != null) {
            builder.maxPartials(Integer.parseInt(maxPartialsProp));
        }
        String maxRegionsProp = System.getProperty("pux4j.refresh.maxRegions");
        if (maxRegionsProp != null) {
            builder.maxRegions(Integer.parseInt(maxRegionsProp));
        }
        String fullRefreshThresholdProp = System.getProperty("pux4j.refresh.fullRefreshThreshold");
        if (fullRefreshThresholdProp != null) {
            builder.fullRefreshThreshold(Float.parseFloat(fullRefreshThresholdProp));
        }

        return builder.build();
    }

    /**
     * Starts a {@link RenderOptions.Builder} for {@code pixelFormat}, applying
     * {@link TransformStrategy#FOUR_GRAY_BINNING} instead of the builder's own default
     * ({@link TransformStrategy#THRESHOLD}) when the format is {@link PixelFormat#FOUR_GRAY} —
     * {@code THRESHOLD} is a MONOCHROME-only strategy, rejected by
     * {@code RenderSession}/{@code PixelTransforms} for any other format, so this is a
     * required correction, not an opinionated default. Shared by both construction paths
     * ({@link #renderOptionsFromSystemProperties} and {@link Builder#defaultRenderOptions})
     * so the two can't drift apart.
     */
    private static RenderOptions.Builder pixelFormatDefaults(PixelFormat pixelFormat) {
        RenderOptions.Builder builder = RenderOptions.builder().pixelFormat(pixelFormat);
        if (pixelFormat == PixelFormat.FOUR_GRAY) {
            builder.strategy(TransformStrategy.FOUR_GRAY_BINNING);
        }
        return builder;
    }

    // -------------------------------------------------------------------------
    // Construction — explicit wiring
    // -------------------------------------------------------------------------

    /**
     * Returns a new {@link Builder} for explicit driver wiring — for applications or tests
     * that construct drivers themselves rather than going through {@link #fromConfig()}'s
     * system-property selection.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for explicit {@link EInkBridge} wiring. {@link #build()} must not run on the
     * JavaFX application thread — see {@link EInkBridge#fromConfig()}.
     */
    public static final class Builder {

        private EInkDisplayDriver display;
        private TouchDriver touchDriver;
        private TouchCalibration touchCalibration;
        private RenderOptions renderOptions;
        private long captureIntervalMs = DEFAULT_CAPTURE_INTERVAL_MS;

        private Builder() {}

        /**
         * Sets the display driver. Required. Must already be initialised — this builder does
         * not call {@link EInkDisplayDriver#initialize()}.
         */
        public Builder display(EInkDisplayDriver display) {
            this.display = Objects.requireNonNull(display, "display");
            return this;
        }

        /**
         * Wires a touch driver whose raw readings are already in display logical space —
         * e.g. the emulator or a programmatic driver — and assumes identity calibration.
         * Named deliberately unlike the overload below: passing a hardware touch driver
         * here (one whose raw readings are in touch-IC-native space) would silently produce
         * wrong touch mapping with no error, since nothing here can tell the two kinds of
         * driver apart. Use {@link #touch(TouchDriver, TouchCalibration)} for those. Must
         * already be initialised — this builder does not call
         * {@link TouchDriver#initialize()}.
         */
        public Builder touchInLogicalSpace(TouchDriver touchDriver) {
            this.touchDriver = Objects.requireNonNull(touchDriver, "touchDriver");
            this.touchCalibration = null;
            return this;
        }

        /**
         * Wires a touch driver together with its physical calibration relative to the
         * display — see {@link TouchCalibration}. Must already be initialised — this builder
         * does not call {@link TouchDriver#initialize()}.
         */
        public Builder touch(TouchDriver touchDriver, TouchCalibration touchCalibration) {
            this.touchDriver = Objects.requireNonNull(touchDriver, "touchDriver");
            this.touchCalibration = Objects.requireNonNull(touchCalibration, "touchCalibration");
            return this;
        }

        /**
         * Sets the render/refresh tuning options. Defaults to the display's
         * {@link DisplayCapabilities#preferredFormat()} with {@link TransformStrategy#THRESHOLD}
         * ({@link TransformStrategy#FOUR_GRAY_BINNING} if the preferred format is
         * {@link PixelFormat#FOUR_GRAY}) when not set.
         */
        public Builder renderOptions(RenderOptions renderOptions) {
            this.renderOptions = Objects.requireNonNull(renderOptions, "renderOptions");
            return this;
        }

        /**
         * Sets the timer-driven capture interval in milliseconds (default 200). {@code 0}
         * disables timer-driven capture; the application must drive all refreshes via
         * {@code requestRefresh()}.
         */
        public Builder captureIntervalMs(long captureIntervalMs) {
            if (captureIntervalMs < 0) {
                throw new IllegalArgumentException(
                        "captureIntervalMs must be >= 0, got " + captureIntervalMs);
            }
            this.captureIntervalMs = captureIntervalMs;
            return this;
        }

        /**
         * Builds the bridge. Must not run on the JavaFX application thread.
         *
         * @throws IllegalStateException if called on the FX application thread, or if
         *         {@link #display(EInkDisplayDriver)} was not set
         */
        public EInkBridge build() {
            assertOffFxThread();
            if (display == null) {
                throw new IllegalStateException("display(...) is required");
            }

            OrientationMapping orientationMapping =
                    OrientationMapping.of(display.getWidth(), display.getHeight(), display.getOrientation());

            TouchCoordinateMapper touchMapper = null;
            if (touchDriver != null) {
                TouchCalibration calibration = touchCalibration != null
                        ? touchCalibration
                        : new TouchCalibration(
                                orientationMapping.logicalWidth(), orientationMapping.logicalHeight(),
                                false, false, false);
                touchMapper = new TouchCoordinateMapper(
                        orientationMapping.logicalWidth(), orientationMapping.logicalHeight(), calibration);
            }

            RenderOptions options = renderOptions != null
                    ? renderOptions
                    : defaultRenderOptions(display.getCapabilities());
            RenderSession session = RenderSession.create(display.getCapabilities(), orientationMapping, options);

            return new EInkBridge(
                    null, display, touchDriver, touchMapper, orientationMapping, session, captureIntervalMs);
        }

        private static RenderOptions defaultRenderOptions(DisplayCapabilities caps) {
            return pixelFormatDefaults(caps.preferredFormat()).build();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle and refresh — implemented in a later phase
    // -------------------------------------------------------------------------

    /**
     * Starts the display and touch threads and the timer-driven capture, against the given
     * (already correctly sized — see {@link #displayWidth()}/{@link #displayHeight()})
     * stage-less {@code Scene}.
     */
    public void start(Scene scene) {
        throw new UnsupportedOperationException("EInkBridge.start() — Phase 6.4 part B");
    }

    /**
     * Stops the display and touch threads and releases owned resources (e.g. a managed
     * {@link Pux4jContext} created by {@link #fromConfig()}).
     */
    public void stop() {
        throw new UnsupportedOperationException("EInkBridge.stop() — Phase 6.4 part B");
    }

    /**
     * Triggers an immediate capture and pipeline run, bypassing the timer interval (but not
     * the in-flight-write backpressure), writing only if the frame actually changed.
     */
    public void requestRefresh() {
        throw new UnsupportedOperationException("EInkBridge.requestRefresh() — Phase 6.4 part C");
    }

    /**
     * As {@link #requestRefresh()}, but forces a write regardless of whether the frame
     * changed — via {@link RenderSession#reset()} before the capture, which always produces a
     * {@code FULL} refresh (there is no "force a specific mode" concept in {@code
     * RenderSession} today — forcing {@code FAST}/{@code PARTIAL} would need new API there,
     * not just a parameter here, so this method takes none rather than accept a
     * {@code RefreshMode} that every value but {@code FULL} would silently misrepresent).
     */
    public void forceRefresh() {
        throw new UnsupportedOperationException("EInkBridge.forceRefresh() — Phase 6.4 part C");
    }

    // -------------------------------------------------------------------------

    private static void assertOffFxThread() {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "EInkBridge construction must not run on the JavaFX application thread: "
                    + "creating a driver here (e.g. the emulator's Platform.runLater()+future.get()) "
                    + "would deadlock. Call fromConfig()/builder().build() from Application.init(), "
                    + "not from start().");
        }
    }
}
