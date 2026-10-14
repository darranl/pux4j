// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.core.ProgrammaticTouchSource;
import dev.pux4j.ui.core.RefreshMode;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.*;

/**
 * Pre-flight check for the <em>real</em> dice demo ({@code SceneContent} named {@code dice-demo}):
 * hosts it on the bridge at both real panel geometries (see {@link BridgeCheckHarness}), checks
 * the five dice and the button row fit, then plays a whole turn by tapping the real buttons and
 * dice — roll, save a die, un-save it, roll twice more, clear.
 *
 * <p>Frames land in {@code target/dice-bridge-check/<panel>/}. Exits 0 on success, 1 on failure.
 * Run with {@code pux4j-bridge-smoke/run-dice-check.sh}.
 */
public final class DiceBridgeCheck {

    private static final Logger LOG = LoggerFactory.getLogger(DiceBridgeCheck.class);

    /** A roll finishes 250 ms after the tap; allow for it plus the capture interval. */
    private static final long ROLL_TIMEOUT_MS = CONDITION_TIMEOUT_MS;

    public static void main(String[] args) throws Exception {
        BridgeCheckHarness.runAndExit("dice-bridge-check", "dice-demo", DiceBridgeCheck::run);
    }

    private static void run(Session session) throws Exception {
        Scene scene = session.scene();
        var recording = session.recording();
        ProgrammaticTouchSource touch = session.touch();
        Path outputDir = session.outputDir();
        int w = session.width();
        int h = session.height();

        Button clear = lookup(scene, "#clearBtn", Button.class);
        Button roll = lookup(scene, "#rollBtn", Button.class);
        List<Node> dice = new ArrayList<>(callAndWaitOnFx(() -> scene.getRoot().lookupAll(".die-view")));
        checkEquals(5, dice.size(), "number of dice");

        // --- Frame 1: initial layout ---------------------------------------------------
        check(recording.awaitWriteCount(c -> c >= 1, CONDITION_TIMEOUT_MS),
                "no initial frame within " + CONDITION_TIMEOUT_MS + "ms");
        checkEquals(RefreshMode.FULL, recording.events().get(0).mode(), "initial write must be FULL");

        List<Rect> dieRects = new ArrayList<>();
        for (Node die : dice) {
            dieRects.add(rectOf(die));
        }
        dieRects.sort(Comparator.comparingInt(Rect::x));
        Rect clearR = rectOf(clear);
        Rect rollR = rectOf(roll);
        LOG.info("Layout: dice {}  clear {}  roll {}", dieRects, clearR, rollR);

        for (Rect r : dieRects) {
            check(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= w && r.y() + r.h() <= h,
                    "die " + r + " does not fit inside the " + w + "x" + h + " scene");
            check(r.w() == r.h(), "die " + r + " is not square");
            check(r.w() >= 40, "die " + r + " is only " + r.w() + "px wide — layout squeezed it");
            check(r.y() + r.h() <= clearR.y(), "die " + r + " overlaps the button row " + clearR);
        }
        for (int i = 1; i < dieRects.size(); i++) {
            Rect prev = dieRects.get(i - 1);
            check(prev.x() + prev.w() <= dieRects.get(i).x() + 1,
                    "dice overlap: " + prev + " then " + dieRects.get(i));
        }
        check(clearR.x() + clearR.w() <= rollR.x() + 1,
                "expected Clear left of Roll, got " + clearR + " " + rollR);
        for (Rect r : List.of(clearR, rollR)) {
            check(r.x() >= 0 && r.x() + r.w() <= w && r.y() + r.h() <= h,
                    "button " + r + " does not fit inside the " + w + "x" + h + " scene");
        }
        checkEquals("Roll 1", callAndWaitOnFx(roll::getText), "initial Roll button text");
        check(callAndWaitOnFx(clear::isDisabled), "Clear must start disabled");
        BufferedImage cleared = latestFrame(session);

        // --- Tapping a die before the first roll does nothing --------------------------------
        int writes = recording.writeCount();
        tap(touch, 1, dieRects.get(0));
        Thread.sleep(NO_WRITE_TIMEOUT_MS);
        checkEquals(writes, recording.writeCount(), "tapping a die before the first roll must not redraw");

        // --- Roll 1 ----------------------------------------------------------------------------
        tap(touch, 2, rollR);
        awaitText(roll, "Roll 2");
        check(!callAndWaitOnFx(clear::isDisabled), "Clear must be enabled after the first roll");
        BufferedImage rolled1 = latestFrame(session);
        check(!sameImage(cleared, rolled1), "frame did not change after the first roll");

        // --- Save a die, then un-save it -----------------------------------------------------------
        tap(touch, 3, dieRects.get(0));
        BufferedImage saved = awaitFrameChange(session, rolled1, "tapping a die did not save it (no redraw)");
        tap(touch, 4, dieRects.get(0));
        BufferedImage unsaved = awaitFrameChange(session, saved, "tapping a saved die did not un-save it");
        check(sameImage(rolled1, unsaved), "un-saving the die did not restore the pre-save frame");

        // --- Roll 2, roll 3 --------------------------------------------------------------------------
        tap(touch, 5, rollR);
        awaitText(roll, "Roll 3");
        check(!callAndWaitOnFx(roll::isDisabled), "Roll must still be enabled before the third roll");
        tap(touch, 6, rollR);
        check(awaitCondition(() -> callUnchecked(roll::isDisabled), ROLL_TIMEOUT_MS),
                "Roll must be disabled after the third roll");
        BufferedImage rolled3 = latestFrame(session);

        // After the final roll every die shows as saved and taps are ignored.
        writes = recording.writeCount();
        tap(touch, 7, dieRects.get(1));
        Thread.sleep(NO_WRITE_TIMEOUT_MS);
        checkEquals(writes, recording.writeCount(), "tapping a die after the third roll must not redraw");

        // --- Clear ---------------------------------------------------------------------------------
        tap(touch, 8, clearR);
        awaitText(roll, "Roll 1");
        check(callAndWaitOnFx(clear::isDisabled), "Clear must be disabled again after clearing");
        BufferedImage afterClear = latestFrame(session);
        check(!sameImage(rolled3, afterClear), "frame did not change after Clear");
        check(sameImage(cleared, afterClear), "Clear did not restore the initial frame");
    }

    // -------------------------------------------------------------------------

    private static <T> T callUnchecked(java.util.concurrent.Callable<T> c) {
        try {
            return callAndWaitOnFx(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitText(Button button, String expected) throws InterruptedException {
        check(awaitCondition(() -> expected.equals(callUnchecked(button::getText)), ROLL_TIMEOUT_MS),
                "Roll button text never became \"" + expected + "\" (is \""
                        + callUnchecked(button::getText) + "\")");
    }

    /** The most recent frame once the bridge has been quiet for a capture interval or three. */
    private static BufferedImage latestFrame(Session session) throws Exception {
        Thread.sleep(NO_WRITE_TIMEOUT_MS);
        return decodePng(session.outputDir(), session.recording().writeCount());
    }

    private static BufferedImage awaitFrameChange(Session session, BufferedImage before, String message)
            throws Exception {
        check(awaitCondition(() -> {
            try {
                return !sameImage(before,
                        decodePng(session.outputDir(), session.recording().writeCount()));
            } catch (Exception e) {
                return false;
            }
        }, CONDITION_TIMEOUT_MS), message);
        return latestFrame(session);
    }

    private DiceBridgeCheck() {}
}
