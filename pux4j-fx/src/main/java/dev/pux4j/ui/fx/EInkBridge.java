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
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.paint.Color;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
 * <p>{@link #start(Scene)}/{@link #stop()}, the display/touch virtual threads (Phase 6.4 part
 * B), and timer/application-driven scene capture with a three-buffer free list (Phase 6.4 part
 * C — {@link #requestRefresh()}, {@link #forceRefresh()}) are all implemented. See the Scene
 * Capture and Threading sections of the design doc, and the 2026-09-22 (design) and 2026-09-24
 * (implementation) Opus reviews of this phase (findings C1-C12 and I1-I9) for the reasoning
 * behind the free-list recycling, the {@code resetRequestedSeq}/{@code refreshPending} latches,
 * and the FX-thread assertion in {@link #start(Scene)}.
 */
public final class EInkBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EInkBridge.class);

    private static final long DEFAULT_CAPTURE_INTERVAL_MS = 200L;

    /**
     * Pixel buffers cycled between the FX and display threads: one being written, one queued,
     * and one spare so a capture taken while both are occupied can still displace the stale
     * queued frame ("latest wins"). With only two, that capture finds no buffer and is skipped,
     * so the stale frame is written anyway — an extra e-ink refresh cycle (CP3 finding 3).
     */
    private static final int CAPTURE_BUFFER_COUNT = 3;

    /** How long the touch thread sleeps after every poll, empty or not (see {@link #runTouchLoop}). */
    private static final long TOUCH_POLL_IDLE_MS = 10L;

    /** How long {@link #stop()} waits for the touch thread to exit after interrupting it. */
    private static final long TOUCH_JOIN_TIMEOUT_MS = 2_000L;

    /**
     * One dispatch to the FX thread may be outstanding at a time — the touch thread acquires
     * this before each {@code Platform.runLater} and the FX-thread task releases it. Without
     * it, a held touch (non-empty polls, no sleep) floods the FX queue faster than a busy FX
     * thread (e.g. mid-{@code snapshot}) can drain it (CP3 finding 1).
     */
    private final Semaphore touchDispatchPermit = new Semaphore(1);

    /** Where touch hand-offs are posted. {@code Platform::runLater}; replaceable by tests. */
    private volatile Consumer<Runnable> fxExecutor = Platform::runLater;

    /** Package-private test seam — see {@link #fxExecutor}. */
    void setFxExecutorForTest(Consumer<Runnable> executor) {
        this.fxExecutor = executor;
    }

    /** Package-private test seam — removes one buffer from the free list, as a capture would. */
    CaptureBuffer pollFreeBufferForTest() {
        return freeBuffers.poll();
    }

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
     * writeFrame()/writeRegion() and its future completing). Diagnostic/test hook only — per
     * the 2026-09-22 Opus review (finding C3), this is deliberately NOT used as capture
     * backpressure: it is set only after {@code session.prepare()}'s packing/diff work has
     * already run, so {@code !writeInFlight} is not "display thread idle". {@link #freeBuffers}
     * being non-empty is the actual backpressure signal — see {@link #maybeCapture}.
     */
    private final AtomicBoolean writeInFlight = new AtomicBoolean(false);

    /**
     * Reusable capture destination for {@code scene.snapshot(...)} — sized once, at
     * construction, from {@link #orientationMapping}'s logical dimensions (never from the
     * {@code Scene} passed to {@link #start}, which is merely validated against it — see
     * {@link #start}). Read and written only from the FX thread (inside {@link #maybeCapture}).
     */
    private final WritableImage captureImage;

    /**
     * Three-entry free list of pixel buffers cycled between the FX thread (fills one per
     * capture) and the display thread (returns one after {@link #processRenderWork} finishes
     * with it, via {@link #recycle}) — see the design doc's Pixel handoff section. Each
     * {@link CaptureBuffer} pairs a {@code byte[]} with a {@code MemorySegment} created once
     * over that same array ({@link MemorySegment#ofArray}), so no segment is ever built from a
     * {@code ByteBuffer}'s position/limit (2026-09-22 Opus review finding C9). Capacity 3 is
     * also the sole capture backpressure signal (C3): {@link #maybeCapture} skips a tick
     * entirely when {@code poll()} returns {@code null} rather than consulting
     * {@link #writeInFlight}. With three buffers the backpressure is loose by design: during a
     * long refresh a tick still snapshots (as an idle tick does anyway) and displaces the stale
     * queued frame; ticks are only skipped when all three are out, which is also the only
     * condition that triggers the starvation WARN (one leaked buffer is therefore silent).
     */
    private final BlockingQueue<CaptureBuffer> freeBuffers = new ArrayBlockingQueue<>(CAPTURE_BUFFER_COUNT);

    /**
     * FX-thread-only write (bumped in {@link #maybeCapture} immediately before each successful
     * capture), so every {@link RenderWork} that ever reaches the display thread carries a
     * strictly increasing generation number. {@code volatile} so {@link #forceRefresh} (callable
     * from any thread) can read a fresh value. Starts at 1 so 0 can mean "no forced reset
     * outstanding" in {@link #resetRequestedSeq}.
     */
    private volatile long nextCaptureSeq = 1L;

    /**
     * Set by {@link #forceRefresh()} to {@link #nextCaptureSeq}'s value at call time — i.e. the
     * generation number of the next capture that hasn't started yet. Consumed by
     * {@link #processRenderWork}, which calls {@code session.reset()} — the only thread allowed
     * to touch the not-thread-safe {@link RenderSession} — only for a {@link RenderWork} whose
     * own {@code captureSeq()} is {@code >=} this value.
     *
     * <p>Originally a plain {@code AtomicBoolean} consumed unconditionally by the next frame the
     * display thread happened to process. That was wrong in two ways later found by the
     * 2026-09-24 Opus implementation review: (finding I4) a frame already in flight or queued
     * *before* {@code forceRefresh()} was called could consume the flag, so the ghost-clearing
     * {@code FULL} write would land on stale content instead of the newly forced capture — which
     * defeats the documented post-{@code sleep()}/{@code wake()} use case entirely; (finding I5)
     * if {@code session.reset()} itself threw, the flag had already been cleared and the force
     * request was lost with no {@code FULL} ever issued. The generation number fixes both: a
     * pre-existing frame's {@code captureSeq()} is always less than a value read from
     * {@link #nextCaptureSeq} after it was captured, so it can never satisfy the request; and
     * {@link #processRenderWork} clears this (via {@code compareAndSet}, so a newer concurrent
     * {@code forceRefresh()} request is never clobbered) only *after* {@code session.reset()}
     * returns successfully, not before.
     *
     * <p>Still deliberately bridge-level rather than a per-{@link RenderWork} field: the render
     * queue is "latest wins" ({@link #offerReplacing}), so a flag carried on the work item
     * itself could be silently discarded if a forced capture is displaced by a later non-forced
     * one before the display thread takes it (2026-09-22 Opus review finding C2) — the
     * generation number sidesteps that the same way the boolean did, by living on the bridge and
     * being checked against *whichever* work item the display thread next actually processes.
     */
    private final AtomicLong resetRequestedSeq = new AtomicLong(0);

    /**
     * Set by {@link #requestRefresh()}/{@link #forceRefresh()}; cleared by {@link #maybeCapture}
     * only for the specific attempt it was set for — see that method's javadoc on the
     * "observed-before-snapshot" sampling this requires (2026-09-24 Opus implementation review
     * finding I6: clearing it unconditionally after *any* successful capture, including an
     * unrelated timer tick whose snapshot predates the request, could drop a concurrently-issued
     * request with nothing left to retry it). If every attempt is skipped (e.g. the display
     * thread is mid-write when the buffer it holds is the only one free), {@link
     * #processRenderWork} re-arms a retry via {@link #retryPendingCapture} once it returns a
     * buffer to {@link #freeBuffers} — without this, a caller using {@code
     * captureIntervalMs(0)} (manual-only capture, no timer) could have a refresh request
     * silently dropped forever (2026-09-22 Opus review finding C1).
     */
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);

    /** How many consecutive capture ticks have been skipped for want of a free buffer — see
     * {@link #recordCaptureSkipped}. Escalates from DEBUG to a one-time WARN so a leaked/never-
     * returned buffer (finding C4) is distinguishable from ordinary brief saturation. */
    private final AtomicInteger consecutiveBufferStarvations = new AtomicInteger();

    private static final int BUFFER_STARVATION_WARN_THRESHOLD = 10;

    /** Display-thread-only (only ever touched inside {@link #processRenderWork}, which is the
     * only caller of {@link #logWriteFailure}/{@link #recoverFromWriteFailure}) — counts
     * consecutive failed writes so a driver that keeps failing (e.g. disconnected hardware)
     * logs one full stack trace and then throttles to WARN, instead of flooding the log with a
     * full stack trace on every capture-interval retry (Part D implementation review finding
     * I2). Reset to zero on the first successful write after a run of failures. */
    private int consecutiveWriteFailures;

    private static final int WRITE_FAILURE_WARN_THRESHOLD = 5;

    /** FX-thread-only (read/written exclusively inside {@link #maybeCapture}, which always
     * runs there — see that method's javadoc). Compared against {@code System.nanoTime()},
     * never an {@code AnimationTimer.handle(long)} timestamp: that clock substitutes a
     * synthetic counter under {@code -Djavafx.animation.fixedPulseLength}, which would make it
     * disagree with the {@code System.nanoTime()} a cross-thread {@code requestRefresh()} call
     * would otherwise have to use (2026-09-22 Opus review finding C8). Meaningless until
     * {@link #hasCaptured} is {@code true} — see that field's javadoc for why a sentinel value
     * here (the original approach) doesn't work. */
    private volatile long lastCaptureNanos;

    /**
     * Guards the interval check in {@link #maybeCapture} against comparing {@link
     * #lastCaptureNanos} before it holds a real timestamp. The original implementation used
     * {@code Long.MIN_VALUE} as a "never captured yet" sentinel and relied on
     * {@code now - lastCaptureNanos} always being large — but {@code System.nanoTime()} is
     * positive (JVM-uptime-relative on Linux/HotSpot), so that subtraction overflows a signed
     * {@code long} and wraps to a large *negative* number, making {@code < intervalNanos} true
     * unconditionally. The result: with the sentinel approach, the very first capture never
     * happened — {@code maybeCapture} skipped every timer tick forever, since only a successful
     * capture (which requires passing the gate) ever advances {@code lastCaptureNanos} into a
     * safe range (2026-09-24 Opus implementation review finding I1). A plain boolean sidesteps
     * the arithmetic entirely: the interval check only runs once a real timestamp exists.
     */
    private volatile boolean hasCaptured;

    /**
     * Timer-driven capture — see {@link #maybeCapture}. Registering/unregistering an
     * {@code AnimationTimer} off the FX thread is delegated asynchronously by JavaFX itself
     * (its own javadoc says so), so {@link #start} asserts the FX thread precisely so this
     * timer's registration is synchronous and {@link #stop}'s shutdown ordering (null the
     * {@code scene} field before tearing down the driver) is a real guard against a
     * still-in-flight {@code handle()} call, not just a convention (2026-09-22 Opus review
     * finding C7).
     */
    private final AnimationTimer captureTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            maybeCapture(false);
        }
    };

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
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile boolean stopped;

    /**
     * Set right after {@link #captureTimer}{@code .start()} in {@link #start}; guards the
     * matching {@code .stop()} call in {@link #stop}. Needed because an off-FX-thread call to
     * {@code AnimationTimer.start()}/{@code .stop()} is delegated to the FX thread via {@code
     * Platform.runLater(...)}, which throws {@code IllegalStateException("Toolkit not
     * initialized")} if the toolkit was never started ({@code PlatformImpl.runLater}) — without
     * this flag, {@link #stop} on a bridge whose {@link #start} was never called (a supported,
     * tested case — see {@code
     * EInkBridgeDisplayThreadTest#stop_withoutEverStarting_doesNotThrowAndIsIdempotent}) would
     * fail even though this test suite deliberately never starts the FX toolkit (Q7/Q8 in the
     * design doc). (An earlier version of this javadoc attributed the throw to the
     * {@code AnimationTimer} constructor's own {@code Toolkit.getToolkit()} call instead —
     * wrong: that call succeeds even without a started toolkit, and runs anyway in every
     * {@code EInkBridge} this test suite builds, evidenced by all of {@code pux4j-fx}'s tests
     * passing without ever starting one. The actual thrower is specifically the off-FX-thread
     * {@code runLater} delegation, which is exactly why this flag — rather than, say, a
     * toolkit-initialised check — is the correct guard: 2026-09-24 Opus implementation review
     * finding I7c.)
     */
    private volatile boolean captureTimerStarted;

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

        int w = orientationMapping.logicalWidth();
        int h = orientationMapping.logicalHeight();
        this.captureImage = new WritableImage(w, h);
        int bufferSize = w * 4 * h;
        for (int i = 0; i < CAPTURE_BUFFER_COUNT; i++) {
            this.freeBuffers.add(newCaptureBuffer(bufferSize));
        }
    }

    /**
     * One pixel buffer in {@link #freeBuffers}: a {@code byte[]} plus a {@link MemorySegment}
     * built once, over that same array, at allocation time — never re-derived from a
     * {@code ByteBuffer}'s position/limit (2026-09-22 Opus review finding C9, which also notes
     * this lets capture use {@link javafx.scene.image.PixelReader}'s array-based
     * {@code getPixels} overload rather than the generic {@code Buffer} one). Package-private
     * (not {@code private}) so tests can build a buffer-carrying {@link RenderWork} directly and
     * exercise free-list recycling without a real scene snapshot — see
     * {@code EInkBridgeCaptureTest} (2026-09-24 Opus implementation review §4).
     */
    record CaptureBuffer(byte[] pixels, MemorySegment segment) {}

    static CaptureBuffer newCaptureBuffer(int sizeBytes) {
        byte[] pixels = new byte[sizeBytes];
        return new CaptureBuffer(pixels, MemorySegment.ofArray(pixels));
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
     * Starts the display and touch virtual threads, and (if {@code captureIntervalMs > 0})
     * timer-driven capture, against the given stage-less {@code Scene}. The touch thread is
     * only started if a touch driver was wired.
     *
     * <p>Must run on the JavaFX application thread. This is required anyway — {@code
     * Scene.snapshot()} asserts it internally — but it is also what makes the {@code
     * AnimationTimer}'s {@code start()} call (inside this method) register synchronously
     * rather than being delegated asynchronously by JavaFX, which is what lets {@link #stop}'s
     * shutdown ordering (null {@code scene} before tearing down the driver) actually guard
     * against a capture that is still in flight (2026-09-22 Opus review finding C7).
     *
     * <p>Also validates that {@code scene} was constructed at exactly
     * {@link #displayWidth()}/{@link #displayHeight()} (a mismatched size is silently
     * cropped/padded by {@code Scene.snapshot()}, not rejected — finding C10) and that its
     * fill is fully opaque ({@link dev.pux4j.ui.transform}'s pixel transform ignores alpha, so
     * a transparent fill renders as black on the panel — finding C11).
     *
     * <p>Not safe to call concurrently with itself or {@link #stop()} from another thread —
     * the bridge's lifecycle is expected to be driven from one place (typically
     * {@code Application.start()}/{@code Application.stop()}), not raced.
     *
     * @throws IllegalStateException if not called on the FX application thread, if already
     *         started, or if called after {@link #stop()} (restarting a stopped bridge would
     *         run threads against a driver that has already been put to sleep/closed)
     * @throws IllegalArgumentException if {@code scene}'s size does not match
     *         {@link #displayWidth()}/{@link #displayHeight()}
     */
    public void start(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "EInkBridge.start(Scene) must run on the JavaFX application thread "
                    + "(e.g. Application.start()) — scene capture requires it, and it is also "
                    + "what makes the capture timer's registration synchronous with this call.");
        }
        if (stopped) {
            throw new IllegalStateException("EInkBridge already stopped — not restartable");
        }
        if (displayThread != null) {
            throw new IllegalStateException("EInkBridge already started");
        }
        int sceneWidth = (int) Math.round(scene.getWidth());
        int sceneHeight = (int) Math.round(scene.getHeight());
        if (sceneWidth != displayWidth() || sceneHeight != displayHeight()) {
            throw new IllegalArgumentException(
                    "Scene is " + sceneWidth + "x" + sceneHeight + " but the display is "
                    + displayWidth() + "x" + displayHeight() + " — construct the Scene at "
                    + "bridge.displayWidth()/displayHeight(); EInkBridge cannot resize it");
        }
        warnIfFillNotOpaque(scene);

        this.scene = scene;
        activeContacts.clear();
        hasActiveContacts.set(false);
        hasCaptured = false;

        displayThread = Thread.ofVirtual().name("eink-display").start(this::runDisplayLoop);
        if (touchDriver != null) {
            touchThread = Thread.ofVirtual().name("eink-touch").start(this::runTouchLoop);
        }
        if (captureIntervalMs > 0) {
            captureTimer.start();
            captureTimerStarted = true;
        }
        LOG.info("EInkBridge started (touch {}, capture {})",
                touchDriver != null ? "enabled" : "disabled",
                captureIntervalMs > 0 ? captureIntervalMs + "ms" : "manual (requestRefresh only)");
    }

    /**
     * Logs a WARN if {@code scene}'s fill is a non-opaque (or non-{@link Color}) paint — see
     * {@link #start}'s javadoc on why alpha is otherwise silently mishandled. Best-effort: an
     * unrecognised {@code Paint} implementation can't be checked for opacity, so it only warns
     * that it couldn't check, rather than failing the whole {@link #start} call over it.
     */
    private static void warnIfFillNotOpaque(Scene scene) {
        var fill = scene.getFill();
        if (fill instanceof Color color) {
            if (color.getOpacity() < 1.0) {
                LOG.warn("Scene fill {} is not fully opaque; pixel transforms ignore alpha, so "
                        + "transparent areas will render as black on the eInk panel", color);
            }
        } else if (fill != null) {
            LOG.warn("Scene fill {} is not a Color; cannot verify it is fully opaque — pixel "
                    + "transforms ignore alpha, so a transparent fill would render as black", fill);
        }
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
     *
     * <p>If called on the FX thread while the display thread is still draining, waits in a
     * nested event loop (so writes that complete via {@code Platform.runLater} can finish), which
     * means other FX-thread work — pulses, queued {@code runLater} tasks, app handlers — can run
     * <em>inside</em> this call. From inside a pulse (nested loops are illegal there) it falls
     * back to a blocking join and the stall that avoids is back (CP3 finding 4).
     *
     * <p>Deliberately NOT asserted to run on the FX thread (unlike {@link #start}) — it must
     * stay callable from wherever {@link #start} was never reached (e.g. driver construction
     * succeeded but the application is shutting down before showing anything). Because of
     * that, {@link #captureTimer}{@code .stop()} below may be delegated asynchronously by
     * JavaFX rather than taking effect immediately (see {@link #start}'s javadoc and
     * 2026-09-22 Opus review finding C7), so {@code scene} is set to {@code null} as the very
     * first thing this method does (before even {@code captureTimer.stop()}), not merely before
     * driver teardown — a capture that is still in flight when {@code stop()} is called (a
     * queued {@code AnimationTimer.handle()}, or a racing {@code requestRefresh()}/
     * {@code forceRefresh()} {@code runLater}) sees the null scene in {@link #maybeCapture} and
     * becomes a no-op. This also closes the window a capture-only guard can't:
     * {@link #offerReplacing} separately refuses to let any ordinary capture displace the
     * poison pill once it is queued, since a capture racing the FX-thread scheduling delay
     * above could otherwise still queue itself *after* the pill and bump it out — see that
     * method's javadoc (2026-09-24 Opus implementation review finding I3, which found the
     * original ordering here protected driver teardown but not this queue race).
     */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return; // a concurrent or earlier stop() owns the teardown
        }
        stopped = true;
        // Moved ahead of captureTimer.stop()/the poison-pill hand-off below (2026-09-24 Opus
        // implementation review finding I3): captureTimer.stop() can be delegated
        // asynchronously if this thread isn't the FX thread (see this method's javadoc), so a
        // capture already in flight — or one still queued behind it before the timer actually
        // stops — must be stopped by maybeCapture's own guards, not by assuming the timer is
        // already off. Nulling scene here, before anything else, is what actually makes that
        // guard effective for the whole rest of this method, not just driver teardown.
        scene = null;

        if (captureTimerStarted) {
            captureTimer.stop();
        }

        if (touchThread != null) {
            touchThread.interrupt();
            joinQuietly(touchThread, TOUCH_JOIN_TIMEOUT_MS);
            if (touchThread.isAlive()) {
                LOG.warn("Touch thread did not exit within {} ms (readTouches() stuck?); "
                        + "continuing shutdown", TOUCH_JOIN_TIMEOUT_MS);
            }
            touchThread = null;
        }
        if (displayThread != null) {
            offerReplacing(POISON_PILL);
            awaitDisplayThreadExit(displayThread, DISPLAY_DRAIN_TIMEOUT_MS);
            if (displayThread.isAlive()) {
                LOG.warn("Display thread did not drain within {} ms; interrupting", DISPLAY_DRAIN_TIMEOUT_MS);
                displayThread.interrupt();
                joinQuietly(displayThread, DISPLAY_DRAIN_TIMEOUT_MS);
                if (displayThread.isAlive()) {
                    LOG.warn("Display thread still alive {} ms after interrupt; releasing the "
                            + "driver anyway", DISPLAY_DRAIN_TIMEOUT_MS);
                }
            }
            displayThread = null;
        }

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

    /**
     * Joins {@code thread} for up to {@code timeoutMs}. On the FX thread a plain join would
     * stall every in-flight write that completes via {@code Platform.runLater} (the emulator's
     * does) for the whole timeout, so there it waits in a nested event loop instead, which the
     * joiner exits as soon as the thread ends (CP3 finding 4). Falls back to a plain bounded
     * join if a nested loop isn't permitted (e.g. during toolkit shutdown).
     */
    private static void awaitDisplayThreadExit(Thread thread, long timeoutMs) {
        // enterNestedEventLoop throws during a pulse (e.g. stop() called from an AnimationTimer
        // or an animation's onFinished handler); canStartNestedEventLoop() reports exactly that.
        if (!Platform.isFxApplicationThread() || !thread.isAlive()
                || !Platform.canStartNestedEventLoop()) {
            joinQuietly(thread, timeoutMs);
            return;
        }
        Object key = new Object();
        // Only ever read/written on the FX thread (the exit task below runs there), so a plain
        // array cell is enough: it guards against exiting a loop that was never entered.
        boolean[] entered = {false};
        Thread.ofVirtual().name("eink-stop-join").start(() -> {
            joinQuietly(thread, timeoutMs);
            try {
                Platform.runLater(() -> {
                    if (entered[0]) {
                        Platform.exitNestedEventLoop(key, null);
                    }
                });
            } catch (IllegalStateException e) {
                // toolkit gone; the toolkit exits any nested loop itself on shutdown
            }
        });
        try {
            entered[0] = true;
            Platform.enterNestedEventLoop(key);
        } catch (IllegalStateException e) {
            entered[0] = false;
            LOG.debug("Nested event loop not permitted during stop(); using a blocking join", e);
            joinQuietly(thread, timeoutMs);
        }
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
     * One captured frame handed from the FX thread to the display thread. {@code buffer} is
     * the {@link CaptureBuffer} that owns {@code bgra}'s backing array, so
     * {@link #processRenderWork}/{@link #recycle} can return it to {@link #freeBuffers} once
     * consumed — {@code null} for work built via the single- or two-argument constructors
     * (tests, and {@link #submitRenderWork(MemorySegment)}'s external callers), which isn't
     * drawn from the free list and so has nothing to recycle. {@code captureSeq} is {@link
     * #nextCaptureSeq}'s value at the moment this work was captured — {@code 0} (never a real
     * capture's value, which starts at 1) for the same test/external-caller constructors, so
     * they can never accidentally satisfy a {@link #resetRequestedSeq} check. Package-private
     * (not {@code private}) so tests can construct one directly and drive
     * {@link #processRenderWork} without going through a real scene snapshot — snapshotting
     * needs a live {@code Scene}, which (see {@link #injectTouches}'s javadoc) needs the JavaFX
     * toolkit started, which this codebase deliberately doesn't require of its test suite
     * (Q7/Q8 in the design doc — no headless Glass platform until JavaFX 26).
     *
     * <p><b>Compare by reference (`==`), never `.equals()`.</b> As a record, {@code equals()} is
     * value-based, and both {@code MemorySegment} (heap-backed, over the same array) and this
     * whole record can compare equal across two distinct instances built from equivalent
     * arguments — including, e.g., {@code new RenderWork(null, null, 0).equals(POISON_PILL)}
     * being {@code true}. Nothing today calls {@code .equals()}/{@code .contains()} on a
     * {@code RenderWork} ({@link #runDisplayLoop} uses {@code ==} at the poison-pill check), but
     * a future {@code Collection}-based use of one would silently match the sentinel
     * (2026-09-24 Opus implementation review finding I8).
     */
    record RenderWork(MemorySegment bgra, CaptureBuffer buffer, long captureSeq) {
        RenderWork(MemorySegment bgra) {
            this(bgra, null, 0);
        }

        RenderWork(MemorySegment bgra, CaptureBuffer buffer) {
            this(bgra, buffer, 0);
        }
    }

    /**
     * Sentinel {@link RenderWork} used only to unblock {@link #runDisplayLoop}'s
     * {@code renderQueue.take()} during {@link #stop()} — checked by reference identity, never
     * passed to {@link #processRenderWork} (its {@code null} {@code bgra} would NPE there).
     */
    private static final RenderWork POISON_PILL = new RenderWork(null, null, 0);

    /**
     * "Latest wins" replace: drops whatever is currently queued (if anything) and queues
     * {@code work} in its place — capacity-1 queue, {@code poll()} then {@code offer()}.
     * {@code synchronized} because Phase 6.4 part C has two producers — captures from the FX
     * thread and {@link #stop()}'s poison-pill hand-off, which can run from any thread (it is
     * deliberately not FX-thread-asserted; see {@link #stop}'s javadoc) — and an interleaved
     * poll()/offer() pair from each could otherwise drop one producer's item silently (the
     * capacity-1 queue's second {@code offer()} would just return {@code false}), which for a
     * dropped poison pill would make {@link #stop()} block its full drain timeout and then
     * interrupt a thread that was never actually stuck (2026-09-22 Opus review finding C5).
     *
     * <p>Also owns free-list recycling for whatever this call displaces (finding C5/C6): a
     * displaced item's {@link CaptureBuffer}, if it has one, goes straight back to
     * {@link #freeBuffers} here rather than at each of this method's call sites, so that rule
     * can't be missed by a future caller the way {@link #stop}'s old poison-pill call used to
     * silently drop it.
     *
     * <p><b>Never displaces an already-queued {@link #POISON_PILL}</b> (2026-09-24 Opus
     * implementation review finding I3): {@code synchronized} alone only fixes a torn
     * poll()/offer() pair between the two producers — it does nothing to stop an ordinary
     * capture from legitimately displacing the pill *after* it was queued, since displacement is
     * exactly what "latest wins" is supposed to do. A displaced pill has no {@link CaptureBuffer}
     * (see {@link #recycle}), so it would simply vanish, and {@link #stop()} would then block its
     * full {@code DISPLAY_DRAIN_TIMEOUT_MS} before falsely concluding the display thread was
     * stuck. So: if the pill is already queued, any *other* work item offered here is refused
     * (its buffer recycled immediately) rather than displacing it — {@link #stop()} is draining
     * and nothing but the pill itself may occupy the queue from that point on.
     *
     * @return the displaced item, or {@code null} if the queue was empty or {@code work} was
     *         refused because the pill is already queued
     */
    private synchronized RenderWork offerReplacing(RenderWork work) {
        if (work != POISON_PILL && renderQueue.peek() == POISON_PILL) {
            recycle(work);
            return null;
        }
        RenderWork displaced = renderQueue.poll();
        recycle(displaced);
        renderQueue.offer(work);
        return displaced;
    }

    /**
     * Returns {@code work}'s {@link CaptureBuffer} (if it has one — see {@link RenderWork}'s
     * javadoc) to {@link #freeBuffers}. Safe to call with {@code null} or {@link #POISON_PILL}.
     */
    private void recycle(RenderWork work) {
        if (work != null && work.buffer() != null) {
            freeBuffers.offer(work.buffer());
        }
    }

    /**
     * Enqueues a captured frame for the display thread — "latest wins", see
     * {@link #offerReplacing}. Package-private: exists so the display thread's
     * consume/prepare/write logic can be exercised directly by tests, without needing a real
     * scene snapshot. The bridge's own capture path ({@link #maybeCapture}) calls
     * {@link #offerReplacing} directly with a buffer-carrying {@link RenderWork} instead of
     * going through this method, since this overload's {@code MemorySegment}-only signature
     * has no buffer to attach.
     *
     * @return the previously-queued item this one replaced, or {@code null} if none
     */
    RenderWork submitRenderWork(MemorySegment bgra) {
        return offerReplacing(new RenderWork(bgra));
    }

    /**
     * As {@link #submitRenderWork(MemorySegment)}, but for a caller-built {@link RenderWork}
     * (e.g. one carrying a {@link CaptureBuffer}, as {@link #maybeCapture} builds) — lets a test
     * exercise {@link #offerReplacing}'s displacement/recycling behavior directly without a
     * {@code Scene} (2026-09-24 Opus implementation review §4).
     */
    RenderWork submitRenderWork(RenderWork work) {
        return offerReplacing(work);
    }

    /**
     * Package-private — drains {@link #freeBuffers} to empty (discarding the buffers) so a test
     * can start free-list recycling assertions from a known state (2026-09-24 Opus
     * implementation review §4).
     */
    void drainFreeBuffersForTest() {
        freeBuffers.clear();
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
     * Package-private — queues {@link #POISON_PILL} exactly as {@link #stop()} does, without
     * spinning up the threaded lifecycle, so a test can verify {@link #offerReplacing} refuses
     * to let a subsequent {@link #submitRenderWork} displace it (2026-09-24 Opus implementation
     * review finding I3).
     */
    void queuePoisonPillForTest() {
        offerReplacing(POISON_PILL);
    }

    /**
     * Package-private — a diagnostic/test hook, not the capture backpressure signal (see this
     * flag's field javadoc for why — finding C3).
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
     *
     * <p>Wrapped in an outer {@code try}/{@code finally} (not just around the write) so
     * {@code work}'s {@link CaptureBuffer} is always returned to {@link #freeBuffers} —
     * including on the "unchanged frame" path below, the most common one for an idle UI, and
     * on {@code session.prepare()} throwing. A {@code finally} scoped only to the write (as an
     * earlier draft had it) would starve the free list within two idle capture ticks
     * (2026-09-22 Opus review finding C6). The same {@code finally} also re-arms a pending
     * {@link #requestRefresh()}/{@link #forceRefresh()} that an earlier capture attempt had to
     * skip for want of a free buffer — see {@link #refreshPending}'s javadoc (finding C1).
     *
     * <p>{@code session.reset()} — consuming {@link #resetRequestedSeq} — runs here, on the
     * display thread, immediately before {@code session.prepare()}: {@link RenderSession} is
     * documented not thread-safe, so this is the only place allowed to call either method
     * (finding C2). Only cleared (via {@code compareAndSet}, so a newer concurrent
     * {@code forceRefresh()} isn't clobbered) once {@code reset()} has actually returned — if it
     * throws, the request survives for a later frame to retry rather than being silently lost
     * (finding I5).
     */
    void processRenderWork(RenderWork work) {
        try {
            long requiredSeq = resetRequestedSeq.get();
            if (requiredSeq != 0 && work.captureSeq() >= requiredSeq) {
                session.reset();
                resetRequestedSeq.compareAndSet(requiredSeq, 0);
            }

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
            LOG.info("Bridge frame: captureSeq={} mode={} region={} frameBytes={}",
                work.captureSeq(), r.decision().mode(), r.decision().region(),
                r.frame() instanceof dev.pux4j.ui.core.MonochromeFrame mf ? mf.data().length : -1);
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
                consecutiveWriteFailures = 0;
            } catch (ExecutionException e) {
                logWriteFailure("Display write failed", e.getCause());
                recoverFromWriteFailure();
            } catch (InterruptedException e) {
                // Only reachable via stop()'s timed-out fallback interrupt (the normal shutdown
                // path drains via the poison pill instead, precisely to avoid abandoning a write
                // mid-flight) — the driver's own write (on its internal virtual thread, per
                // EInkDisplayDriver's contract) is not cancelled and completes on its own, so
                // RenderSession's idea of "previous" is not actually stale here; no reset armed.
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logWriteFailure("Display write rejected", e);
                recoverFromWriteFailure();
            } finally {
                writeInFlight.set(false);
            }
        } finally {
            recycle(work);
            if (refreshPending.get() && !stopped) {
                try {
                    Platform.runLater(this::retryPendingCapture);
                } catch (IllegalStateException e) {
                    // Toolkit already shut down between the stopped check above and this call —
                    // a benign shutdown race, not a display-thread failure (finding C12).
                    LOG.debug("Could not schedule pending-refresh retry; toolkit likely shut down", e);
                }
            }
        }
    }

    /**
     * Retries a {@link #requestRefresh()}/{@link #forceRefresh()} that a previous capture
     * attempt had to skip for want of a free buffer — scheduled by {@link #processRenderWork}
     * onto the FX thread right after it returns a buffer to {@link #freeBuffers}, so a buffer
     * is normally available immediately. Re-checks {@link #refreshPending} first since it may
     * already have been satisfied by an unrelated capture (the timer, or another retry) that
     * ran between this being scheduled and now.
     */
    private void retryPendingCapture() {
        if (refreshPending.get()) {
            maybeCapture(true);
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
     * Package-private for tests (CP3). Polls {@link TouchDriver#readTouches()} and dispatches each batch to the FX thread. An
     * empty poll is normally dispatched as nothing — except when {@link #hasActiveContacts} says
     * a contact is still tracked as down, in which case the empty poll is dispatched anyway so
     * {@link #injectTouches} can synthesize its release (see that method's javadoc for why a
     * lift doesn't always arrive as an explicit {@code down=false} point). A {@link
     * RuntimeException} from {@code readTouches()} (e.g. a transient I2C fault) is logged and
     * retried after the idle interval, rather than ending the thread — touch going dead for
     * the rest of the process on one bad read would be a much worse failure mode than a
     * dropped poll.
     */
    void runTouchLoop() {
        LOG.info("Touch thread started");
        try {
            // The per-iteration sleep() is what normally delivers the interrupt; the flag check
            // is a backstop for an interrupt that lands while blocked elsewhere and whose
            // InterruptedException was consumed (CP3 finding 2).
            while (!Thread.currentThread().isInterrupted()) {
                // Permit first, then read: a batch read while the FX thread is still busy would
                // go stale waiting for it. Released here if there is nothing to dispatch,
                // otherwise by the FX-thread task (or dispatchToFxThread if it is rejected).
                touchDispatchPermit.acquire();
                boolean dispatched = false;
                try {
                    List<TouchPoint> raw = touchDriver.readTouches();
                    if (!raw.isEmpty()) {
                        dispatchToFxThread(raw.stream().map(touchMapper::map).toList());
                        dispatched = true;
                    } else if (hasActiveContacts.get()) {
                        dispatchToFxThread(List.of());
                        dispatched = true;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Touch poll failed; retrying", e);
                } finally {
                    if (!dispatched) {
                        touchDispatchPermit.release();
                    }
                }
                Thread.sleep(TOUCH_POLL_IDLE_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Touch thread exiting");
    }

    /**
     * Hands one poll's contacts to the FX thread; the caller already holds
     * {@link #touchDispatchPermit}, which the posted task releases once it has run.
     * Batches already read are never dropped or merged, so a press and its release can't be coalesced away; the IC
     * simply isn't read again until the FX thread has caught up (so whether a tap that starts
     * and ends entirely inside one FX stall survives depends on the IC holding its report).
     * If the toolkit has already exited, {@code runLater} drops the task silently rather than
     * throwing, so the permit is then never released — harmless, since {@link #stop()}
     * interrupts the thread out of {@code acquire()}.
     */
    private void dispatchToFxThread(List<TouchPoint> contacts) {
        // If the executor throws (toolkit never started), the task was rejected and the caller's
        // finally releases the permit, since it never got as far as marking the dispatch made.
        fxExecutor.accept(() -> {
            try {
                injectTouches(contacts);
            } finally {
                touchDispatchPermit.release();
            }
        });
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
     * Triggers an immediate capture and pipeline run, bypassing the timer interval, writing
     * only if the frame actually changed. Safe to call from any thread — if not already on the
     * FX thread, the actual capture is dispatched there via {@code Platform.runLater(...)}
     * (snapshotting requires it); note this throws {@code IllegalStateException} if called
     * off the FX thread before the JavaFX toolkit has started (e.g. from
     * {@code Application.init()}), and is silently dropped if called after
     * {@code Platform.exit()}.
     *
     * <p>If no free capture buffer is available right now (the display thread is still working
     * through the previous frame), the request is not dropped: it is retried automatically as
     * soon as a buffer is returned — see {@link #refreshPending}'s javadoc (2026-09-22 Opus
     * review finding C1). This matters most with {@link Builder#captureIntervalMs(long)
     * captureIntervalMs(0)}, where there is no timer to pick up a dropped request on its own —
     * which is also why this method must actually bypass the interval gate itself, not just
     * accept a parameter named as if it did (2026-09-24 Opus implementation review finding I2:
     * an earlier version of this method called {@code triggerCapture(false)}, so in manual-only
     * mode it could never capture anything at all).
     *
     * <p>Call this from the same {@code Platform.runLater(...)} turn that made the scene
     * change you want reflected, not from an unrelated background thread afterwards — queued
     * FX-thread work (including this bridge's own touch-event dispatch) runs in order, so a
     * {@code requestRefresh()} issued from a different thread has no ordering guarantee
     * relative to scene mutations that thread queues later (finding C12).
     */
    public void requestRefresh() {
        triggerCapture(true);
    }

    /**
     * As {@link #requestRefresh()}, but forces a write regardless of whether the frame
     * changed. The actual {@link RenderSession#reset()} call happens on the display thread,
     * immediately before the next {@code session.prepare()} for a frame captured at or after
     * this call — never here — because {@code RenderSession} is documented not thread-safe and
     * the display thread is its only permitted caller; see {@link #resetRequestedSeq}'s javadoc
     * (2026-09-22 Opus review finding C2, refined 2026-09-24 as finding I4/I5). There is no
     * "force a specific mode" concept in {@code RenderSession} today — forcing
     * {@code FAST}/{@code PARTIAL} would need new API there, not just a parameter here, so this
     * method takes none rather than accept a {@code RefreshMode} that every value but
     * {@code FULL} would silently misrepresent.
     */
    public void forceRefresh() {
        resetRequestedSeq.set(nextCaptureSeq);
        triggerCapture(true);
    }

    /**
     * Logs a failed write — full stack trace for the first failure in a run, throttled to a
     * one-time WARN (no stack trace) at {@link #WRITE_FAILURE_WARN_THRESHOLD} consecutive
     * failures thereafter, mirroring {@link #recordCaptureSkipped}'s pattern. Without this, a
     * driver that keeps failing (disconnected hardware, a stuck bus) would log a full stack
     * trace on every capture-interval retry {@link #recoverFromWriteFailure} triggers — on the
     * Pi that means flooding {@code ~/tmp/run-log.txt} for as long as the fault persists (Part D
     * implementation review finding I2).
     */
    private void logWriteFailure(String message, Throwable cause) {
        consecutiveWriteFailures++;
        if (consecutiveWriteFailures <= 1) {
            LOG.error(message, cause);
        } else if (consecutiveWriteFailures == WRITE_FAILURE_WARN_THRESHOLD) {
            LOG.warn("{} consecutive write failures ({}); the display driver may be stuck or "
                    + "disconnected — throttling further stack traces for this failure run",
                    consecutiveWriteFailures, message);
        } else {
            LOG.debug("{} (failure #{} in this run)", message, consecutiveWriteFailures);
        }
    }

    /**
     * Recovers {@link RenderSession}'s state after {@link #processRenderWork} catches a failed
     * write — see that method's {@code ExecutionException}/{@code RuntimeException} catch
     * clauses. {@code RenderSession.prepareMonochrome}/{@code prepareFourGray} set
     * {@code previous = next} <em>before</em> the write is attempted (Part D implementation
     * review finding I9). A failed write must not silently desynchronise {@code
     * RenderSession}'s idea of the previous frame from what the panel actually shows. Left
     * as-is, a failed write means the driver never actually showed that frame, but
     * {@code RenderSession} now believes it did — so a later capture of that same (never-
     * actually-displayed) content diffs against it as "unchanged" and is silently dropped
     * forever, and the panel never catches up.
     *
     * <p>Calls {@code session.reset()} directly, here, rather than only arming
     * {@link #resetRequestedSeq} for a future capture (an earlier version of this fix, Part D
     * implementation review finding I1): this method always runs on the display thread — inside
     * {@link #processRenderWork}, which {@link RenderSession}'s own contract makes the only
     * permitted caller of {@code reset()}/{@code prepare()} — so calling it immediately is safe,
     * and it closes a gap the seq-based approach left open. A frame captured <em>before</em> the
     * failure but still queued behind it (its {@code captureSeq} therefore below whatever
     * {@link #resetRequestedSeq} would have been armed to) would otherwise still diff against
     * the stale {@code previous} on its own turn through {@code processRenderWork} — right up
     * until the seq-armed reset eventually caught a later frame — risking one intermediate
     * PARTIAL write composited against content the panel never actually received. Resetting
     * immediately means the very next {@code processRenderWork} call of any kind, whatever its
     * {@code captureSeq}, sees {@code previous == null} and gets an unconditional FULL write.
     *
     * <p>Falls back to {@link #resetRequestedSeq} (the same mechanism {@link #forceRefresh()}
     * uses) only if {@code session.reset()} itself throws — still better than losing the
     * recovery request entirely, and consistent with {@link #resetRequestedSeq}'s existing
     * "survive a throw for a later frame to retry" contract (finding I5).
     */
    private void recoverFromWriteFailure() {
        try {
            session.reset();
        } catch (RuntimeException e) {
            LOG.error("session.reset() itself failed while recovering from a write failure; "
                    + "arming a reset for the next capture instead", e);
            long target = nextCaptureSeq;
            resetRequestedSeq.updateAndGet(current -> Math.max(current, target));
        }
    }

    /**
     * Shared by {@link #requestRefresh()}/{@link #forceRefresh()} — see their javadocs.
     * {@code bypassInterval} is always {@code true} for both of this class's public callers
     * today; it exists as a separate parameter (rather than being implied by the method you
     * called) only because {@link #retryPendingCapture} and {@link #captureTimer} also route
     * through {@link #maybeCapture} and need to request the two behaviours independently of
     * which public method — if any — originally triggered them.
     */
    private void triggerCapture(boolean bypassInterval) {
        refreshPending.set(true);
        if (Platform.isFxApplicationThread()) {
            maybeCapture(bypassInterval);
        } else {
            Platform.runLater(() -> maybeCapture(bypassInterval));
        }
    }

    /**
     * Attempts one capture. Must run on the FX thread (called directly from
     * {@link #captureTimer}'s {@code handle()}, or via {@code Platform.runLater(...)} from
     * {@link #triggerCapture}/{@link #retryPendingCapture}). {@code bypassInterval} bypasses the
     * capture-interval gate only — the free-buffer backpressure check below still applies
     * regardless (2026-09-22 Opus review finding C1's point that "bypassing the interval" must
     * not mean "bypassing backpressure too").
     *
     * <p>Uses {@link #freeBuffers} availability as the sole backpressure signal, not
     * {@link #writeInFlight} — see that field's javadoc for why the latter is set too late in
     * {@link #processRenderWork} to mean "display thread idle" (finding C3).
     *
     * <p>Samples {@link #refreshPending} <em>before</em> snapshotting, and clears it afterwards
     * only if it was already set at that sampling point — not unconditionally after any
     * successful capture. An unconditional clear would let an ordinary timer tick (whose
     * snapshot may have started before a concurrent {@link #requestRefresh()}/
     * {@link #forceRefresh()} call set the flag) satisfy a request it never actually captured
     * for, silently dropping that request with nothing left to retry it (2026-09-24 Opus
     * implementation review finding I6).
     */
    private void maybeCapture(boolean bypassInterval) {
        if (stopped) {
            return; // belt-and-braces alongside the scene==null check below — see stop()'s javadoc
        }
        Scene currentScene = scene;
        if (currentScene == null) {
            return; // not started, or stop() already nulled it out — see stop()'s javadoc
        }

        long now = System.nanoTime(); // never AnimationTimer.handle()'s `now` — see field javadoc (C8)
        if (!bypassInterval) {
            if (captureIntervalMs == 0) {
                return; // manual-only capture; only requestRefresh()/forceRefresh() drive it
            }
            if (!captureIntervalElapsed(now)) {
                return;
            }
        }

        CaptureBuffer buffer = freeBuffers.poll();
        if (buffer == null) {
            recordCaptureSkipped();
            return;
        }
        lastCaptureNanos = now;
        hasCaptured = true;
        boolean pendingAtCaptureStart = refreshPending.get();

        boolean submitted = false;
        try {
            currentScene.snapshot(captureImage);
            captureImage.getPixelReader().getPixels(
                    0, 0, orientationMapping.logicalWidth(), orientationMapping.logicalHeight(),
                    javafx.scene.image.PixelFormat.getByteBgraInstance(),
                    buffer.pixels(), 0, orientationMapping.logicalWidth() * 4);
            offerReplacing(new RenderWork(buffer.segment(), buffer, nextCaptureSeq++));
            submitted = true;
            if (pendingAtCaptureStart) {
                refreshPending.set(false);
            }
            consecutiveBufferStarvations.set(0);
        } finally {
            if (!submitted) {
                // Never leak a polled buffer on a snapshot/getPixels failure (finding C4).
                freeBuffers.offer(buffer);
            }
        }
    }

    /**
     * The capture-interval gate, factored out of {@link #maybeCapture} so a test can exercise
     * this exact arithmetic without a {@code Scene}/toolkit. Package-private for that reason.
     * {@code true} means enough time has passed (or no capture has happened yet) for a
     * non-forced capture to proceed.
     *
     * <p>This specific arithmetic previously overflowed: the original implementation used
     * {@code Long.MIN_VALUE} as the "never captured" sentinel for {@link #lastCaptureNanos} and
     * computed {@code nowNanos - lastCaptureNanos} directly. Since {@code System.nanoTime()} is
     * positive (JVM-uptime-relative on Linux/HotSpot), that subtraction overflows a signed
     * {@code long} and wraps to a large negative number — making the "not enough time has
     * elapsed" comparison true unconditionally, so the timer never captured anything, ever
     * (2026-09-24 Opus implementation review finding I1). {@link #hasCaptured} sidesteps the
     * arithmetic entirely instead of trying to pick a safer sentinel.
     */
    boolean captureIntervalElapsed(long nowNanos) {
        return !hasCaptured || nowNanos - lastCaptureNanos >= captureIntervalMs * 1_000_000L;
    }

    /**
     * Package-private — lets a test observe free-list exhaustion/recovery directly (e.g. that a
     * displaced or consumed {@link RenderWork}'s buffer comes back) without needing the display
     * thread running (2026-09-24 Opus implementation review §4).
     */
    int freeBufferCount() {
        return freeBuffers.size();
    }

    /**
     * Package-private — lets a test arm {@link #resetRequestedSeq} directly and drive {@link
     * #processRenderWork} with a {@link RenderWork} of a chosen {@code captureSeq}, exercising
     * the reset-consumption logic (2026-09-22 finding C2, refined as I4/I5) without needing
     * {@link #forceRefresh()}'s live-{@code Scene} capture path.
     */
    void armForcedReset(long requiredSeq) {
        resetRequestedSeq.set(requiredSeq);
    }

    /**
     * Logs a capture skipped for want of a free buffer — normal, brief backpressure at DEBUG,
     * but escalated once to WARN after {@link #BUFFER_STARVATION_WARN_THRESHOLD} consecutive
     * skips, since sustained starvation (a leaked buffer, or a genuinely stuck display thread)
     * would otherwise be silent at default log levels and look like a driver/panel problem
     * rather than an FX-thread one (2026-09-22 Opus review finding C4).
     */
    private void recordCaptureSkipped() {
        int count = consecutiveBufferStarvations.incrementAndGet();
        if (count == BUFFER_STARVATION_WARN_THRESHOLD) {
            LOG.warn("No free capture buffer for {} consecutive capture attempts — the display "
                    + "thread may be stuck, or a buffer was leaked", count);
        } else {
            LOG.debug("Capture skipped: no free buffer available");
        }
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
