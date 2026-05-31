// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.Pux4jContext;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.TouchCoordinateMapper;
import dev.pux4j.ui.core.TouchDriver;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.core.TouchPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

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

        Pux4jContext ctx = Pux4jContext.managed();
        EInkDisplayDriver display = null;
        TouchDriver touch = null;
        ReportWriter reportWriter = null;

        try {
            DisplayDriverFactory displayFactory = findFactory(DisplayDriverFactory.class, options.displayDriver, "DisplayDriverFactory");
            TouchDriverFactory touchFactory = findFactory(TouchDriverFactory.class, options.touchDriver, "TouchDriverFactory");

            String resolvedDisplay = displayFactory.name();
            String resolvedTouch = touchFactory.name();
            int touchI2cAddress = options.touchI2cAddress >= 0
                ? options.touchI2cAddress
                : (resolvedTouch.equals("gt1151q") ? 0x14 : 0x48);

            log.info("HardwareValidationTest: displayDriver={}, touchDriver={}, orientation={}",
                resolvedDisplay, resolvedTouch, options.orientation);

            DriverConfig config = createDriverConfig(options, touchI2cAddress);

            display = displayFactory.create(ctx, config);
            touch = touchFactory.create(ctx, config);

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
            ctx.close();
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

    private static DriverConfig createDriverConfig(Options options, int touchI2cAddress) {
        return DriverConfig.builder()
            .strProperty("orientation", options.orientation.name())
            .intProperty("dcPin", options.dcPin)
            .intProperty("rstPin", options.rstPin)
            .intProperty("busyPin", options.busyPin)
            .intProperty("touchI2cAddress", touchI2cAddress)
            .intProperty("touchRstPin", options.touchRstPin)
            .intProperty("touchIntPin", options.touchIntPin)
            .build();
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

}
