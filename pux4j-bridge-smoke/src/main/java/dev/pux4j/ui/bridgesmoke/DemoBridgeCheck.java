// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.core.ProgrammaticTouchSource;
import dev.pux4j.ui.core.RefreshMode;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.*;

/**
 * Pre-flight check for the <em>real</em> counter demo ({@code SceneContent} named {@code demo}):
 * hosts it on the bridge at both real panel geometries (see {@link BridgeCheckHarness}) and drives
 * it with taps on the actual "+" / "−" buttons. Where {@link BridgeSmokeTest} proves the bridge
 * with a synthetic scene, this proves the demo's own FXML/CSS layout fits 296×128 and 250×122 and
 * that touch, orientation and rendering agree end to end.
 *
 * <p>Frames land in {@code target/demo-bridge-check/<panel>/}. Exits 0 on success, 1 on failure.
 * Run with {@code pux4j-bridge-smoke/run-demo-check.sh}.
 */
public final class DemoBridgeCheck {

    private static final Logger LOG = LoggerFactory.getLogger(DemoBridgeCheck.class);

    public static void main(String[] args) throws Exception {
        BridgeCheckHarness.runAndExit("demo-bridge-check", "demo", DemoBridgeCheck::run);
    }

    private static void run(Session session) throws Exception {
        Scene scene = session.scene();
        var recording = session.recording();
        ProgrammaticTouchSource touchSource = session.touch();
        Path outputDir = session.outputDir();
        int w = session.width();
        int h = session.height();

        Button inc = lookup(scene, "#incBtn", Button.class);
        Button dec = lookup(scene, "#decBtn", Button.class);
        Label counter = lookup(scene, "#counterLabel", Label.class);

        // --- Frame 1: initial layout ------------------------------------------------
        check(recording.awaitWriteCount(c -> c >= 1, CONDITION_TIMEOUT_MS),
                "no initial frame within " + CONDITION_TIMEOUT_MS + "ms");
        checkEquals(RefreshMode.FULL, recording.events().get(0).mode(), "initial write must be FULL");

        Rect incR = rectOf(inc);
        Rect decR = rectOf(dec);
        Rect labelR = rectOf(counter);
        LOG.info("Layout: dec {}  label {}  inc {}", decR, labelR, incR);
        for (Rect r : List.of(incR, decR, labelR)) {
            check(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= w && r.y() + r.h() <= h,
                    "node " + r + " does not fit inside the " + w + "x" + h + " scene");
        }
        check(decR.x() + decR.w() <= labelR.x() + 1 && labelR.x() + labelR.w() <= incR.x() + 1,
                "expected left-to-right order dec | label | inc, got " + decR + " " + labelR + " " + incR);
        check(labelR.w() >= 40, "counter label is only " + labelR.w() + "px wide — layout squeezed it");
        checkEquals("0", callAndWaitOnFx(counter::getText), "initial counter text");
        check(callAndWaitOnFx(dec::isDisabled), "the \"−\" button must start disabled at 0");

        BufferedImage frame1 = decodePng(outputDir, 1);
        // Orientation/handedness: the enabled "+" button has a 3px black border; its left
        // border column must be dark. A rotated/mirrored frame would put something else
        // (white background or the label) there.
        int incBorderGray = grayAt(frame1, incR.x() + 1, incR.cy());
        check(incBorderGray < 60, "the \"+\" button's left border pixel (" + (incR.x() + 1) + ","
                + incR.cy() + ") should be black, was gray=" + incBorderGray
                + " — frame orientation or layout is wrong; see frame-0001.png");
        // The disabled button is colour-inverted by the bridge: dark inside its layout box. Sample a
        // few pixels in from the edge of the visual bounds (they include the control's outset).
        int decGray = grayAt(frame1, decR.x() + 8, decR.cy());
        check(decGray < 60, "the disabled \"−\" button should be inverted (dark) at (" + (decR.x() + 8) + ","
                + decR.cy() + "), was gray=" + decGray + "; see frame-0001.png");

        // --- Tap "+" -------------------------------------------------------------------
        tap(touchSource, 1, incR);
        check(awaitCondition(() -> "1".equals(textOf(counter)), CONDITION_TIMEOUT_MS),
                "tapping the \"+\" button's centre did not increment the counter (text="
                + textOf(counter) + ") — touch coordinates and the frame disagree");
        check(recording.awaitWriteCount(c -> c >= 2, CONDITION_TIMEOUT_MS), "no write followed the \"+\" tap");
        check(!callAndWaitOnFx(dec::isDisabled), "\"−\" must be enabled once the counter is 1");
        BufferedImage frame2 = decodePng(outputDir, recording.writeCount());
        check(!sameImage(frame1, frame2), "frame did not change after incrementing");

        // --- Tap "−" (now enabled) ------------------------------------------------------
        int writesBefore = recording.writeCount();
        tap(touchSource, 2, decR);
        check(awaitCondition(() -> "0".equals(textOf(counter)), CONDITION_TIMEOUT_MS),
                "tapping the \"−\" button's centre did not decrement the counter (text="
                + textOf(counter) + ")");
        check(recording.awaitWriteCount(c -> c > writesBefore, CONDITION_TIMEOUT_MS),
                "no write followed the \"−\" tap");
        check(callAndWaitOnFx(dec::isDisabled), "\"−\" must be disabled again at 0");

        // --- Tap the label (no handler): counter unchanged ---------------------------------
        tap(touchSource, 3, labelR);
        Thread.sleep(NO_WRITE_TIMEOUT_MS);
        checkEquals("0", textOf(counter), "tapping the label must not change the counter");
    }

    private DemoBridgeCheck() {}
}
