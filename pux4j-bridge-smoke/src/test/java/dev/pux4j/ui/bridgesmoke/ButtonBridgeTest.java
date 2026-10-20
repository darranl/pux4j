// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@link Button} on the bridge: touches in, the Button's own arm/disarm/action behaviour
 * out, as JavaFX gives it on any touch platform. The scene is hosted without a Stage, as in production.
 */
class ButtonBridgeTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    /** One Button in the top-left of an otherwise empty Pane; counts actions; notes its state at release. */
    private static final class Rig implements AutoCloseable {
        final AtomicInteger actions = new AtomicInteger();
        volatile boolean armedAtRelease;
        volatile boolean pressedAtRelease;
        volatile boolean hoverAtRelease;
        Button button;
        BridgeFixture fixture;
        int[] on;
        final int[] off = {200, 100};

        Rig(Path out) throws Exception {
            fixture = BridgeFixture.open(out, () -> {
                button = new Button("Go");
                button.setPrefSize(100, 40);
                button.relocate(20, 20);
                button.setOnAction(e -> actions.incrementAndGet());
                Pane root = new Pane(button);
                root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
                    armedAtRelease = button.isArmed();
                    pressedAtRelease = button.isPressed();
                    hoverAtRelease = button.isHover();
                });
                return root;
            });
            on = fixture.centre(button);
        }

        @Override public void close() throws Exception { fixture.close(); }
    }

    @Test
    void tapFiresActionOnce() throws Exception {
        try (Rig rig = new Rig(out)) {
            rig.fixture.play(Gesture.tap(rig.on[0], rig.on[1]));
            assertEquals(1, rig.actions.get());
            assertTrue(rig.armedAtRelease, "armed when released over the button");
            assertTrue(rig.pressedAtRelease);
            assertTrue(rig.hoverAtRelease);
            assertFalse(rig.button.isPressed(), ":pressed cleared after release");
            var trace = rig.fixture.trace;
            assertTrue(BridgeFixture.isWithin(rig.button, rig.fixture.scene.getRoot().lookup(".button")));
            var types = trace.types();
            assertTrue(types.indexOf("MOUSE_PRESSED") < types.indexOf("MOUSE_RELEASED"), types.toString());
            assertTrue(types.indexOf("MOUSE_RELEASED") < types.indexOf("MOUSE_CLICKED"), types.toString());
        }
    }

    @Test
    void dragOffAndReleaseDoesNotFire() throws Exception {
        try (Rig rig = new Rig(out)) {
            rig.fixture.play(Gesture.press(rig.on[0], rig.on[1]).dragTo(rig.off[0], rig.off[1]).release());
            assertEquals(0, rig.actions.get());
            assertFalse(rig.armedAtRelease, "disarmed once the contact left the button");
            assertFalse(rig.hoverAtRelease, ":hover follows the contact");
            assertTrue(rig.pressedAtRelease, ":pressed stays on the press target until release (JavaFX rule)");
        }
    }

    @Test
    void dragOffAndBackOnFires() throws Exception {
        try (Rig rig = new Rig(out)) {
            rig.fixture.play(Gesture.press(rig.on[0], rig.on[1])
                    .dragTo(rig.off[0], rig.off[1]).dragTo(rig.on[0], rig.on[1]).release());
            assertEquals(1, rig.actions.get());
            assertTrue(rig.armedAtRelease, "re-armed on returning to the button");
        }
    }

    @Test
    void pressOffAndDragOnDoesNotArm() throws Exception {
        try (Rig rig = new Rig(out)) {
            rig.fixture.play(Gesture.press(rig.off[0], rig.off[1]).dragTo(rig.on[0], rig.on[1]).release());
            assertEquals(0, rig.actions.get());
            assertFalse(rig.armedAtRelease, "a press that started elsewhere never arms the button");
        }
    }

    @Test
    void disabledButtonDoesNotFire() throws Exception {
        try (Rig rig = new Rig(out)) {
            callAndWaitOnFx(() -> { rig.button.setDisable(true); return null; });
            rig.fixture.play(Gesture.tap(rig.on[0], rig.on[1]));
            assertEquals(0, rig.actions.get());
            assertEquals(List.of("MOUSE_MOVED", "MOUSE_PRESSED", "MOUSE_RELEASED", "MOUSE_CLICKED"),
                    rig.fixture.trace.typesAt("root"), "a disabled node is not picked: its parent gets the touch");
        }
    }

    /**
     * Characterises a known deviation: the scene has no window, so a pressed control becomes the
     * scene's focus owner but never reports {@code isFocused()} (no {@code :focused} style, no caret).
     */
    @Test
    void pressMakesButtonFocusOwnerButNotFocused() throws Exception {
        try (Rig rig = new Rig(out)) {
            rig.fixture.play(Gesture.tap(rig.on[0], rig.on[1]));
            assertTrue(callAndWaitOnFx(() -> rig.fixture.scene.getFocusOwner() == rig.button));
            assertFalse(callAndWaitOnFx(rig.button::isFocused));
        }
    }
}
