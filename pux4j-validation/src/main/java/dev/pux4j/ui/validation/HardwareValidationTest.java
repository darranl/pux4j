// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.TouchCoordinateMapper;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.core.TouchPoint;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Interactive 10-step hardware acceptance test.
 * Validates a paired EInkDisplayDriver and TouchDriver end-to-end.
 */
public final class HardwareValidationTest {

    private static final Logger log = LoggerFactory.getLogger(HardwareValidationTest.class);

    private static final int TOUCH_TOLERANCE_PX = 10;
    private static final Duration INSTRUCTION_HINT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FEEDBACK_HINT_TIMEOUT = Duration.ofMillis(1400);
    private static final Duration TOUCH_RELEASE_TIMEOUT = Duration.ofMillis(700);
    private static final Duration TOUCH_RELEASE_STABLE_PERIOD = Duration.ofMillis(220);
    private static final Duration TOUCH_ARM_TIMEOUT = Duration.ofMillis(900);
    private static final Duration TOUCH_ARM_STABLE_PERIOD = Duration.ofMillis(160);
    private static final Duration DISPLAY_SETTLE_AFTER_REFRESH = Duration.ofMillis(220);
    private static final Duration COMPLETION_MIN_DWELL = Duration.ofMillis(3500);
    private static final Duration COMPLETION_EXTRA_DWELL = Duration.ofMillis(5000);
    private static final Duration TOUCH_POLL_INTERVAL = Duration.ofMillis(25);

    private HardwareValidationTest() {}

    public static void main(String[] args) {
        var options = Options.parse(args);

        var pi4j = Pi4J.newAutoContext();
        EInkDisplayDriver display = null;
        TouchDriver touch = null;
        ReportWriter reportWriter = null;

        try {
            var displayFactory = findFactory(DisplayDriverFactory.class, options.displayDriver, "DisplayDriverFactory");
            var touchFactory = findFactory(TouchDriverFactory.class, options.touchDriver, "TouchDriverFactory");

            String resolvedDisplay = displayFactory.name();
            String resolvedTouch = touchFactory.name();
            int touchI2cAddress = options.touchI2cAddress >= 0
                ? options.touchI2cAddress
                : (resolvedTouch.equals("gt1151q") ? 0x14 : 0x48);

            log.info("HardwareValidationTest: displayDriver={}, touchDriver={}, orientation={}",
                resolvedDisplay, resolvedTouch, options.orientation);

            var config = createDriverConfig(pi4j, options, touchI2cAddress);

            display = displayFactory.create(config);
            touch = touchFactory.create(config);

            display.initialize();
            touch.initialize();

            int framebufferWidth = display.getWidth();
            int framebufferHeight = display.getHeight();

            int logicalWidth = logicalWidth(options.orientation, framebufferWidth, framebufferHeight);
            int logicalHeight = logicalHeight(options.orientation, framebufferWidth, framebufferHeight);

            var mapper = new TouchCoordinateMapper(
                logicalWidth,
                logicalHeight,
                options.touchNativeWidth,
                options.touchNativeHeight,
                options.flipX,
                options.flipY,
                options.swapAxes
            );

            reportWriter = new ReportWriter(resolvedDisplay, options.notes);
            var renderer = new Renderer(framebufferWidth, framebufferHeight, logicalWidth, logicalHeight, options.orientation);
            var touchPoller = new TouchPoller(touch, mapper);

            var allSteps = ValidationStepFactory.build(logicalWidth, logicalHeight);
            int startStep = Math.max(0, Math.min(options.startStep, allSteps.size() - 1));
            int selectedScenarioCount = Math.max(1, Math.min(options.scenarioCount, allSteps.size() - startStep));
            var steps = allSteps.subList(startStep, startStep + selectedScenarioCount);
            var results = new ArrayList<StepResult>(steps.size());
            int passedSoFar = 0;
            int failedSoFar = 0;

            log.info("Executing {} of {} available scenarios (use --scenario-count or --all-scenarios to change)",
                selectedScenarioCount, allSteps.size());

            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                int stepNumber = i + 1;
                // Step 1 uses a slow FULL refresh to establish a clean pixel baseline.
                // Subsequent steps use FAST — no visible flash, quick transitions.
                boolean slowFull = stepNumber == 1;
                log.info("Step {}: {}", stepNumber, step.instructionText);

                showInstructionPhase(display, renderer, touchPoller, stepNumber, steps.size(), passedSoFar, failedSoFar,
                    step.instructionText, slowFull);

                var challengeImage = renderer.renderChallenge(stepNumber, steps.size(), step.instructionText, step.items,
                    passedSoFar, failedSoFar);
                log.info("PHASE step={} challenge: rendering challenge screen (FAST)", stepNumber);
                // Challenge always uses FAST — the instruction-screen FULL refresh already
                // established a clean pixel baseline for step 1; all subsequent transitions
                // are fast with the custom LUT.
                CompletableFuture<Void> displayDone = renderer.writeFastAsync(display, challengeImage);
                log.info("PHASE step={} challenge: display update started; arming touch in parallel", stepNumber);
                TouchPoller.sleep(DISPLAY_SETTLE_AFTER_REFRESH);
                touchPoller.waitForRelease(TOUCH_RELEASE_TIMEOUT, TOUCH_RELEASE_STABLE_PERIOD);
                touchPoller.waitForIdle(TOUCH_ARM_TIMEOUT, TOUCH_ARM_STABLE_PERIOD);
                log.info("PHASE step={} challenge: armed and waiting for answer tap", stepNumber);

                var challengeStart = Instant.now();
                var touchPoint = touchPoller.waitForTap();
                var elapsed = Duration.between(challengeStart, Instant.now());
                var match = step.match(touchPoint, TOUCH_TOLERANCE_PX);
                boolean pass = match.isPresent() && match.get().name.equals(step.correctItem);

                log.info("Step {} result: {} (touch=({}, {}), matched={})",
                    stepNumber,
                    pass ? "PASS" : "FAIL",
                    touchPoint.x(),
                    touchPoint.y(),
                    match.map(item -> item.name).orElse("none"));

                displayDone.join(); // ensure display refresh completes before feedback SPI commands
                showFeedbackPhase(display, renderer, challengeImage, step, pass);

                var expected = step.expectedRegion();
                var result = new StepResult(
                    stepNumber,
                    step.instructionText,
                    pass,
                    expected,
                    touchPoint,
                    elapsed,
                    match.map(item -> item.name).orElse("none")
                );
                results.add(result);
                reportWriter.append(result);
                if (pass) {
                    passedSoFar++;
                } else {
                    failedSoFar++;
                }
            }

            long passed = results.stream().filter(StepResult::pass).count();
            int failed = results.size() - (int) passed;
            reportWriter.finish((int) passed, failed);

            var completionImage = renderer.renderCompletionScreen((int) passed, failed, reportWriter.outputPath());
            log.info("Rendering completion screen (pass={}, fail={}) — FULL/slow refresh (final cleanup)", passed, failed);
            renderer.writeFull(display, completionImage);
            log.info("PHASE completion: completion screen rendered; starting minimum dwell {} ms", COMPLETION_MIN_DWELL.toMillis());
            TouchPoller.sleep(COMPLETION_MIN_DWELL);
            log.info("PHASE completion: extending completion dwell {} ms", COMPLETION_EXTRA_DWELL.toMillis());
            TouchPoller.sleep(COMPLETION_EXTRA_DWELL);

            // Clear screen before sleep — WaveShare recommend not leaving pixels set to
            // avoid long-term burn-in from sustained pixel states.
            log.info("PHASE completion: clearing screen to all-white (FULL refresh) before sleep");
            int clearBytes = ((framebufferWidth + 7) / 8) * framebufferHeight;
            byte[] allWhite = new byte[clearBytes];
            Arrays.fill(allWhite, (byte) 0xFF);
            display.writeFrame(new MonochromeFrame(allWhite, RefreshMode.FULL)).join();

            log.info("Validation complete: passed={} failed={} report={}", passed, failed, reportWriter.outputPath());
        } catch (Exception e) {
            log.error("HardwareValidationTest failed", e);
            if (reportWriter != null) {
                try {
                    reportWriter.finishWithFailure(e);
                } catch (IOException ioException) {
                    log.error("Unable to write failure report", ioException);
                }
            }
            throw new RuntimeException(e);
        } finally {
            if (display != null) {
                try {
                    display.sleep();
                } catch (Exception e) {
                    log.warn("Display sleep failed", e);
                }
            }
            pi4j.shutdown();
        }
    }

    private static void showInstructionPhase(EInkDisplayDriver display,
                                             Renderer renderer,
                                             TouchPoller touchPoller,
                                             int step,
                                             int total,
                                             int passed,
                                             int failed,
                                             String instruction,
                                             boolean slowFull) {
        log.info("PHASE step={} instruction: preparing instruction screen ({})", step,
            slowFull ? "FULL/slow — baseline" : "FAST — no flash");
        touchPoller.waitForRelease(TOUCH_RELEASE_TIMEOUT, TOUCH_RELEASE_STABLE_PERIOD);
        touchPoller.waitForIdle(TOUCH_ARM_TIMEOUT, TOUCH_ARM_STABLE_PERIOD);
        var image = renderer.renderInstruction(step, total, instruction, passed, failed);
        if (slowFull) {
            renderer.writeFull(display, image);
        } else {
            renderer.writeFast(display, image);
        }
        log.info("PHASE step={} instruction: instruction rendered; waiting for proceed tap (hint timeout {} ms)",
            step, INSTRUCTION_HINT_TIMEOUT.toMillis());
        TouchPoller.sleep(DISPLAY_SETTLE_AFTER_REFRESH);
        waitForTapWithPrompt(display, renderer, touchPoller, image, INSTRUCTION_HINT_TIMEOUT, "Tap to advance");
        log.info("PHASE step={} instruction: proceed tap received", step);
    }

    private static void showFeedbackPhase(EInkDisplayDriver display,
                                          Renderer renderer,
                                          Canvas challengeImage,
                                          ValidationStep validationStep,
                                          boolean pass) {
        log.info("PHASE feedback: rendering {} overlay", pass ? "PASS" : "FAIL");
        renderer.drawChallengeFeedbackOverlay(challengeImage, validationStep.correctItemBounds(), pass);
        renderer.writePartial(display, challengeImage);
        log.info("PHASE feedback: overlay rendered; holding {} ms before next step", FEEDBACK_HINT_TIMEOUT.toMillis());
        TouchPoller.sleep(FEEDBACK_HINT_TIMEOUT);
    }

    private static void waitForTapWithPrompt(EInkDisplayDriver display,
                                             Renderer renderer,
                                             TouchPoller touchPoller,
                                             Canvas currentImage,
                                             Duration initialTimeout,
                                             String prompt) {
        touchPoller.waitForRelease(TOUCH_RELEASE_TIMEOUT, TOUCH_RELEASE_STABLE_PERIOD);
        touchPoller.waitForIdle(TOUCH_ARM_TIMEOUT, TOUCH_ARM_STABLE_PERIOD);
        log.info("PHASE instruction-wait: waiting for tap for up to {} ms", initialTimeout.toMillis());
        Optional<TouchPoint> touch = touchPoller.waitForTap(initialTimeout);
        if (touch.isPresent()) {
            log.info("PHASE instruction-wait: tap received before prompt");
            return;
        }

        log.info("PHASE instruction-wait: timeout reached; rendering prompt '{}'; waiting indefinitely", prompt);
        int promptHeight = Math.max(20, renderer.logicalHeight() / 8);
        int promptY = renderer.logicalHeight() - promptHeight;
        renderer.drawPrompt(currentImage, promptY, promptHeight, prompt);
        renderer.writePartial(display, currentImage);
        touchPoller.waitForRelease(TOUCH_RELEASE_TIMEOUT, TOUCH_RELEASE_STABLE_PERIOD);
        touchPoller.waitForIdle(TOUCH_ARM_TIMEOUT, TOUCH_ARM_STABLE_PERIOD);
        touchPoller.waitForTap();
        log.info("PHASE instruction-wait: tap received after prompt");
    }

    private static DriverConfig createDriverConfig(Context pi4j, Options options, int touchI2cAddress) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("orientation", options.orientation.name())
            .add("dcPin", options.dcPin)
            .add("rstPin", options.rstPin)
            .add("busyPin", options.busyPin)
            .add("touchI2cAddress", touchI2cAddress)
            .add("touchRstPin", options.touchRstPin)
            .add("touchIntPin", options.touchIntPin);

        return DriverConfig.ofHardware(pi4j, builder.build());
    }

    private static <T> T findFactory(Class<T> type, String name, String label) {
        var all = ServiceLoader.load(type)
            .stream()
            .map(ServiceLoader.Provider::get)
            .toList();
        if (name != null) {
            return all.stream()
                .filter(instance -> instanceName(instance).equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No " + label + " named '" + name + "' found via ServiceLoader. "
                    + "Available: " + all.stream().map(HardwareValidationTest::instanceName).toList()));
        }
        // Auto-select: for DisplayDriverFactory filter out the headless "png" stub.
        // In a native binary only one hardware driver module is compiled in.
        var candidates = (type == DisplayDriverFactory.class)
            ? all.stream().filter(i -> !instanceName(i).equals("png")).toList()
            : all;
        if (candidates.size() == 1) return candidates.get(0);
        throw new IllegalStateException(
            "No " + label + " name given; expected 1 provider but found " + candidates.size()
            + ": " + candidates.stream().map(HardwareValidationTest::instanceName).toList()
            + ". Specify with --display/--touch.");
    }

    private static String instanceName(Object instance) {
        if (instance instanceof DisplayDriverFactory f) return f.name();
        if (instance instanceof TouchDriverFactory f) return f.name();
        return instance.getClass().getSimpleName();
    }

    private static int logicalWidth(Orientation orientation, int framebufferWidth, int framebufferHeight) {
        return switch (orientation) {
            case PORTRAIT, PORTRAIT_INVERTED -> framebufferWidth;
            case LANDSCAPE, LANDSCAPE_INVERTED -> framebufferHeight;
        };
    }

    private static int logicalHeight(Orientation orientation, int framebufferWidth, int framebufferHeight) {
        return switch (orientation) {
            case PORTRAIT, PORTRAIT_INVERTED -> framebufferHeight;
            case LANDSCAPE, LANDSCAPE_INVERTED -> framebufferWidth;
        };
    }

    private record Options(
        String displayDriver,
        String touchDriver,
        Orientation orientation,
        int touchNativeWidth,
        int touchNativeHeight,
        boolean flipX,
        boolean flipY,
        boolean swapAxes,
        int dcPin,
        int rstPin,
        int busyPin,
        int touchI2cAddress,
        int touchRstPin,
        int touchIntPin,
        int scenarioCount,
        int startStep,
        String notes
    ) {
        private static Options parse(String[] args) {
            String displayDriver = null;
            String touchDriver = null;
            Orientation orientation = Orientation.LANDSCAPE;
            int touchNativeWidth = 4096;
            int touchNativeHeight = 4096;
            boolean flipX = false;
            boolean flipY = false;
            boolean swapAxes = false;
            int dcPin = 25;
            int rstPin = 17;
            int busyPin = 24;
            int touchI2cAddress = -1;
            int touchRstPin = 22;
            int touchIntPin = 27;
            int scenarioCount = Integer.MAX_VALUE;
            int startStep = 0;
            String notes = "";

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--display" -> displayDriver = requireValue(args, ++i, arg);
                    case "--touch" -> touchDriver = requireValue(args, ++i, arg);
                    case "--orientation" -> orientation = Orientation.valueOf(requireValue(args, ++i, arg).toUpperCase(Locale.ROOT));
                    case "--touch-native-width" -> touchNativeWidth = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--touch-native-height" -> touchNativeHeight = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--flip-x" -> flipX = true;
                    case "--flip-y" -> flipY = true;
                    case "--swap-axes" -> swapAxes = true;
                    case "--dc-pin" -> dcPin = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--rst-pin" -> rstPin = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--busy-pin" -> busyPin = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--touch-i2c-address" -> {
                        String value = requireValue(args, ++i, arg);
                        touchI2cAddress = value.startsWith("0x") ? Integer.decode(value) : Integer.parseInt(value);
                    }
                    case "--touch-rst-pin" -> touchRstPin = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--touch-int-pin" -> touchIntPin = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--scenario-count" -> scenarioCount = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--all-scenarios" -> scenarioCount = Integer.MAX_VALUE;
                    case "--start-step" -> startStep = Integer.parseInt(requireValue(args, ++i, arg)) - 1;
                    case "--notes" -> notes = requireValue(args, ++i, arg);
                    default -> {
                        if (!arg.startsWith("-")) {
                            if (displayDriver == null) displayDriver = arg;
                            else if (touchDriver == null) touchDriver = arg;
                            else throw new IllegalArgumentException("Unknown argument: " + arg);
                        } else {
                            throw new IllegalArgumentException("Unknown argument: " + arg);
                        }
                    }
                }
            }

            // displayDriver and touchDriver remain null → auto-selected from ServiceLoader later
            // touchI2cAddress remains -1 → derived from resolved touch driver name in main()

            return new Options(
                displayDriver,
                touchDriver,
                orientation,
                touchNativeWidth,
                touchNativeHeight,
                flipX,
                flipY,
                swapAxes,
                dcPin,
                rstPin,
                busyPin,
                touchI2cAddress,
                touchRstPin,
                touchIntPin,
                scenarioCount,
                startStep,
                notes
            );
        }

        private static String requireValue(String[] args, int idx, String option) {
            if (idx >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[idx];
        }
    }

    private static final class TouchPoller {
        private final TouchDriver touchDriver;
        private final TouchCoordinateMapper mapper;
        private boolean lastAnyDown;

        private TouchPoller(TouchDriver touchDriver, TouchCoordinateMapper mapper) {
            this.touchDriver = touchDriver;
            this.mapper = mapper;
        }

        private TouchPoint waitForTap() {
            while (true) {
                var touch = pollOnce();
                if (touch.isPresent()) {
                    return touch.get();
                }
                sleep(TOUCH_POLL_INTERVAL);
            }
        }

        private Optional<TouchPoint> waitForTap(Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                var touch = pollOnce();
                if (touch.isPresent()) {
                    return touch;
                }
                sleep(TOUCH_POLL_INTERVAL);
            }
            return Optional.empty();
        }

        private Optional<TouchPoint> pollOnce() {
            var points = touchDriver.readTouches();
            boolean anyDown = false;
            TouchPoint firstMappedDown = null;
            for (var point : points) {
                if (point.down()) {
                    anyDown = true;
                    if (firstMappedDown == null) {
                        TouchPoint mapped = mapper.map(point);
                        log.info("Touch raw=({}, {}) mapped=({}, {})", point.x(), point.y(), mapped.x(), mapped.y());
                        firstMappedDown = mapped;
                    }
                }
            }

            if (anyDown && !lastAnyDown && firstMappedDown != null) {
                lastAnyDown = true;
                return Optional.of(firstMappedDown);
            }

            lastAnyDown = anyDown;
            return Optional.empty();
        }

        private void waitForRelease(Duration timeout, Duration stableDuration) {
            waitForIdle(timeout, stableDuration);
            lastAnyDown = false;
        }

        private boolean waitForIdle(Duration timeout, Duration stableDuration) {
            Instant deadline = Instant.now().plus(timeout);
            Instant stableSince = null;

            while (Instant.now().isBefore(deadline)) {
                boolean anyDown = false;
                var points = touchDriver.readTouches();
                for (var point : points) {
                    if (point.down()) {
                        anyDown = true;
                        break;
                    }
                }

                if (anyDown) {
                    stableSince = null;
                } else if (stableSince == null) {
                    stableSince = Instant.now();
                } else if (Duration.between(stableSince, Instant.now()).compareTo(stableDuration) >= 0) {
                    return true;
                }

                sleep(TOUCH_POLL_INTERVAL);
            }
            return false;
        }

        private static void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Renderer {

        private final int framebufferWidth;
        private final int framebufferHeight;
        private final int logicalWidth;
        private final int logicalHeight;
        private final Orientation orientation;

        private Renderer(int framebufferWidth,
                         int framebufferHeight,
                         int logicalWidth,
                         int logicalHeight,
                         Orientation orientation) {
            this.framebufferWidth = framebufferWidth;
            this.framebufferHeight = framebufferHeight;
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
            this.orientation = orientation;
        }

        private int logicalWidth() {
            return logicalWidth;
        }

        private int logicalHeight() {
            return logicalHeight;
        }

        private Canvas blankCanvas() {
            return new Canvas(logicalWidth, logicalHeight, framebufferWidth, framebufferHeight, orientation);
        }

        private Canvas renderInstruction(int step, int total, String instruction, int passed, int failed) {
            var canvas = blankCanvas();
            drawHeader(canvas, "Step " + step + " / " + total, "Instruction", passed, failed);
            drawWrappedCentered(canvas, instruction, logicalHeight / 2 - 10, 20, Canvas.SCALE_NORMAL);
            return canvas;
        }

        private Canvas renderChallenge(int step,
                                       int total,
                                       String instruction,
                                       List<ChallengeItem> items,
                                       int passed,
                                       int failed) {
            var canvas = blankCanvas();
            drawHeader(canvas, "Step " + step + " / " + total, instruction, passed, failed);
            for (var item : items) {
                item.renderer.render(canvas, item.bounds);
            }
            return canvas;
        }

        private void drawChallengeFeedbackOverlay(Canvas canvas, Rect correctBounds, boolean pass) {
            int centerX = correctBounds.x + (correctBounds.width / 2);
            int topReserved = 34;
            int bottomReserved = 6;
            int availableAbove = Math.max(0, correctBounds.y - topReserved);
            int availableBelow = Math.max(0, (logicalHeight - bottomReserved) - (correctBounds.y + correctBounds.height));
            boolean placeArrowBelow = availableBelow >= availableAbove;

            int arrowTipY;
            int arrowHeadTopY;
            int arrowStartY;
            if (placeArrowBelow) {
                arrowTipY = Math.min(logicalHeight - 8, correctBounds.y + correctBounds.height + 2);
                int arrowHeadBottomY = Math.min(logicalHeight - 6, arrowTipY + 12);
                arrowHeadTopY = arrowTipY;
                arrowStartY = Math.min(logicalHeight - 6, arrowHeadBottomY + 16);
            } else {
                arrowTipY = Math.max(topReserved, correctBounds.y - 2);
                arrowHeadTopY = Math.max(topReserved - 6, arrowTipY - 12);
                arrowStartY = Math.max(18, arrowHeadTopY - 16);
            }
            int arrowHalf = 7;

            canvas.setBlack();
            if (placeArrowBelow) {
                canvas.fillPolygon(
                    new int[]{centerX - arrowHalf, centerX + arrowHalf, centerX},
                    new int[]{arrowTipY + 12, arrowTipY + 12, arrowTipY},
                    3);
                canvas.drawLine(centerX, arrowStartY, centerX, arrowTipY + 12);
            } else {
                canvas.fillPolygon(
                    new int[]{centerX - arrowHalf, centerX + arrowHalf, centerX},
                    new int[]{arrowHeadTopY, arrowHeadTopY, arrowTipY},
                    3);
                canvas.drawLine(centerX, arrowStartY, centerX, arrowHeadTopY);
            }

            int ringPad = 4;
            Rect ringRect = clampRect(new Rect(
                correctBounds.x - ringPad,
                correctBounds.y - ringPad,
                correctBounds.width + ringPad * 2,
                correctBounds.height + ringPad * 2
            ));
            canvas.drawRect(ringRect.x, ringRect.y, ringRect.width - 1, ringRect.height - 1);
        }

        private Canvas renderCompletionScreen(int passed, int failed, Path reportPath) {
            Optional<PngReader.PngImage> poster = loadResourceImage("icons/Pux.png");
            if (poster.isEmpty()) {
                return renderFallbackCompletion(passed, failed, reportPath);
            }

            var canvas = blankCanvas();
            drawHeader(canvas, "Validation Complete", "Pux", passed, failed);
            drawCenteredText(canvas, "COMPLETE", 44, Canvas.SCALE_NORMAL);
            PngReader.PngImage src = poster.get();

            int availableTop = 50;
            int availableBottom = logicalHeight - 28;
            int availableHeight = Math.max(20, availableBottom - availableTop);
            int availableWidth = logicalWidth - 8;

            double scale = Math.min((double) availableWidth / src.width(), (double) availableHeight / src.height());
            scale = Math.min(scale, 1.0);

            int drawW = Math.max(1, (int) Math.round(src.width() * scale));
            int drawH = Math.max(1, (int) Math.round(src.height() * scale));
            int drawX = (logicalWidth - drawW) / 2;
            int drawY = availableTop + ((availableHeight - drawH) / 2);

            int[] mono = PngReader.toHighContrastMonochrome(src, drawW, drawH);
            canvas.drawImage(mono, drawW, drawH, drawX, drawY);
            canvas.setBlack();
            canvas.drawRect(drawX, drawY, Math.max(0, drawW - 1), Math.max(0, drawH - 1));

            drawCenteredText(canvas, "Passed: " + passed + "   Failed: " + failed,
                logicalHeight - 12, Canvas.SCALE_SMALL);
            return canvas;
        }

        private Canvas renderFallbackCompletion(int passed, int failed, Path reportPath) {
            var canvas = blankCanvas();
            drawHeader(canvas, "Validation Complete", "Summary", passed, failed);
            drawCenteredText(canvas, "Passed: " + passed, logicalHeight / 2 - 10, Canvas.SCALE_NORMAL);
            drawCenteredText(canvas, "Failed: " + failed, logicalHeight / 2 + 10, Canvas.SCALE_NORMAL);
            drawWrappedCentered(canvas, "Report: " + reportPath.getFileName(),
                logicalHeight - 14, 14, Canvas.SCALE_SMALL);
            return canvas;
        }

        private Optional<PngReader.PngImage> loadResourceImage(String resourcePath) {
            var candidates = List.of(
                resourcePath,
                "/" + resourcePath,
                "icons/" + Path.of(resourcePath).getFileName(),
                "/icons/" + Path.of(resourcePath).getFileName()
            );
            for (var candidate : candidates) {
                try (InputStream stream = openResource(candidate)) {
                    if (stream == null) continue;
                    var image = PngReader.read(stream);
                    if (image.isPresent()) return image;
                } catch (IOException e) {
                    log.warn("Unable to load resource image {}", candidate, e);
                }
            }
            log.debug("Resource image not found: {}", resourcePath);
            return Optional.empty();
        }

        private InputStream openResource(String resourcePath) {
            InputStream stream = HardwareValidationTest.class.getResourceAsStream(resourcePath);
            if (stream != null) return stream;
            String stripped = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            return HardwareValidationTest.class.getClassLoader().getResourceAsStream(stripped);
        }

        private void drawPrompt(Canvas canvas, int y, int height, String prompt) {
            canvas.setWhite();
            canvas.fillRect(0, y, logicalWidth, height);
            canvas.setBlack();
            canvas.drawLine(0, y, logicalWidth - 1, y);
            drawCenteredText(canvas, prompt, y + (height / 2) + 4, Canvas.SCALE_SMALL);
        }

        private void writeFull(EInkDisplayDriver display, Canvas canvas) {
            writeFullAsync(display, canvas).join();
        }

        private CompletableFuture<Void> writeFullAsync(EInkDisplayDriver display, Canvas canvas) {
            return display.writeFrame(new MonochromeFrame(canvas.packMonochrome(), RefreshMode.FULL));
        }

        private void writeFast(EInkDisplayDriver display, Canvas canvas) {
            writeFastAsync(display, canvas).join();
        }

        private CompletableFuture<Void> writeFastAsync(EInkDisplayDriver display, Canvas canvas) {
            return display.writeFrame(new MonochromeFrame(canvas.packMonochrome(), RefreshMode.FAST));
        }

        // Writes the full packed frame using the partial waveform. Only pixels that
        // changed from the previous full frame will visibly update on the display.
        // The SSD1675A reference implementation always writes the complete framebuffer
        // for partial updates (it never sub-region-writes to 0x24); we follow that.
        private void writePartial(EInkDisplayDriver display, Canvas canvas) {
            display.writeFrame(new MonochromeFrame(canvas.packMonochrome(), RefreshMode.PARTIAL)).join();
        }

        private void drawHeader(Canvas canvas, String title, String subtitle, int passed, int failed) {
            canvas.setBlack();
            canvas.drawLine(0, 26, logicalWidth - 1, 26);
            drawCenteredText(canvas, title, 14, Canvas.SCALE_SMALL);
            drawCenteredText(canvas, subtitle, 24, Canvas.SCALE_SMALL);
            drawScoreBadge(canvas, passed, failed);
        }

        private void drawScoreBadge(Canvas canvas, int passed, int failed) {
            String text = "P:" + passed + "  F:" + failed;
            int scale = Canvas.SCALE_SMALL;
            int sw = Canvas.stringWidth(text, scale);
            int padX = 5;
            int padY = 2;
            int badgeX = 6;
            int badgeY = 3;
            int badgeW = sw + padX * 2;
            int badgeH = Canvas.FONT_H * scale + padY * 2;

            canvas.setWhite();
            canvas.fillRect(badgeX, badgeY, badgeW, badgeH);
            canvas.setBlack();
            canvas.drawRect(badgeX, badgeY, badgeW, badgeH);
            canvas.drawString(text, badgeX + padX, badgeY + padY, scale);
        }

        private void drawWrappedCentered(Canvas canvas, String text, int centerY, int lineHeight, int scale) {
            int maxWidth = logicalWidth - 12;
            var lines = wrap(text, maxWidth, scale);
            int baseY = centerY - ((lines.size() - 1) * lineHeight / 2);
            for (int i = 0; i < lines.size(); i++) {
                drawCenteredText(canvas, lines.get(i), baseY + i * lineHeight, scale);
            }
        }

        private List<String> wrap(String text, int maxWidth, int scale) {
            String[] words = text.split("\\s+");
            var lines = new ArrayList<String>();
            var current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (Canvas.stringWidth(candidate, scale) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    if (!current.isEmpty()) lines.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines;
        }

        // baselineY is where the bottom row of the 5×8 glyph cell sits,
        // matching the AWT drawString baseline convention used at all call sites.
        private void drawCenteredText(Canvas canvas, String text, int baselineY, int scale) {
            int x = Math.max(0, (logicalWidth - Canvas.stringWidth(text, scale)) / 2);
            canvas.setBlack();
            canvas.drawString(text, x, baselineY - Canvas.FONT_H * scale + 1, scale);
        }

        private Rect clampRect(Rect rect) {
            int x = Math.max(0, rect.x);
            int y = Math.max(0, rect.y);
            int maxW = logicalWidth - x;
            int maxH = logicalHeight - y;
            int w = Math.max(1, Math.min(rect.width, maxW));
            int h = Math.max(1, Math.min(rect.height, maxH));
            return new Rect(x, y, w, h);
        }
    }

    private record ValidationStep(String instructionText, String correctItem, List<ChallengeItem> items) {
        private Optional<ChallengeItem> match(TouchPoint point, int tolerance) {
            var strictMatches = new ArrayList<ChallengeItem>();
            for (var item : items) {
                if (item.bounds.contains(point.x(), point.y())) {
                    strictMatches.add(item);
                }
            }

            if (strictMatches.size() == 1) {
                return Optional.of(strictMatches.getFirst());
            }
            if (strictMatches.size() > 1) {
                return Optional.empty();
            }

            var expandedMatches = new ArrayList<ChallengeItem>();
            for (var item : items) {
                Rect expanded = new Rect(
                    item.bounds.x - tolerance,
                    item.bounds.y - tolerance,
                    item.bounds.width + tolerance * 2,
                    item.bounds.height + tolerance * 2
                );
                if (expanded.contains(point.x(), point.y())) {
                    expandedMatches.add(item);
                }
            }

            if (expandedMatches.size() == 1) {
                return Optional.of(expandedMatches.getFirst());
            }
            if (expandedMatches.size() > 1) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        private Optional<ChallengeItem> findByName(String name) {
            return items.stream().filter(item -> item.name.equals(name)).findFirst();
        }

        private Rect expectedRegion() {
            return findByName(correctItem)
                .map(item -> item.bounds)
                .orElseThrow(() -> new IllegalStateException("Missing correct item: " + correctItem));
        }

        private Rect correctItemBounds() {
            return expectedRegion();
        }
    }

    private static final class Rect {
        int x, y, width, height;

        Rect(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private record ChallengeItem(String name, Rect bounds, ItemRenderer renderer) {}

    @FunctionalInterface
    private interface ItemRenderer {
        void render(Canvas c, Rect bounds);
    }

    private static final class ValidationStepFactory {

        private static final Map<String, Optional<PngReader.PngImage>> PNG_CACHE = new HashMap<>();

        private static List<ValidationStep> build(int width, int height) {
            int margin = Math.max(8, Math.min(width, height) / 18);
            int shape = Math.max(18, Math.min(width, height) / 4);
            int small = Math.max(15, shape / 2);
            int medium = Math.max(24, shape - 4);
            int large = Math.max(36, shape + 8);

            // Picture steps: each of the three images gets an equal 1/3-width slot
            // spanning the full test area (below the 30 px header strip). Each image
            // is as large as will fit, centred within its slot.
            int headerH = 30;
            int iconSlotW = (width - 2 * margin) / 3;
            int icon = Math.min(iconSlotW - 4, height - headerH - margin);
            int iconY = headerH + (height - headerH - margin - icon) / 2;
            Rect iconLeft   = new Rect(margin + (iconSlotW - icon) / 2, iconY, icon, icon);
            Rect iconCenter = new Rect(margin + iconSlotW + (iconSlotW - icon) / 2, iconY, icon, icon);
            Rect iconRight  = new Rect(margin + 2 * iconSlotW + (iconSlotW - icon) / 2, iconY, icon, icon);

            Rect topLeft = new Rect(margin, margin + 24, shape, shape);
            Rect topRight = new Rect(width - margin - shape, margin + 24, shape, shape);
            Rect bottomLeft = new Rect(margin, height - margin - shape, shape, shape);
            Rect bottomRight = new Rect(width - margin - shape, height - margin - shape, shape, shape);
            Rect center = centered(width, height, shape, shape);
            Rect bottomCenter = new Rect((width - shape) / 2, height - margin - shape, shape, shape);
            Rect topCenter = new Rect((width - shape) / 2, margin + 24, shape, shape);

            Rect largeCenter = centered(width, height, large, large);
            Rect mediumLeft = new Rect(margin, height - margin - medium, medium, medium);
            Rect smallRight = new Rect(width - margin - small, margin + 28, small, small);

            Rect darkBg = new Rect(margin, height / 2 - shape / 2, shape + 10, shape + 10);
            Rect whiteBgTop = new Rect(width - margin - shape - 10, margin + 30, shape + 10, shape + 10);
            Rect whiteBgBottom = new Rect(width - margin - shape - 10, height - margin - shape - 10, shape + 10, shape + 10);

            Rect step10Small = new Rect((width / 2) - (small / 2), height / 2 - (small / 2), small, small);
            Rect step10Large = new Rect(margin, margin + 24, large, large);
            Rect step10Medium = new Rect(width - margin - medium, height - margin - medium, medium, medium);

            return List.of(
                new ValidationStep(
                    "Touch the CIRCLE",
                    "CIRCLE",
                    List.of(
                        item("CIRCLE", center, ValidationStepFactory::drawCircle),
                        item("SQUARE", topLeft, ValidationStepFactory::drawSquare),
                        item("TRIANGLE", bottomRight, ValidationStepFactory::drawTriangle)
                    )
                ),
                new ValidationStep(
                    "Touch the shape in the TOP-LEFT",
                    "TOP_LEFT_BOX",
                    List.of(
                        item("TOP_LEFT_BOX", topLeft, ValidationStepFactory::drawSquare),
                        item("TOP_RIGHT_CIRCLE", topRight, ValidationStepFactory::drawCircle),
                        item("BOTTOM_TRIANGLE", bottomCenter, ValidationStepFactory::drawTriangle)
                    )
                ),
                new ValidationStep(
                    "Touch the shape in the TOP-RIGHT",
                    "TOP_RIGHT_CIRCLE",
                    List.of(
                        item("TOP_RIGHT_CIRCLE", topRight, ValidationStepFactory::drawCircle),
                        item("BOTTOM_LEFT_SQUARE", bottomLeft, ValidationStepFactory::drawSquare),
                        item("CENTER_TRIANGLE", center, ValidationStepFactory::drawTriangle)
                    )
                ),
                new ValidationStep(
                    "Touch the shape in the BOTTOM-LEFT",
                    "BOTTOM_LEFT_TRIANGLE",
                    List.of(
                        item("BOTTOM_LEFT_TRIANGLE", bottomLeft, ValidationStepFactory::drawTriangle),
                        item("TOP_CENTER_CIRCLE", topCenter, ValidationStepFactory::drawCircle),
                        item("BOTTOM_RIGHT_SQUARE", bottomRight, ValidationStepFactory::drawSquare)
                    )
                ),
                new ValidationStep(
                    "Touch the shape in the BOTTOM-RIGHT",
                    "BOTTOM_RIGHT_BOX",
                    List.of(
                        item("BOTTOM_RIGHT_BOX", bottomRight, ValidationStepFactory::drawSquare),
                        item("TOP_LEFT_CIRCLE", topLeft, ValidationStepFactory::drawCircle),
                        item("CENTER_SQUARE", center, ValidationStepFactory::drawSquare)
                    )
                ),
                new ValidationStep(
                    "Touch the CAT",
                    "CAT",
                    List.of(
                        item("CAT", iconLeft, pngIcon("icons/cat.png", ValidationStepFactory::drawCatIcon)),
                        item("DOG", iconCenter, pngIcon("icons/dog.png", ValidationStepFactory::drawDogIcon)),
                        item("FISH", iconRight, pngIcon("icons/fish.png", ValidationStepFactory::drawFishIcon))
                    )
                ),
                new ValidationStep(
                    "Touch the ICE CREAM",
                    "ICE_CREAM",
                    List.of(
                        item("ICE_CREAM", iconLeft, pngIcon("icons/ice-cream.png", ValidationStepFactory::drawIceCreamIcon)),
                        item("BURGER", iconCenter, pngIcon("icons/burger.png", ValidationStepFactory::drawBurgerIcon)),
                        item("APPLE", iconRight, pngIcon("icons/apple.png", ValidationStepFactory::drawAppleIcon))
                    )
                ),
                new ValidationStep(
                    "Touch the LARGE circle",
                    "LARGE_CIRCLE",
                    List.of(
                        item("LARGE_CIRCLE", largeCenter, ValidationStepFactory::drawCircle),
                        item("MEDIUM_SQUARE", mediumLeft, ValidationStepFactory::drawSquare),
                        item("SMALL_CIRCLE", smallRight, ValidationStepFactory::drawCircle)
                    )
                ),
                new ValidationStep(
                    "Touch the item with a DARK background",
                    "DARK_BG",
                    List.of(
                        item("DARK_BG", darkBg, ValidationStepFactory::drawDarkBackgroundCircle),
                        item("WHITE_BG_TOP", whiteBgTop, ValidationStepFactory::drawWhiteBackgroundSquare),
                        item("WHITE_BG_BOTTOM", whiteBgBottom, ValidationStepFactory::drawWhiteBackgroundTriangle)
                    )
                ),
                new ValidationStep(
                    "Touch the SMALL shape",
                    "SMALL_BOX",
                    List.of(
                        item("SMALL_BOX", step10Small, ValidationStepFactory::drawSquare),
                        item("LARGE_BOX", step10Large, ValidationStepFactory::drawSquare),
                        item("MEDIUM_CIRCLE", step10Medium, ValidationStepFactory::drawCircle)
                    )
                )
            );
        }

        private static Rect centered(int width, int height, int w, int h) {
            return new Rect((width - w) / 2, (height - h) / 2, w, h);
        }

        private static ChallengeItem item(String name, Rect bounds, ItemRenderer renderer) {
            return new ChallengeItem(name, bounds, renderer);
        }

        private static ItemRenderer pngIcon(String resourcePath, ItemRenderer fallback) {
            return (c, bounds) -> {
                var image = loadIcon(resourcePath);
                if (image.isPresent()) {
                    int[] mono = PngReader.toMonochrome(image.get(), bounds.width, bounds.height);
                    c.drawImage(mono, bounds.width, bounds.height, bounds.x, bounds.y);
                } else {
                    fallback.render(c, bounds);
                }
            };
        }

        // Progressive halving to a 512×512 ceiling before caching — prevents huge
        // uncompressed images (e.g. 6000×7000 PNG) from exhausting heap on the Pi Zero.
        private static PngReader.PngImage scaleDownIcon(PngReader.PngImage source) {
            int maxDim = 512;
            int w = source.width();
            int h = source.height();
            if (w <= maxDim && h <= maxDim) return source;
            while (w > maxDim || h > maxDim) {
                w = Math.max(1, w / 2);
                h = Math.max(1, h / 2);
            }
            int[] scaled = PngReader.toMonochrome(source, w, h);
            return new PngReader.PngImage(scaled, w, h);
        }

        private static Optional<PngReader.PngImage> loadIcon(String resourcePath) {
            if (PNG_CACHE.containsKey(resourcePath)) {
                return PNG_CACHE.get(resourcePath);
            }

            // Class.getResourceAsStream with a leading '/' resolves from the module root,
            // which works correctly under --module-path. ClassLoader.getResourceAsStream
            // is not reliable for named module resources and is only tried as a fallback.
            String absPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
            InputStream stream = HardwareValidationTest.class.getResourceAsStream(absPath);
            if (stream == null) {
                String bare = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
                stream = HardwareValidationTest.class.getClassLoader().getResourceAsStream(bare);
            }

            try (InputStream s = stream) {
                Optional<PngReader.PngImage> loaded = s == null
                    ? Optional.empty()
                    : PngReader.read(s).map(ValidationStepFactory::scaleDownIcon);
                PNG_CACHE.put(resourcePath, loaded);
                if (loaded.isEmpty()) {
                    log.warn("PNG icon not found: {} (falling back to drawn shape)", resourcePath);
                }
                return loaded;
            } catch (IOException e) {
                log.warn("Failed to load PNG icon {}", resourcePath, e);
                PNG_CACHE.put(resourcePath, Optional.empty());
                return Optional.empty();
            }
        }

        private static void drawSquare(Canvas c, Rect r) {
            c.setBlack();
            c.fillRect(r.x, r.y, r.width, r.height);
        }

        private static void drawCircle(Canvas c, Rect r) {
            c.setBlack();
            c.fillOval(r.x, r.y, r.width, r.height);
        }

        private static void drawTriangle(Canvas c, Rect r) {
            c.setBlack();
            c.fillPolygon(
                new int[]{r.x + r.width / 2, r.x, r.x + r.width},
                new int[]{r.y, r.y + r.height, r.y + r.height},
                3);
        }

        private static void drawCatIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            var head = inset(r, 8, 12);
            c.setBlack();
            c.fillOval(head.x, head.y + 6, head.width, head.height - 10);
            c.fillPolygon(
                new int[]{head.x + 4, head.x + 10, head.x + 14},
                new int[]{head.y + 8, head.y, head.y + 8},
                3);
            c.fillPolygon(
                new int[]{head.x + head.width - 14, head.x + head.width - 10, head.x + head.width - 4},
                new int[]{head.y + 8, head.y, head.y + 8},
                3);
        }

        private static void drawDogIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            var head = inset(r, 8, 12);
            c.setBlack();
            c.fillOval(head.x + 4, head.y + 6, head.width - 8, head.height - 10);
            c.fillOval(head.x, head.y + 10, 8, 14);
            c.fillOval(head.x + head.width - 8, head.y + 10, 8, 14);
        }

        private static void drawFishIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            var body = inset(r, 8, 14);
            c.setBlack();
            c.fillOval(body.x, body.y + 4, body.width - 10, body.height - 8);
            c.fillPolygon(
                new int[]{body.x + body.width - 8, body.x + body.width + 2, body.x + body.width + 2},
                new int[]{body.y + body.height / 2, body.y + 2, body.y + body.height - 2},
                3);
        }

        private static void drawIceCreamIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            var scoop = inset(r, 10, 8);
            c.setBlack();
            c.fillOval(scoop.x, scoop.y, scoop.width, scoop.height / 2 + 4);
            c.fillPolygon(
                new int[]{r.x + r.width / 2, r.x + r.width / 2 - 10, r.x + r.width / 2 + 10},
                new int[]{r.y + r.height - 4, r.y + r.height / 2, r.y + r.height / 2},
                3);
        }

        private static void drawBurgerIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            int x = r.x + 6;
            int w = r.width - 12;
            c.setBlack();
            c.fillRoundRect(x, r.y + 10, w, 10);
            c.fillRect(x + 2, r.y + 22, w - 4, 8);
            c.fillRoundRect(x, r.y + 30, w, 10);
        }

        private static void drawAppleIcon(Canvas c, Rect r) {
            drawIconBadge(c, r);
            c.setBlack();
            c.fillOval(r.x + 10, r.y + 10, r.width - 20, r.height - 16);
            c.fillRect(r.x + r.width / 2 - 1, r.y + 4, 2, 8);
            c.fillPolygon(
                new int[]{r.x + r.width / 2 + 2, r.x + r.width / 2 + 12, r.x + r.width / 2 + 8},
                new int[]{r.y + 8, r.y + 4, r.y + 12},
                3);
        }

        private static void drawDarkBackgroundCircle(Canvas c, Rect r) {
            c.setBlack();
            c.fillRect(r.x, r.y, r.width, r.height);
            c.setWhite();
            c.fillOval(r.x + 5, r.y + 5, r.width - 10, r.height - 10);
            c.setBlack();
        }

        private static void drawWhiteBackgroundSquare(Canvas c, Rect r) {
            c.setBlack();
            c.drawRect(r.x, r.y, r.width, r.height);
            c.fillRect(r.x + 5, r.y + 5, r.width - 10, r.height - 10);
        }

        private static void drawWhiteBackgroundTriangle(Canvas c, Rect r) {
            c.setBlack();
            c.drawRect(r.x, r.y, r.width, r.height);
            c.fillPolygon(
                new int[]{r.x + r.width / 2, r.x + 6, r.x + r.width - 6},
                new int[]{r.y + 5, r.y + r.height - 5, r.y + r.height - 5},
                3);
        }

        private static void drawIconBadge(Canvas c, Rect r) {
            c.setBlack();
            c.drawRoundRect(r.x, r.y, r.width, r.height);
        }

        private static Rect inset(Rect r, int dx, int dy) {
            return new Rect(r.x + dx, r.y + dy, r.width - (2 * dx), r.height - (2 * dy));
        }
    }

    private record StepResult(
        int step,
        String title,
        boolean pass,
        Rect expectedRegion,
        TouchPoint touchPoint,
        Duration elapsed,
        String matchedItem
    ) {}

    private static final class ReportWriter {
        private final StringBuilder sb = new StringBuilder();
        private final Path outputPath;

        private ReportWriter(String driverName, String notes) throws IOException {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            outputPath = Path.of("validation-report-" + timestamp + ".txt");

            sb.append("Hardware Validation Test Report\n");
            sb.append("================================\n");
            sb.append("Date:   ").append(LocalDateTime.now()).append('\n');
            sb.append("Driver: ").append(driverName).append('\n');
            sb.append("Notes:  ").append(notes == null || notes.isBlank() ? "-" : notes).append("\n\n");

            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        }

        private void append(StepResult result) {
            sb.append("Step ").append(result.step()).append(": ").append(result.title()).append('\n');
            sb.append("  Result:          ").append(result.pass() ? "PASS" : "FAIL").append('\n');
            sb.append("  Expected region: (")
                .append(result.expectedRegion().x).append(", ")
                .append(result.expectedRegion().y).append(", ")
                .append(result.expectedRegion().width).append(", ")
                .append(result.expectedRegion().height).append(")\n");
            sb.append("  Touch received:  (")
                .append(result.touchPoint().x()).append(", ")
                .append(result.touchPoint().y()).append(")")
                .append(" after ").append(String.format(Locale.ROOT, "%.1f", result.elapsed().toMillis() / 1000.0)).append(" s\n");
            sb.append("  Matched item:    ").append(result.matchedItem()).append("\n\n");
        }

        private void finish(int passed, int failed) throws IOException {
            sb.append("Summary\n");
            sb.append("-------\n");
            sb.append("Passed:  ").append(passed).append(" / ").append(passed + failed).append('\n');
            sb.append("Failed:  ").append(failed).append(" / ").append(passed + failed).append('\n');
            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        }

        private void finishWithFailure(Exception failure) throws IOException {
            sb.append("Summary\n");
            sb.append("-------\n");
            sb.append("Run terminated with failure: ").append(failure.getClass().getSimpleName())
                .append(" - ").append(failure.getMessage() == null ? "<no message>" : failure.getMessage()).append('\n');
            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        }

        private Path outputPath() {
            return outputPath;
        }
    }

    // Pure-Java pixel buffer for rendering to an eInk display without AWT.
    // Colour state (black/white) is set before drawing calls, matching the
    // AWT Graphics2D.setColor() pattern used throughout this class.
    private static final class Canvas {

        static final int SCALE_SMALL = 1;
        static final int SCALE_NORMAL = 2;
        static final int FONT_H = 8;
        static final int FONT_W = 5;

        // Adafruit GFX 5×8 classic bitmap font — 96 printable ASCII characters
        // (0x20 space through 0x7E tilde), 5 bytes per character.
        // Each byte is a column: bit 0 = top pixel, bit 7 = bottom pixel.
        // Source: Adafruit-GFX-Library/glcdfont.c
        // Copyright (c) 2012 Adafruit Industries. All rights reserved.
        // SPDX-License-Identifier: BSD-2-Clause
        // https://github.com/adafruit/Adafruit-GFX-Library/blob/master/glcdfont.c
        private static final byte[] FONT = {
            0x00, 0x00, 0x00, 0x00, 0x00, // ' '
            0x3E, 0x5B, 0x4F, 0x5B, 0x3E, // '!'
            0x3E, 0x6B, 0x4F, 0x6B, 0x3E, // '"'
            0x36, 0x7F, 0x36, 0x7F, 0x36, // '#'
            0x24, 0x2A, 0x7F, 0x2A, 0x12, // '$'
            0x23, 0x13, 0x08, 0x64, 0x62, // '%'
            0x36, 0x49, 0x55, 0x22, 0x50, // '&'
            0x00, 0x05, 0x03, 0x00, 0x00, // '''
            0x00, 0x1C, 0x22, 0x41, 0x00, // '('
            0x00, 0x41, 0x22, 0x1C, 0x00, // ')'
            0x14, 0x08, 0x3E, 0x08, 0x14, // '*'
            0x08, 0x08, 0x3E, 0x08, 0x08, // '+'
            0x00, 0x50, 0x30, 0x00, 0x00, // ','
            0x08, 0x08, 0x08, 0x08, 0x08, // '-'
            0x00, 0x60, 0x60, 0x00, 0x00, // '.'
            0x20, 0x10, 0x08, 0x04, 0x02, // '/'
            0x3E, 0x51, 0x49, 0x45, 0x3E, // '0'
            0x00, 0x42, 0x7F, 0x40, 0x00, // '1'
            0x42, 0x61, 0x51, 0x49, 0x46, // '2'
            0x21, 0x41, 0x45, 0x4B, 0x31, // '3'
            0x18, 0x14, 0x12, 0x7F, 0x10, // '4'
            0x27, 0x45, 0x45, 0x45, 0x39, // '5'
            0x3C, 0x4A, 0x49, 0x49, 0x30, // '6'
            0x01, 0x71, 0x09, 0x05, 0x03, // '7'
            0x36, 0x49, 0x49, 0x49, 0x36, // '8'
            0x06, 0x49, 0x49, 0x29, 0x1E, // '9'
            0x00, 0x36, 0x36, 0x00, 0x00, // ':'
            0x00, 0x56, 0x36, 0x00, 0x00, // ';'
            0x08, 0x14, 0x22, 0x41, 0x00, // '<'
            0x14, 0x14, 0x14, 0x14, 0x14, // '='
            0x00, 0x41, 0x22, 0x14, 0x08, // '>'
            0x02, 0x01, 0x51, 0x09, 0x06, // '?'
            0x32, 0x49, 0x79, 0x41, 0x3E, // '@'
            0x7E, 0x11, 0x11, 0x11, 0x7E, // 'A'
            0x7F, 0x49, 0x49, 0x49, 0x36, // 'B'
            0x3E, 0x41, 0x41, 0x41, 0x22, // 'C'
            0x7F, 0x41, 0x41, 0x22, 0x1C, // 'D'
            0x7F, 0x49, 0x49, 0x49, 0x41, // 'E'
            0x7F, 0x09, 0x09, 0x09, 0x01, // 'F'
            0x3E, 0x41, 0x49, 0x49, 0x7A, // 'G'
            0x7F, 0x08, 0x08, 0x08, 0x7F, // 'H'
            0x00, 0x41, 0x7F, 0x41, 0x00, // 'I'
            0x20, 0x40, 0x41, 0x3F, 0x01, // 'J'
            0x7F, 0x08, 0x14, 0x22, 0x41, // 'K'
            0x7F, 0x40, 0x40, 0x40, 0x40, // 'L'
            0x7F, 0x02, 0x0C, 0x02, 0x7F, // 'M'
            0x7F, 0x04, 0x08, 0x10, 0x7F, // 'N'
            0x3E, 0x41, 0x41, 0x41, 0x3E, // 'O'
            0x7F, 0x09, 0x09, 0x09, 0x06, // 'P'
            0x3E, 0x41, 0x51, 0x21, 0x5E, // 'Q'
            0x7F, 0x09, 0x19, 0x29, 0x46, // 'R'
            0x46, 0x49, 0x49, 0x49, 0x31, // 'S'
            0x01, 0x01, 0x7F, 0x01, 0x01, // 'T'
            0x3F, 0x40, 0x40, 0x40, 0x3F, // 'U'
            0x1F, 0x20, 0x40, 0x20, 0x1F, // 'V'
            0x3F, 0x40, 0x38, 0x40, 0x3F, // 'W'
            0x63, 0x14, 0x08, 0x14, 0x63, // 'X'
            0x07, 0x08, 0x70, 0x08, 0x07, // 'Y'
            0x61, 0x51, 0x49, 0x45, 0x43, // 'Z'
            0x00, 0x7F, 0x41, 0x41, 0x00, // '['
            0x02, 0x04, 0x08, 0x10, 0x20, // '\'
            0x00, 0x41, 0x41, 0x7F, 0x00, // ']'
            0x04, 0x02, 0x01, 0x02, 0x04, // '^'
            0x40, 0x40, 0x40, 0x40, 0x40, // '_'
            0x00, 0x01, 0x02, 0x04, 0x00, // '`'
            0x20, 0x54, 0x54, 0x54, 0x78, // 'a'
            0x7F, 0x48, 0x44, 0x44, 0x38, // 'b'
            0x38, 0x44, 0x44, 0x44, 0x20, // 'c'
            0x38, 0x44, 0x44, 0x48, 0x7F, // 'd'
            0x38, 0x54, 0x54, 0x54, 0x18, // 'e'
            0x08, 0x7E, 0x09, 0x01, 0x02, // 'f'
            0x0C, 0x52, 0x52, 0x52, 0x3E, // 'g'
            0x7F, 0x08, 0x04, 0x04, 0x78, // 'h'
            0x00, 0x44, 0x7D, 0x40, 0x00, // 'i'
            0x20, 0x40, 0x44, 0x3D, 0x00, // 'j'
            0x7F, 0x10, 0x28, 0x44, 0x00, // 'k'
            0x00, 0x41, 0x7F, 0x40, 0x00, // 'l'
            0x7C, 0x04, 0x18, 0x04, 0x78, // 'm'
            0x7C, 0x08, 0x04, 0x04, 0x78, // 'n'
            0x38, 0x44, 0x44, 0x44, 0x38, // 'o'
            0x7C, 0x14, 0x14, 0x14, 0x08, // 'p'
            0x08, 0x14, 0x14, 0x18, 0x7C, // 'q'
            0x7C, 0x08, 0x04, 0x04, 0x08, // 'r'
            0x48, 0x54, 0x54, 0x54, 0x20, // 's'
            0x04, 0x3F, 0x44, 0x40, 0x20, // 't'
            0x3C, 0x40, 0x40, 0x20, 0x7C, // 'u'
            0x1C, 0x20, 0x40, 0x20, 0x1C, // 'v'
            0x3C, 0x40, 0x30, 0x40, 0x3C, // 'w'
            0x44, 0x28, 0x10, 0x28, 0x44, // 'x'
            0x0C, 0x50, 0x50, 0x50, 0x3C, // 'y'
            0x44, 0x64, 0x54, 0x4C, 0x44, // 'z'
            0x00, 0x08, 0x36, 0x41, 0x00, // '{'
            0x00, 0x00, 0x7F, 0x00, 0x00, // '|'
            0x00, 0x41, 0x36, 0x08, 0x00, // '}'
            0x10, 0x08, 0x08, 0x10, 0x08, // '~'
        };

        private final int logicalW;
        private final int logicalH;
        private final int fbW;
        private final int fbH;
        private final Orientation orientation;
        private final int[] pixels;
        private int colour = 0xFF000000; // black

        Canvas(int logicalW, int logicalH, int fbW, int fbH, Orientation orientation) {
            this.logicalW = logicalW;
            this.logicalH = logicalH;
            this.fbW = fbW;
            this.fbH = fbH;
            this.orientation = orientation;
            this.pixels = new int[logicalW * logicalH];
            Arrays.fill(pixels, 0xFFFFFFFF); // white background
        }

        void setBlack() { colour = 0xFF000000; }
        void setWhite() { colour = 0xFFFFFFFF; }

        static int stringWidth(String text, int scale) {
            return text.length() * (FONT_W + 1) * scale;
        }

        // topY is the top of the glyph cell (bit-0 row of the font data).
        void drawString(String text, int x, int topY, int scale) {
            int cx = x;
            for (int i = 0; i < text.length(); i++) {
                int ch = text.charAt(i);
                if (ch < 0x20 || ch > 0x7E) ch = 0x20;
                int base = (ch - 0x20) * 5;
                for (int col = 0; col < FONT_W; col++) {
                    int bits = FONT[base + col] & 0xFF;
                    for (int row = 0; row < FONT_H; row++) {
                        if ((bits & (1 << row)) != 0) {
                            for (int sy = 0; sy < scale; sy++) {
                                for (int sx = 0; sx < scale; sx++) {
                                    setPixel(cx + col * scale + sx, topY + row * scale + sy);
                                }
                            }
                        }
                    }
                }
                cx += (FONT_W + 1) * scale; // 1px inter-glyph gap
            }
        }

        void drawLine(int x0, int y0, int x1, int y1) {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;
            while (true) {
                setPixel(x0, y0);
                if (x0 == x1 && y0 == y1) break;
                int e2 = err * 2;
                if (e2 > -dy) { err -= dy; x0 += sx; }
                if (e2 < dx) { err += dx; y0 += sy; }
            }
        }

        // AWT drawRect semantics: inclusive [x..x+w] × [y..y+h]
        void drawRect(int x, int y, int w, int h) {
            drawLine(x, y, x + w, y);
            drawLine(x + w, y, x + w, y + h);
            drawLine(x + w, y + h, x, y + h);
            drawLine(x, y + h, x, y);
        }

        // drawRoundRect without AWT arcs — square corners are fine on eInk.
        void drawRoundRect(int x, int y, int w, int h) {
            drawRect(x, y, w, h);
        }

        // AWT fillRect semantics: exclusive [x..x+w) × [y..y+h)
        void fillRect(int x, int y, int w, int h) {
            for (int py = y; py < y + h; py++) {
                for (int px = x; px < x + w; px++) {
                    setPixel(px, py);
                }
            }
        }

        // fillRoundRect — square corners acceptable on eInk.
        void fillRoundRect(int x, int y, int w, int h) {
            fillRect(x, y, w, h);
        }

        // fillOval using scan-line approach; (x,y,w,h) is the bounding box.
        void fillOval(int x, int y, int w, int h) {
            double rx = w / 2.0;
            double ry = h / 2.0;
            double cx = x + rx;
            double cy = y + ry;
            for (int py = y; py < y + h; py++) {
                double dy = (py + 0.5 - cy) / ry;
                double span = rx * Math.sqrt(Math.max(0.0, 1.0 - dy * dy));
                int x0 = (int) Math.ceil(cx - span);
                int x1 = (int) Math.floor(cx + span);
                for (int px = x0; px <= x1; px++) {
                    setPixel(px, py);
                }
            }
        }

        // Fill a polygon defined by n vertices using scan-line rasterisation.
        void fillPolygon(int[] xs, int[] ys, int n) {
            if (n < 3) return;
            int minY = ys[0];
            int maxY = ys[0];
            for (int i = 1; i < n; i++) {
                minY = Math.min(minY, ys[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            var intersections = new ArrayList<Integer>();
            for (int py = minY; py <= maxY; py++) {
                intersections.clear();
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    int y0 = ys[i], y1 = ys[j];
                    if ((y0 <= py && y1 > py) || (y1 <= py && y0 > py)) {
                        int ix = xs[i] + (py - y0) * (xs[j] - xs[i]) / (y1 - y0);
                        intersections.add(ix);
                    }
                }
                intersections.sort(Integer::compare);
                for (int k = 0; k + 1 < intersections.size(); k += 2) {
                    int x0 = intersections.get(k);
                    int x1 = intersections.get(k + 1);
                    for (int px = x0; px <= x1; px++) {
                        setPixel(px, py);
                    }
                }
            }
        }

        // Blit a pre-rendered monochrome ARGB pixel array at position (dx, dy).
        void drawImage(int[] argb, int w, int h, int dx, int dy) {
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    int c = argb[py * w + px];
                    int r = (c >>> 16) & 0xFF;
                    int g = (c >>> 8) & 0xFF;
                    int b = c & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    if (lum < 128) {
                        int savedColour = colour;
                        colour = 0xFF000000;
                        setPixel(dx + px, dy + py);
                        colour = savedColour;
                    }
                }
            }
        }

        // Pack the logical pixel buffer into a packed 1-bit-per-pixel framebuffer byte
        // array, applying the orientation mapping from logical (x,y) to framebuffer (fx,fy).
        // White pixels → bit 1; black pixels → bit 0 (eInk convention).
        byte[] packMonochrome() {
            int fbRowBytes = (fbW + 7) / 8;
            byte[] out = new byte[fbRowBytes * fbH];
            Arrays.fill(out, (byte) 0xFF);
            for (int y = 0; y < logicalH; y++) {
                for (int x = 0; x < logicalW; x++) {
                    int rgb = pixels[y * logicalW + x];
                    int r = (rgb >>> 16) & 0xFF;
                    int g = (rgb >>> 8) & 0xFF;
                    int bv = rgb & 0xFF;
                    int lum = (r * 299 + g * 587 + bv * 114) / 1000;
                    if (lum < 128) {
                        int[] mapped = mapToFramebuffer(x, y);
                        int fx = mapped[0];
                        int fy = mapped[1];
                        int idx = fy * fbRowBytes + (fx / 8);
                        int bit = 7 - (fx % 8);
                        out[idx] = (byte) (out[idx] & ~(1 << bit));
                    }
                }
            }
            return out;
        }

        private void setPixel(int x, int y) {
            if (x >= 0 && x < logicalW && y >= 0 && y < logicalH) {
                pixels[y * logicalW + x] = colour;
            }
        }

        private int[] mapToFramebuffer(int lx, int ly) {
            return switch (orientation) {
                case PORTRAIT -> new int[]{lx, ly};
                case LANDSCAPE -> new int[]{ly, fbH - 1 - lx};
                case PORTRAIT_INVERTED -> new int[]{fbW - 1 - lx, fbH - 1 - ly};
                case LANDSCAPE_INVERTED -> new int[]{fbW - 1 - ly, lx};
            };
        }
    }

    // Minimal PNG decoder using java.util.zip.Inflater — no AWT, safe for GraalVM CE native image.
    private static final class PngReader {

        record PngImage(int[] pixels, int width, int height) {}

        static Optional<PngImage> read(InputStream in) throws IOException {
            var buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            byte[] data = buf.toByteArray();

            if (data.length < 8) return Optional.empty();

            // Verify PNG signature: 137 80 78 71 13 10 26 10
            long sig = 0x89504E470D0A1A0AL;
            long actual = 0;
            for (int i = 0; i < 8; i++) actual = (actual << 8) | (data[i] & 0xFF);
            if (actual != sig) return Optional.empty();

            int width = 0, height = 0, bitDepth = 0, colorType = 0;
            var idatBuf = new ByteArrayOutputStream();
            int pos = 8;

            while (pos + 12 <= data.length) {
                int len = readInt(data, pos);
                int type = readInt(data, pos + 4);
                pos += 8;
                if (type == 0x49484452) { // IHDR
                    width = readInt(data, pos);
                    height = readInt(data, pos + 4);
                    bitDepth = data[pos + 8] & 0xFF;
                    colorType = data[pos + 9] & 0xFF;
                } else if (type == 0x49444154) { // IDAT
                    idatBuf.write(data, pos, len);
                } else if (type == 0x49454E44) { // IEND
                    break;
                }
                pos += len + 4; // data + CRC
            }

            if (bitDepth != 8) {
                log.warn("PngReader: unsupported bit depth {}", bitDepth);
                return Optional.empty();
            }
            int channels = switch (colorType) {
                case 2 -> 3;  // RGB
                case 6 -> 4;  // RGBA
                default -> {
                    log.warn("PngReader: unsupported color type {}", colorType);
                    yield -1;
                }
            };
            if (channels == -1) return Optional.empty();

            byte[] compressed = idatBuf.toByteArray();
            int stride = width * channels + 1; // +1 for filter byte
            byte[] raw = new byte[stride * height];
            try {
                var inflater = new Inflater();
                inflater.setInput(compressed);
                inflater.inflate(raw);
                inflater.end();
            } catch (DataFormatException e) {
                log.warn("PngReader: inflate failed", e);
                return Optional.empty();
            }

            // Reconstruct with PNG filters (RFC 2083 section 6.3)
            byte[] prior = new byte[stride];
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                int filter = raw[rowStart] & 0xFF;
                for (int x = 1; x < stride; x++) {
                    int i = rowStart + x;
                    int a = x > channels ? (raw[i - channels] & 0xFF) : 0;
                    int b = prior[x] & 0xFF;
                    int c = x > channels ? (prior[x - channels] & 0xFF) : 0;
                    int orig = raw[i] & 0xFF;
                    raw[i] = (byte) switch (filter) {
                        case 0 -> orig;
                        case 1 -> orig + a;
                        case 2 -> orig + b;
                        case 3 -> orig + (a + b) / 2;
                        case 4 -> orig + paeth(a, b, c);
                        default -> orig;
                    };
                }
                System.arraycopy(raw, rowStart, prior, 0, stride);
                for (int x = 0; x < width; x++) {
                    int base = rowStart + 1 + x * channels;
                    int rv = raw[base] & 0xFF;
                    int gv = raw[base + 1] & 0xFF;
                    int bv = raw[base + 2] & 0xFF;
                    int av = channels == 4 ? (raw[base + 3] & 0xFF) : 255;
                    pixels[y * width + x] = (av << 24) | (rv << 16) | (gv << 8) | bv;
                }
            }
            return Optional.of(new PngImage(pixels, width, height));
        }

        // Scale src to (dstW × dstH) using nearest-neighbour and threshold to monochrome.
        // Transparent pixels (alpha ≤ 20) → white; luminance ≥ 190 → white; else black.
        static int[] toMonochrome(PngImage src, int dstW, int dstH) {
            int[] out = new int[dstW * dstH];
            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * src.height() / dstH;
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = dx * src.width() / dstW;
                    int argb = src.pixels()[sy * src.width() + sx];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    out[dy * dstW + dx] = (a > 20 && lum < 190) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return out;
        }

        // Same as toMonochrome but treats a lower luminance threshold (128) for stark
        // high-contrast rendering of the completion screen poster image.
        static int[] toHighContrastMonochrome(PngImage src, int dstW, int dstH) {
            int[] out = new int[dstW * dstH];
            for (int dy = 0; dy < dstH; dy++) {
                int sy = dy * src.height() / dstH;
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = dx * src.width() / dstW;
                    int argb = src.pixels()[sy * src.width() + sx];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    out[dy * dstW + dx] = (a > 20 && lum < 128) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return out;
        }

        private static int readInt(byte[] data, int off) {
            return ((data[off] & 0xFF) << 24)
                | ((data[off + 1] & 0xFF) << 16)
                | ((data[off + 2] & 0xFF) << 8)
                | (data[off + 3] & 0xFF);
        }

        private static int paeth(int a, int b, int c) {
            int p = a + b - c;
            int pa = Math.abs(p - a);
            int pb = Math.abs(p - b);
            int pc = Math.abs(p - c);
            if (pa <= pb && pa <= pc) return a;
            if (pb <= pc) return b;
            return c;
        }
    }
}
