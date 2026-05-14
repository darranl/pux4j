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
import javax.imageio.ImageIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
        log.info("HardwareValidationTest: displayDriver={}, touchDriver={}, orientation={}",
            options.displayDriver, options.touchDriver, options.orientation);

        var pi4j = Pi4J.newAutoContext();
        EInkDisplayDriver display = null;
        TouchDriver touch = null;
        ReportWriter reportWriter = null;

        try {
            var config = createDriverConfig(pi4j, options);
            var displayFactory = findFactory(DisplayDriverFactory.class, options.displayDriver, "DisplayDriverFactory");
            var touchFactory = findFactory(TouchDriverFactory.class, options.touchDriver, "TouchDriverFactory");

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

            reportWriter = new ReportWriter(options.displayDriver, options.notes);
            var renderer = new Renderer(framebufferWidth, framebufferHeight, logicalWidth, logicalHeight, options.orientation);
            var touchPoller = new TouchPoller(touch, mapper);

            var allSteps = ValidationStepFactory.build(logicalWidth, logicalHeight);
            int selectedScenarioCount = Math.max(1, Math.min(options.scenarioCount, allSteps.size()));
            var steps = allSteps.subList(0, selectedScenarioCount);
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
                displayDone.join();
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
            int clearBytes = (framebufferWidth / 8) * framebufferHeight;
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
                                          BufferedImage challengeImage,
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
                                             BufferedImage currentImage,
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

    private static DriverConfig createDriverConfig(Context pi4j, Options options) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("orientation", options.orientation.name())
            .add("dcPin", options.dcPin)
            .add("rstPin", options.rstPin)
            .add("busyPin", options.busyPin)
            .add("touchI2cAddress", options.touchI2cAddress)
            .add("touchRstPin", options.touchRstPin)
            .add("touchIntPin", options.touchIntPin);

        return DriverConfig.ofHardware(pi4j, builder.build());
    }

    private static <T> T findFactory(Class<T> type, String name, String label) {
        return ServiceLoader.load(type)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(instance -> {
                if (instance instanceof DisplayDriverFactory displayFactory) {
                    return displayFactory.name().equals(name);
                }
                if (instance instanceof TouchDriverFactory touchFactory) {
                    return touchFactory.name().equals(name);
                }
                return false;
            })
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No " + label + " named '" + name + "' found via ServiceLoader"));
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
        String notes
    ) {
        private static Options parse(String[] args) {
            String displayDriver = "ssd1675a";
            String touchDriver = "icnt86x";
            Orientation orientation = Orientation.LANDSCAPE;
            int touchNativeWidth = 4096;
            int touchNativeHeight = 4096;
            boolean flipX = false;
            boolean flipY = false;
            boolean swapAxes = false;
            int dcPin = 25;
            int rstPin = 17;
            int busyPin = 24;
            int touchI2cAddress = 0x48;
            int touchRstPin = 22;
            int touchIntPin = 27;
            int scenarioCount = Integer.MAX_VALUE;
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
                    case "--notes" -> notes = requireValue(args, ++i, arg);
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

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
        private static final Color WHITE = new Color(255, 255, 255);
        private static final Color BLACK = new Color(0, 0, 0);

        private final int framebufferWidth;
        private final int framebufferHeight;
        private final int logicalWidth;
        private final int logicalHeight;
        private final Orientation orientation;
        private final int framebufferRowBytes;

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
            this.framebufferRowBytes = framebufferWidth / 8;
        }

        private int logicalWidth() {
            return logicalWidth;
        }

        private int logicalHeight() {
            return logicalHeight;
        }

        private BufferedImage renderInstruction(int step, int total, String instruction, int passed, int failed) {
            var image = blankCanvas();
            var g = graphics(image);
            try {
                drawHeader(g, "Step " + step + " / " + total, "Instruction", passed, failed);
                drawWrappedCentered(g, instruction, logicalHeight / 2 - 10, 20, true);
                // Bottom strip left blank so the partial-refresh prompt has a clean
                // white baseline — avoids bidirectional pixel transitions in the LUT.
            } finally {
                g.dispose();
            }
            return image;
        }

        private BufferedImage renderChallenge(int step,
                                              int total,
                                              String instruction,
                                              List<ChallengeItem> items,
                                              int passed,
                                              int failed) {
            var image = blankCanvas();
            var g = graphics(image);
            try {
                drawHeader(g, "Step " + step + " / " + total, instruction, passed, failed);
                for (var item : items) {
                    item.renderer.render(g, item.bounds);
                }
            } finally {
                g.dispose();
            }
            return image;
        }

        private List<Rectangle> drawChallengeFeedbackOverlay(BufferedImage challengeImage,
                                                             Rectangle correctBounds,
                                                             boolean pass) {
            var g = graphics(challengeImage);
            try {
                g.setStroke(new BasicStroke(2f));
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

                var arrowHead = placeArrowBelow
                    ? new Polygon(
                        new int[]{centerX - arrowHalf, centerX + arrowHalf, centerX},
                        new int[]{arrowTipY + 12, arrowTipY + 12, arrowTipY},
                        3
                    )
                    : new Polygon(
                        new int[]{centerX - arrowHalf, centerX + arrowHalf, centerX},
                        new int[]{arrowHeadTopY, arrowHeadTopY, arrowTipY},
                        3
                    );
                g.setColor(BLACK);
                g.fillPolygon(arrowHead);
                if (placeArrowBelow) {
                    g.drawLine(centerX, arrowStartY, centerX, arrowTipY + 12);
                } else {
                    g.drawLine(centerX, arrowStartY, centerX, arrowHeadTopY);
                }

                int ringPad = 4;
                Rectangle ringRect = clampRect(new Rectangle(
                    correctBounds.x - ringPad,
                    correctBounds.y - ringPad,
                    correctBounds.width + ringPad * 2,
                    correctBounds.height + ringPad * 2
                ));
                g.drawRect(ringRect.x, ringRect.y, ringRect.width - 1, ringRect.height - 1);

                var overlays = new ArrayList<Rectangle>(1);
                int markerX = Math.max(0, ringRect.x - 10);
                int markerY = Math.max(0, Math.min(arrowStartY, ringRect.y) - 2);
                int markerW = Math.min(logicalWidth - markerX, ringRect.width + 20);
                int markerBottom = Math.max(ringRect.y + ringRect.height + 2, placeArrowBelow ? arrowStartY + 2 : arrowTipY + 2);
                int markerH = Math.min(logicalHeight - markerY, markerBottom - markerY);
                overlays.add(clampRect(new Rectangle(markerX, markerY, markerW, markerH)));
                return overlays;
            } finally {
                g.dispose();
            }
        }

        private BufferedImage renderCompletionScreen(int passed, int failed, Path reportPath) {
            Optional<BufferedImage> poster = loadResourceImage("icons/Pux.png");
            if (poster.isEmpty()) {
                return renderFallbackCompletion(passed, failed, reportPath);
            }

            var image = blankCanvas();
            var g = graphics(image);
            try {
                drawHeader(g, "Validation Complete", "Pux", passed, failed);
                drawCenteredText(g, "COMPLETE", 44, true);
                BufferedImage src = poster.get();

                int availableTop = 50;
                int availableBottom = logicalHeight - 28;
                int availableHeight = Math.max(20, availableBottom - availableTop);
                int availableWidth = logicalWidth - 8;

                double scale = Math.min((double) availableWidth / src.getWidth(), (double) availableHeight / src.getHeight());
                scale = Math.min(scale, 1.0);

                int drawW = Math.max(1, (int) Math.round(src.getWidth() * scale));
                int drawH = Math.max(1, (int) Math.round(src.getHeight() * scale));
                int drawX = (logicalWidth - drawW) / 2;
                int drawY = availableTop + ((availableHeight - drawH) / 2);

                BufferedImage mono = toHighContrastMonochrome(src, drawW, drawH);
                g.drawImage(mono, drawX, drawY, drawW, drawH, null);
                g.setColor(BLACK);
                g.drawRect(drawX, drawY, Math.max(0, drawW - 1), Math.max(0, drawH - 1));

                drawCenteredText(g, "Passed: " + passed + "   Failed: " + failed, logicalHeight - 12, true);
            } finally {
                g.dispose();
            }
            return image;
        }

        private BufferedImage renderFallbackCompletion(int passed, int failed, Path reportPath) {
            var image = blankCanvas();
            var g = graphics(image);
            try {
                drawHeader(g, "Validation Complete", "Summary", passed, failed);
                drawCenteredText(g, "Passed: " + passed, logicalHeight / 2 - 10, true);
                drawCenteredText(g, "Failed: " + failed, logicalHeight / 2 + 10, true);
                drawWrappedCentered(g, "Report: " + reportPath.getFileName(), logicalHeight - 14, 16, false);
            } finally {
                g.dispose();
            }
            return image;
        }

        private BufferedImage toHighContrastMonochrome(BufferedImage source, int width, int height) {
            var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var gs = scaled.createGraphics();
            gs.setColor(WHITE);
            gs.fillRect(0, 0, width, height);
            gs.drawImage(source, 0, 0, width, height, null);
            gs.dispose();

            var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = scaled.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                    boolean dark = a > 20 && luminance < 190;
                    out.setRGB(x, y, dark ? BLACK.getRGB() : WHITE.getRGB());
                }
            }
            return out;
        }

        private Optional<BufferedImage> loadResourceImage(String resourcePath) {
            var candidates = List.of(
                resourcePath,
                "/" + resourcePath,
                "icons/" + Path.of(resourcePath).getFileName(),
                "/icons/" + Path.of(resourcePath).getFileName()
            );

            for (var candidate : candidates) {
                try (InputStream stream = openResource(candidate)) {
                    if (stream == null) {
                        continue;
                    }
                    var image = ImageIO.read(stream);
                    if (image != null) {
                        return Optional.of(image);
                    }
                } catch (IOException e) {
                    log.warn("Unable to load resource image {}", candidate, e);
                }
            }

            log.debug("Resource image not found: {}", resourcePath);
            return Optional.empty();
        }

        private InputStream openResource(String resourcePath) {
            InputStream stream = HardwareValidationTest.class.getResourceAsStream(resourcePath);
            if (stream != null) {
                return stream;
            }
            String stripped = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            return HardwareValidationTest.class.getClassLoader().getResourceAsStream(stripped);
        }

        private void drawPrompt(BufferedImage image, int y, int height, String prompt) {
            var g = graphics(image);
            try {
                g.setColor(WHITE);
                g.fillRect(0, y, logicalWidth, height);
                g.setColor(BLACK);
                g.drawLine(0, y, logicalWidth - 1, y);
                drawCenteredText(g, prompt, y + (height / 2) + 4, false);
            } finally {
                g.dispose();
            }
        }

        private void writeFull(EInkDisplayDriver display, BufferedImage image) {
            writeFullAsync(display, image).join();
        }

        private CompletableFuture<Void> writeFullAsync(EInkDisplayDriver display, BufferedImage image) {
            byte[] packed = packMonochrome(image);
            return display.writeFrame(new MonochromeFrame(packed, RefreshMode.FULL));
        }

        private void writeFast(EInkDisplayDriver display, BufferedImage image) {
            writeFastAsync(display, image).join();
        }

        private CompletableFuture<Void> writeFastAsync(EInkDisplayDriver display, BufferedImage image) {
            byte[] packed = packMonochrome(image);
            return display.writeFrame(new MonochromeFrame(packed, RefreshMode.FAST));
        }

        // Writes the full packed frame using the partial waveform. Only pixels that
        // changed from the previous full frame will visibly update on the display.
        // The SSD1675A reference implementation always writes the complete framebuffer
        // for partial updates (it never sub-region-writes to 0x24); we follow that.
        private void writePartial(EInkDisplayDriver display, BufferedImage image) {
            byte[] packed = packMonochrome(image);
            display.writeFrame(new MonochromeFrame(packed, RefreshMode.PARTIAL)).join();
        }



        private byte[] packMonochrome(BufferedImage image) {
            byte[] out = new byte[framebufferRowBytes * framebufferHeight];
            Arrays.fill(out, (byte) 0xFF);

            for (int y = 0; y < logicalHeight; y++) {
                for (int x = 0; x < logicalWidth; x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                    if (luminance < 128) {
                        int[] mapped = mapLogicalToFramebuffer(x, y);
                        int fx = mapped[0];
                        int fy = mapped[1];
                        int idx = fy * framebufferRowBytes + (fx / 8);
                        int bit = 7 - (fx % 8);
                        out[idx] = (byte) (out[idx] & ~(1 << bit));
                    }
                }
            }
            return out;
        }

        private int[] mapLogicalToFramebuffer(int logicalX, int logicalY) {
            return switch (orientation) {
                case PORTRAIT -> new int[]{logicalX, logicalY};
                case LANDSCAPE -> new int[]{logicalY, framebufferHeight - 1 - logicalX};
                case PORTRAIT_INVERTED -> new int[]{framebufferWidth - 1 - logicalX, framebufferHeight - 1 - logicalY};
                case LANDSCAPE_INVERTED -> new int[]{framebufferWidth - 1 - logicalY, logicalX};
            };
        }

        private BufferedImage blankCanvas() {
            var image = new BufferedImage(logicalWidth, logicalHeight, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(WHITE);
            g.fillRect(0, 0, logicalWidth, logicalHeight);
            g.dispose();
            return image;
        }

        private Graphics2D graphics(BufferedImage image) {
            var g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setStroke(new BasicStroke(2f));
            g.setColor(BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, logicalHeight / 12)));
            return g;
        }

        private void drawHeader(Graphics2D g, String title, String subtitle, int passed, int failed) {
            g.setColor(BLACK);
            g.drawLine(0, 26, logicalWidth - 1, 26);
            drawCenteredText(g, title, 14, true);
            g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(10, logicalHeight / 16)));
            drawCenteredText(g, subtitle, 24, false);
            drawScoreBadge(g, passed, failed);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, logicalHeight / 12)));
        }

        private void drawScoreBadge(Graphics2D g, int passed, int failed) {
            String text = "P:" + passed + "  F:" + failed;
            Font badgeFont = new Font("SansSerif", Font.BOLD, Math.max(10, logicalHeight / 17));
            g.setFont(badgeFont);
            FontMetrics fm = g.getFontMetrics();
            int padX = 5;
            int padY = 2;
            int badgeX = 6;
            int badgeY = 3;
            int badgeW = fm.stringWidth(text) + (padX * 2);
            int badgeH = fm.getHeight() + (padY * 2);

            g.setColor(WHITE);
            g.fillRect(badgeX, badgeY, badgeW, badgeH);
            g.setColor(BLACK);
            g.drawRect(badgeX, badgeY, badgeW, badgeH);
            int textX = badgeX + padX;
            int textY = badgeY + padY + fm.getAscent();
            g.drawString(text, textX, textY);
        }

        private void drawWrappedCentered(Graphics2D g, String text, int y, int lineHeight, boolean bold) {
            Font font = new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, Math.max(11, logicalHeight / 14));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            int maxWidth = logicalWidth - 12;
            var lines = wrap(text, fm, maxWidth);
            int baseY = y - ((lines.size() - 1) * lineHeight / 2);
            for (int i = 0; i < lines.size(); i++) {
                drawCenteredText(g, lines.get(i), baseY + i * lineHeight, false);
            }
        }

        private List<String> wrap(String text, FontMetrics fm, int maxWidth) {
            String[] words = text.split("\\s+");
            var lines = new ArrayList<String>();
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                    }
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        private void drawCenteredText(Graphics2D g, String text, int baselineY, boolean bold) {
            if (bold) {
                Font f = g.getFont();
                g.setFont(f.deriveFont(Font.BOLD));
            }
            FontMetrics fm = g.getFontMetrics();
            int x = (logicalWidth - fm.stringWidth(text)) / 2;
            g.setColor(BLACK);
            g.drawString(text, x, baselineY);
        }

        private Rectangle clampRect(Rectangle rect) {
            int x = Math.max(0, rect.x);
            int y = Math.max(0, rect.y);
            int maxW = logicalWidth - x;
            int maxH = logicalHeight - y;
            int w = Math.max(1, Math.min(rect.width, maxW));
            int h = Math.max(1, Math.min(rect.height, maxH));
            return new Rectangle(x, y, w, h);
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
                Rectangle expanded = new Rectangle(
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

        private Rectangle expectedRegion() {
            return findByName(correctItem)
                .map(item -> item.bounds)
                .orElseThrow(() -> new IllegalStateException("Missing correct item: " + correctItem));
        }

        private Rectangle correctItemBounds() {
            return expectedRegion();
        }
    }

    private record ChallengeItem(String name, Rectangle bounds, ItemRenderer renderer) {}

    @FunctionalInterface
    private interface ItemRenderer {
        void render(Graphics2D g, Rectangle bounds);
    }

    private static final class ValidationStepFactory {

        private static final Map<String, Optional<BufferedImage>> PNG_CACHE = new HashMap<>();

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
            Rectangle iconLeft   = new Rectangle(margin + (iconSlotW - icon) / 2, iconY, icon, icon);
            Rectangle iconCenter = new Rectangle(margin + iconSlotW + (iconSlotW - icon) / 2, iconY, icon, icon);
            Rectangle iconRight  = new Rectangle(margin + 2 * iconSlotW + (iconSlotW - icon) / 2, iconY, icon, icon);

            Rectangle topLeft = new Rectangle(margin, margin + 24, shape, shape);
            Rectangle topRight = new Rectangle(width - margin - shape, margin + 24, shape, shape);
            Rectangle bottomLeft = new Rectangle(margin, height - margin - shape, shape, shape);
            Rectangle bottomRight = new Rectangle(width - margin - shape, height - margin - shape, shape, shape);
            Rectangle center = centered(width, height, shape, shape);
            Rectangle bottomCenter = new Rectangle((width - shape) / 2, height - margin - shape, shape, shape);
            Rectangle topCenter = new Rectangle((width - shape) / 2, margin + 24, shape, shape);

            Rectangle largeCenter = centered(width, height, large, large);
            Rectangle mediumLeft = new Rectangle(margin, height - margin - medium, medium, medium);
            Rectangle smallRight = new Rectangle(width - margin - small, margin + 28, small, small);

            Rectangle darkBg = new Rectangle(margin, height / 2 - shape / 2, shape + 10, shape + 10);
            Rectangle whiteBgTop = new Rectangle(width - margin - shape - 10, margin + 30, shape + 10, shape + 10);
            Rectangle whiteBgBottom = new Rectangle(width - margin - shape - 10, height - margin - shape - 10, shape + 10, shape + 10);

            Rectangle step10Small = new Rectangle((width / 2) - (small / 2), height / 2 - (small / 2), small, small);
            Rectangle step10Large = new Rectangle(margin, margin + 24, large, large);
            Rectangle step10Medium = new Rectangle(width - margin - medium, height - margin - medium, medium, medium);

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

        private static Rectangle centered(int width, int height, int w, int h) {
            return new Rectangle((width - w) / 2, (height - h) / 2, w, h);
        }

        private static ChallengeItem item(String name, Rectangle bounds, ItemRenderer renderer) {
            return new ChallengeItem(name, bounds, renderer);
        }

        private static ItemRenderer pngIcon(String resourcePath, ItemRenderer fallback) {
            return (g, bounds) -> {
                var image = loadIcon(resourcePath);
                if (image.isPresent()) {
                    // Convert to high-contrast monochrome at the target size. This
                    // handles alpha (transparent → white) and maps all non-trivially-
                    // dark pixels to black, matching the eInk display's binary output.
                    var mono = toMonochrome(image.get(), bounds.width, bounds.height);
                    g.drawImage(mono, bounds.x, bounds.y, null);
                } else {
                    fallback.render(g, bounds);
                }
            };
        }

        private static BufferedImage toMonochrome(BufferedImage source, int width, int height) {
            // Composite source (may be ARGB) onto a white background at the target size.
            var composite = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var cg = composite.createGraphics();
            cg.setColor(Color.WHITE);
            cg.fillRect(0, 0, width, height);
            cg.drawImage(source, 0, 0, width, height, null);
            cg.dispose();
            // Threshold to pure black/white with high-contrast luminance cutoff (190).
            var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = composite.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int gv = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int luminance = (r * 299 + gv * 587 + b * 114) / 1000;
                    out.setRGB(x, y, (a > 20 && luminance < 190) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
                }
            }
            return out;
        }

        private static Optional<BufferedImage> loadIcon(String resourcePath) {
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
                Optional<BufferedImage> loaded = s == null
                    ? Optional.empty()
                    : Optional.ofNullable(ImageIO.read(s));
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

        private static void drawSquare(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        private static void drawCircle(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.fillOval(r.x, r.y, r.width, r.height);
        }

        private static void drawTriangle(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            var p = new Polygon();
            p.addPoint(r.x + r.width / 2, r.y);
            p.addPoint(r.x, r.y + r.height);
            p.addPoint(r.x + r.width, r.y + r.height);
            g.fillPolygon(p);
        }

        private static void drawCatIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            var head = inset(r, 8, 12);
            g.fillOval(head.x, head.y + 6, head.width, head.height - 10);
            var leftEar = new Polygon(new int[]{head.x + 4, head.x + 10, head.x + 14},
                new int[]{head.y + 8, head.y, head.y + 8}, 3);
            var rightEar = new Polygon(new int[]{head.x + head.width - 14, head.x + head.width - 10, head.x + head.width - 4},
                new int[]{head.y + 8, head.y, head.y + 8}, 3);
            g.fillPolygon(leftEar);
            g.fillPolygon(rightEar);
        }

        private static void drawDogIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            var head = inset(r, 8, 12);
            g.fillOval(head.x + 4, head.y + 6, head.width - 8, head.height - 10);
            g.fillOval(head.x, head.y + 10, 8, 14);
            g.fillOval(head.x + head.width - 8, head.y + 10, 8, 14);
        }

        private static void drawFishIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            var body = inset(r, 8, 14);
            g.fillOval(body.x, body.y + 4, body.width - 10, body.height - 8);
            var tail = new Polygon();
            tail.addPoint(body.x + body.width - 8, body.y + body.height / 2);
            tail.addPoint(body.x + body.width + 2, body.y + 2);
            tail.addPoint(body.x + body.width + 2, body.y + body.height - 2);
            g.fillPolygon(tail);
        }

        private static void drawIceCreamIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            var scoop = inset(r, 10, 8);
            g.fillOval(scoop.x, scoop.y, scoop.width, scoop.height / 2 + 4);
            var cone = new Polygon();
            cone.addPoint(r.x + r.width / 2, r.y + r.height - 4);
            cone.addPoint(r.x + r.width / 2 - 10, r.y + r.height / 2);
            cone.addPoint(r.x + r.width / 2 + 10, r.y + r.height / 2);
            g.fillPolygon(cone);
        }

        private static void drawBurgerIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            int x = r.x + 6;
            int w = r.width - 12;
            g.fillRoundRect(x, r.y + 10, w, 10, 8, 8);
            g.fillRect(x + 2, r.y + 22, w - 4, 8);
            g.fillRoundRect(x, r.y + 30, w, 10, 8, 8);
        }

        private static void drawAppleIcon(Graphics2D g, Rectangle r) {
            drawIconBadge(g, r);
            g.fillOval(r.x + 10, r.y + 10, r.width - 20, r.height - 16);
            g.fillRect(r.x + r.width / 2 - 1, r.y + 4, 2, 8);
            var leaf = new Polygon();
            leaf.addPoint(r.x + r.width / 2 + 2, r.y + 8);
            leaf.addPoint(r.x + r.width / 2 + 12, r.y + 4);
            leaf.addPoint(r.x + r.width / 2 + 8, r.y + 12);
            g.fillPolygon(leaf);
        }

        private static void drawDarkBackgroundCircle(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Color.WHITE);
            g.fillOval(r.x + 5, r.y + 5, r.width - 10, r.height - 10);
            g.setColor(Color.BLACK);
        }

        private static void drawWhiteBackgroundSquare(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.drawRect(r.x, r.y, r.width, r.height);
            g.fillRect(r.x + 5, r.y + 5, r.width - 10, r.height - 10);
        }

        private static void drawWhiteBackgroundTriangle(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.drawRect(r.x, r.y, r.width, r.height);
            var p = new Polygon();
            p.addPoint(r.x + r.width / 2, r.y + 5);
            p.addPoint(r.x + 6, r.y + r.height - 5);
            p.addPoint(r.x + r.width - 6, r.y + r.height - 5);
            g.fillPolygon(p);
        }

        private static void drawIconBadge(Graphics2D g, Rectangle r) {
            g.setColor(Color.BLACK);
            g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        }

        private static Rectangle inset(Rectangle r, int dx, int dy) {
            return new Rectangle(r.x + dx, r.y + dy, r.width - (2 * dx), r.height - (2 * dy));
        }
    }

    private record StepResult(
        int step,
        String title,
        boolean pass,
        Rectangle expectedRegion,
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
