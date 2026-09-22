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
import dev.pux4j.ui.core.TouchPoint;
import dev.pux4j.ui.transform.RenderOptions;
import dev.pux4j.ui.transform.RenderResult;
import dev.pux4j.ui.transform.RenderSession;
import dev.pux4j.ui.transform.TransformStrategy;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>{@link #start(Scene)}/{@link #stop()} and the display/touch virtual threads are
 * implemented in this phase (Phase 6.4 part B). Scene capture (the {@code AnimationTimer}
 * that actually produces frames) and {@code requestRefresh()}/{@code forceRefresh()} remain
 * Phase 6.4 part C — until then, nothing calls {@link #submitRenderWork(MemorySegment)}, so
 * the display thread runs but stays idle, and only touch injection is externally observable.
 */
public final class EInkBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EInkBridge.class);

    private static final long DEFAULT_CAPTURE_INTERVAL_MS = 200L;

    /** How long the touch thread sleeps between polls when a read comes back empty. */
    private static final long TOUCH_POLL_IDLE_MS = 10L;

    /**
     * Maximum scene-coordinate distance, in pixels, between a press and its matching release
     * for the pair to still synthesize a {@code MOUSE_CLICKED} — "within a few pixels of the
     * press", per the design doc's Event injection section.
     */
    private static final double CLICK_TOLERANCE_PX = 4.0;

    private final Pux4jContext context;
    private final EInkDisplayDriver display;
    private final TouchDriver touchDriver;
    private final TouchCoordinateMapper touchMapper;
    private final OrientationMapping orientationMapping;
    private final RenderSession session;
    private final long captureIntervalMs;

    /** "Latest wins" hand-off from the FX thread to the display thread — capacity 1. */
    private final BlockingQueue<RenderWork> renderQueue = new ArrayBlockingQueue<>(1);

    /**
     * True while the display thread is actually writing to the driver (between submitting
     * writeFrame()/writeRegion() and its future completing) — the backpressure signal Phase C's
     * AnimationTimer reads before snapshotting again. Deliberately narrower than "queue
     * non-empty": an unchanged frame (session.prepare() returns empty) never sets this.
     */
    private final AtomicBoolean writeInFlight = new AtomicBoolean(false);

    /**
     * Per-contact touch state (pressed node, press position, last known position). Read and
     * written only from inside {@link #injectTouches} and the methods it calls, which always
     * run on the FX thread (reached via {@code Platform.runLater()}) — the touch thread itself
     * never touches this map, so it needs no synchronization on that account. {@link #start}
     * clears it before the threads start (safe: nothing is running yet); {@link #stop}
     * deliberately does <em>not</em> touch it directly — see {@link #stop}'s javadoc on why
     * only {@code scene} is cleared there.
     */
    private final Map<Integer, ContactState> activeContacts = new HashMap<>();

    /**
     * Mirrors {@code !activeContacts.isEmpty()}, but readable from the touch thread (which
     * cannot safely read {@link #activeContacts} itself) — lets {@link #runTouchLoop} decide
     * whether an empty poll still needs dispatching to the FX thread, for contacts that lift
     * by disappearing from the report rather than by an explicit {@code down=false} point
     * (see {@link #injectTouches}'s javadoc).
     */
    private final AtomicBoolean hasActiveContacts = new AtomicBoolean(false);

    private volatile Scene scene;
    private volatile Thread displayThread;
    private volatile Thread touchThread;
    private volatile boolean stopped;

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
    // Lifecycle
    // -------------------------------------------------------------------------

    /** How long {@link #stop()} waits for the display thread to drain the poison pill (i.e.
     * finish whatever write was already in flight) before falling back to interrupting it. */
    private static final long DISPLAY_DRAIN_TIMEOUT_MS = 5_000L;

    /**
     * Starts the display and touch virtual threads against the given (already correctly
     * sized — see {@link #displayWidth()}/{@link #displayHeight()}) stage-less {@code Scene}.
     * The touch thread is only started if a touch driver was wired. Timer-driven capture is
     * Phase 6.4 part C — until then the display thread runs but has nothing to consume unless
     * a test calls {@link #submitRenderWork(MemorySegment)} directly.
     *
     * <p>Not safe to call concurrently with itself or {@link #stop()} from another thread —
     * the bridge's lifecycle is expected to be driven from one place (typically
     * {@code Application.start()}/{@code Application.stop()}), not raced.
     *
     * @throws IllegalStateException if already started, or if called after {@link #stop()}
     *         (restarting a stopped bridge would run threads against a driver that has
     *         already been put to sleep/closed)
     */
    public void start(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        if (stopped) {
            throw new IllegalStateException("EInkBridge already stopped — not restartable");
        }
        if (displayThread != null) {
            throw new IllegalStateException("EInkBridge already started");
        }
        this.scene = scene;
        activeContacts.clear();
        hasActiveContacts.set(false);

        displayThread = Thread.ofVirtual().name("eink-display").start(this::runDisplayLoop);
        if (touchDriver != null) {
            touchThread = Thread.ofVirtual().name("eink-touch").start(this::runTouchLoop);
        }
        LOG.info("EInkBridge started (touch {})", touchDriver != null ? "enabled" : "disabled");
    }

    /**
     * Stops the display and touch threads and releases the display driver and, for a
     * {@link #fromConfig()}-built bridge, its managed {@link Pux4jContext}. Idempotent (a
     * second call is a no-op) and safe to call even if {@link #start(Scene)} was never
     * called — {@code fromConfig()} already creates and initialises the driver/context, so
     * those still need releasing regardless of whether the threads ever started. Best-effort
     * on the way out: a failure releasing one resource doesn't stop the others from being
     * attempted, and is logged rather than thrown (the caller is already shutting down; a
     * {@code stop()} that itself throws would leave the shutdown sequence unclear about what
     * did and didn't happen).
     *
     * <p>The display thread is never interrupted mid-write: {@link EInkDisplayDriver}'s
     * contract requires writes to be serialised, and abandoning one to send {@code sleep()}/
     * {@code close()} commands on the same bus would interleave hardware I/O. Instead a
     * poison-pill work item displaces anything queued (same "latest wins" replace {@link
     * #submitRenderWork} already uses) and the thread is joined with a bounded timeout,
     * falling back to interrupt only if a write appears to be stuck.
     */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;

        if (touchThread != null) {
            touchThread.interrupt();
            joinQuietly(touchThread, 0);
            touchThread = null;
        }
        if (displayThread != null) {
            offerReplacing(POISON_PILL);
            joinQuietly(displayThread, DISPLAY_DRAIN_TIMEOUT_MS);
            if (displayThread.isAlive()) {
                LOG.warn("Display thread did not drain within {} ms; interrupting", DISPLAY_DRAIN_TIMEOUT_MS);
                displayThread.interrupt();
                joinQuietly(displayThread, 0);
            }
            displayThread = null;
        }
        scene = null;

        try {
            display.sleep();
        } catch (RuntimeException e) {
            LOG.warn("display.sleep() failed during stop()", e);
        }
        if (display instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("display.close() failed during stop()", e);
            }
        }
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException e) {
                LOG.warn("context.close() failed during stop()", e);
            }
        }
        LOG.info("EInkBridge stopped");
    }

    /** {@code timeoutMs == 0} means join indefinitely — see {@link Thread#join(long)}. */
    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Display thread
    // -------------------------------------------------------------------------

    /**
     * One captured frame handed from the FX thread to the display thread. Package-private
     * (not {@code private}) so tests can construct one directly and drive
     * {@link #processRenderWork} without going through a real scene snapshot — snapshotting
     * needs a live {@code Scene}, which (see {@link #injectTouches}'s javadoc) needs the
     * JavaFX toolkit started, which this codebase deliberately doesn't require of its test
     * suite (Q7/Q8 in the design doc — no headless Glass platform until JavaFX 26).
     */
    record RenderWork(MemorySegment bgra) {}

    /**
     * Sentinel {@link RenderWork} used only to unblock {@link #runDisplayLoop}'s
     * {@code renderQueue.take()} during {@link #stop()} — checked by reference identity, never
     * passed to {@link #processRenderWork} (its {@code null} {@code bgra} would NPE there).
     */
    private static final RenderWork POISON_PILL = new RenderWork(null);

    /**
     * "Latest wins" replace: drops whatever is currently queued (if anything) and queues
     * {@code work} in its place — capacity-1 queue, {@code poll()} then {@code offer()}. Safe
     * with exactly one producer (the caller), which both {@link #submitRenderWork} and
     * {@link #stop}'s poison-pill hand-off are.
     *
     * @return the displaced item, or {@code null} if the queue was empty
     */
    private RenderWork offerReplacing(RenderWork work) {
        RenderWork displaced = renderQueue.poll();
        renderQueue.offer(work);
        return displaced;
    }

    /**
     * Enqueues a captured frame for the display thread — "latest wins", see
     * {@link #offerReplacing}. Package-private: the intended caller is Phase 6.4 part C's
     * {@code AnimationTimer}/{@code requestRefresh()} machinery; exists now so the display
     * thread's consume/prepare/write logic can be exercised directly, without needing a real
     * scene snapshot.
     *
     * @return the previously-queued item this one replaced, or {@code null} if none — a caller
     *         managing a pixel-buffer free list (Phase 6.4 part C) needs this to recycle that
     *         buffer, since a displaced item is otherwise discarded and never comes back.
     *         Note the other half of that problem isn't solved here: {@link #processRenderWork}
     *         doesn't hand its own buffer back after use either, since nothing calls this
     *         method in a way that needs it yet — Phase C will need its own mechanism for that
     *         (e.g. a callback or a returned-buffer queue), not guessed at in advance here.
     */
    RenderWork submitRenderWork(MemorySegment bgra) {
        return offerReplacing(new RenderWork(bgra));
    }

    /**
     * Package-private — lets a test observe the "latest wins" queue policy directly (e.g.
     * that a second {@link #submitRenderWork} before the first is taken replaces it) without
     * needing the display thread running.
     */
    RenderWork peekRenderWork() {
        return renderQueue.peek();
    }

    /**
     * Package-private — the backpressure signal Phase 6.4 part C's {@code AnimationTimer}
     * will read before snapshotting again.
     */
    boolean isWriteInFlight() {
        return writeInFlight.get();
    }

    /**
     * Drains {@link #renderQueue} one item at a time via {@link #processRenderWork} until
     * {@link #POISON_PILL} is taken (normal shutdown, from {@link #stop()}) or the thread is
     * interrupted (the timed-out fallback {@link #stop()} uses if a write appears stuck). A
     * {@link RuntimeException} escaping {@link #processRenderWork} — e.g. a driver rejecting a
     * frame/region synchronously — is logged and the loop continues; it must never silently
     * end the thread; see that method's own catch clauses for why this can still happen despite
     * them.
     */
    private void runDisplayLoop() {
        LOG.info("Display thread started");
        try {
            while (true) {
                RenderWork work = renderQueue.take();
                if (work == POISON_PILL) {
                    break;
                }
                try {
                    processRenderWork(work);
                } catch (RuntimeException e) {
                    LOG.error("Unexpected error processing render work; continuing", e);
                    writeInFlight.set(false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Display thread exiting");
    }

    /**
     * Transforms, diffs, and (if changed) writes one captured frame. Package-private, not
     * {@code private} — see {@link RenderWork}'s javadoc for why tests call this directly
     * instead of going through {@link #start(Scene)}'s real display thread.
     */
    void processRenderWork(RenderWork work) {
        Optional<RenderResult> result;
        try {
            result = session.prepare(work.bgra());
        } catch (RuntimeException e) {
            LOG.error("RenderSession.prepare() failed; dropping frame", e);
            return;
        }
        if (result.isEmpty()) {
            return;
        }
        RenderResult r = result.get();
        writeInFlight.set(true);
        try {
            // A driver may reject an out-of-range region or an unsupported FrameData/
            // RefreshMode combination synchronously, before ever returning a future (e.g.
            // Ssd1680DisplayDriver.writeRegion's coordinate validation) — that's a
            // RuntimeException escaping this try, not something ExecutionException/
            // InterruptedException below would catch, so it needs its own clause: one bad
            // frame must not end the display thread for every frame after it.
            CompletableFuture<Void> future = r.decision().region()
                    .map(region -> display.writeRegion(
                            region.x(), region.y(), region.width(), region.height(), r.frame()))
                    .orElseGet(() -> display.writeFrame(r.frame()));
            // Waits on this (display) thread, not the FX thread — per EInkDisplayDriver's
            // threading contract, this is exactly what serialises writes one at a time.
            future.get();
        } catch (ExecutionException e) {
            LOG.error("Display write failed", e.getCause());
        } catch (InterruptedException e) {
            // Only reachable via stop()'s timed-out fallback interrupt (the normal shutdown
            // path drains via the poison pill instead, precisely to avoid abandoning a write
            // mid-flight) — the driver's own write (on its internal virtual thread, per
            // EInkDisplayDriver's contract) is not cancelled and completes on its own.
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.error("Display write rejected", e);
        } finally {
            writeInFlight.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Touch thread
    // -------------------------------------------------------------------------

    /**
     * A contact's dispatch state: the node it was pressed on (or {@code null} if the press
     * hit nothing), its press position (for {@link #CLICK_TOLERANCE_PX} on release), and its
     * last known position (to detect movement between polls, for {@code MOUSE_DRAGGED}).
     */
    private record ContactState(Node pressedNode, double pressX, double pressY, double lastX, double lastY) {}

    /**
     * Polls {@link TouchDriver#readTouches()} and dispatches each batch to the FX thread. An
     * empty poll is normally just an idle-sleep — except when {@link #hasActiveContacts} says
     * a contact is still tracked as down, in which case the empty poll is dispatched anyway so
     * {@link #injectTouches} can synthesize its release (see that method's javadoc for why a
     * lift doesn't always arrive as an explicit {@code down=false} point). A {@link
     * RuntimeException} from {@code readTouches()} (e.g. a transient I2C fault) is logged and
     * retried after the idle interval, rather than ending the thread — touch going dead for
     * the rest of the process on one bad read would be a much worse failure mode than a
     * dropped poll.
     */
    private void runTouchLoop() {
        LOG.info("Touch thread started");
        try {
            while (true) {
                try {
                    List<TouchPoint> raw = touchDriver.readTouches();
                    if (raw.isEmpty()) {
                        if (hasActiveContacts.get()) {
                            Platform.runLater(() -> injectTouches(List.of()));
                        }
                        Thread.sleep(TOUCH_POLL_IDLE_MS);
                        continue;
                    }
                    List<TouchPoint> mapped = raw.stream().map(touchMapper::map).toList();
                    Platform.runLater(() -> injectTouches(mapped));
                } catch (RuntimeException e) {
                    LOG.error("Touch poll failed; retrying", e);
                    Thread.sleep(TOUCH_POLL_IDLE_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Touch thread exiting");
    }

    /**
     * Dispatches one poll's worth of already-logical-space contacts as synthetic mouse
     * events. Always runs on the FX thread (only ever invoked via {@code Platform.runLater()}
     * from {@link #runTouchLoop}), so {@link #activeContacts} needs no synchronization.
     *
     * <p>Not every touch IC reports a lift as an explicit {@code down=false} point — the
     * GT1151Q (2.13" HAT) signals it purely by the contact disappearing from the next report,
     * while the ICNT86X (2.9" HAT) does send an explicit {@code down=false}. Both must work:
     * after dispatching everything {@code contacts} reports, any id still in
     * {@link #activeContacts} that wasn't in this batch is inferred to have lifted, and its
     * release is synthesized at its last known position. Without this, a GT1151Q press would
     * never see a {@code MOUSE_RELEASED}/{@code MOUSE_CLICKED} at all, and the stale
     * {@link ContactState} would misdispatch the next unrelated tap sharing that contact id as
     * a drag of the first press's node.
     */
    private void injectTouches(List<TouchPoint> contacts) {
        Scene currentScene = scene;
        if (currentScene == null) {
            return; // stopped between the poll and this runLater turn running
        }
        Set<Integer> reportedIds = new HashSet<>();
        for (TouchPoint p : contacts) {
            reportedIds.add(p.id());
            if (p.down()) {
                dispatchDownContact(currentScene, p);
            } else {
                dispatchUpContact(currentScene, p);
            }
        }
        if (!activeContacts.isEmpty()) {
            for (Integer id : List.copyOf(activeContacts.keySet())) {
                if (!reportedIds.contains(id)) {
                    ContactState state = activeContacts.remove(id);
                    releaseContact(currentScene, state, state.lastX(), state.lastY());
                }
            }
        }
        hasActiveContacts.set(!activeContacts.isEmpty());
    }

    /** New contact → {@code MOUSE_PRESSED} at the picked node (if any). Existing contact that
     * moved since the last poll → {@code MOUSE_DRAGGED} at its pressed node (if any); a
     * contact that hasn't moved is left alone (no event). */
    private void dispatchDownContact(Scene currentScene, TouchPoint p) {
        ContactState state = activeContacts.get(p.id());
        if (state == null) {
            Node target = ScenePicker.pick(currentScene, p.x(), p.y());
            if (target != null) {
                fireMouseEvent(target, MouseEvent.MOUSE_PRESSED, p.x(), p.y());
            }
            activeContacts.put(p.id(), new ContactState(target, p.x(), p.y(), p.x(), p.y()));
        } else if (p.x() != state.lastX() || p.y() != state.lastY()) {
            if (state.pressedNode() != null) {
                fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_DRAGGED, p.x(), p.y());
            }
            activeContacts.put(p.id(),
                    new ContactState(state.pressedNode(), state.pressX(), state.pressY(), p.x(), p.y()));
        }
    }

    /** Explicit {@code down=false} point (ICNT86X-style lift) — releases at the reported
     * position. See {@link #injectTouches}'s javadoc for the other (GT1151Q-style) lift path. */
    private void dispatchUpContact(Scene currentScene, TouchPoint p) {
        ContactState state = activeContacts.remove(p.id());
        if (state != null) {
            releaseContact(currentScene, state, p.x(), p.y());
        }
    }

    /**
     * {@code MOUSE_RELEASED} at {@code state}'s pressed node, plus {@code MOUSE_CLICKED} if
     * the release lands back on that same node within {@link #CLICK_TOLERANCE_PX} of the
     * original press position (not the last drag position — a slow drag back to the start
     * shouldn't register as a click). Shared by both lift paths in {@link #dispatchUpContact}
     * and {@link #injectTouches}'s inferred-release handling, which differ only in where the
     * release position comes from (an explicit point vs. the contact's last known position).
     */
    private void releaseContact(Scene currentScene, ContactState state, double releaseX, double releaseY) {
        if (state.pressedNode() == null) {
            return;
        }
        fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_RELEASED, releaseX, releaseY);

        double distanceFromPress = Math.hypot(releaseX - state.pressX(), releaseY - state.pressY());
        Node releaseTarget = ScenePicker.pick(currentScene, releaseX, releaseY);
        if (releaseTarget == state.pressedNode() && distanceFromPress <= CLICK_TOLERANCE_PX) {
            fireMouseEvent(state.pressedNode(), MouseEvent.MOUSE_CLICKED, releaseX, releaseY);
        }
    }

    /**
     * Fires a synthetic mouse event at {@code target} via manual pick + {@code
     * Event.fireEvent} (JavaFX exposes no public pick API — see {@link ScenePicker}), giving
     * correct capture/bubble dispatch along the node's parent chain. {@code synthesized} is
     * {@code true} (this really is synthesized from touch input — code that specifically
     * branches on {@link MouseEvent#isSynthesized()} will see that). Known, documented
     * limitations of this synthetic path that remain (see the design doc's Event injection
     * section): no {@code :hover} pseudo-class, no cursor changes, {@code stillSincePress} is
     * always reported {@code true} rather than tracked precisely, and — since the bridge runs
     * stage-less — screen coordinates are approximated as scene coordinates.
     */
    private static void fireMouseEvent(Node target, EventType<MouseEvent> type, double x, double y) {
        boolean primaryDown = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        MouseEvent event = new MouseEvent(
                type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                primaryDown, false, false,
                true, false, true,
                new PickResult(target, x, y));
        Event.fireEvent(target, event);
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
