// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import javafx.scene.Group;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where nothing is under the contact, JavaFX delivers mouse events to the {@code Scene} itself.
 * A {@link Group} root is used because it only covers its children — the empty area is the Scene's.
 */
class SceneTargetBridgeTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    private static BridgeFixture open(Path out, Button[] holder) throws Exception {
        return BridgeFixture.open(out, () -> {
            holder[0] = new Button("Go");
            holder[0].setPrefSize(60, 30);
            holder[0].relocate(10, 10);
            return new Group(holder[0]);
        });
    }

    @Test
    void tapOnEmptySpaceIsDeliveredToTheScene() throws Exception {
        Button[] button = new Button[1];
        try (BridgeFixture f = open(out, button)) {
            f.play(Gesture.tap(200, 100));
            var types = f.trace.typesAt("scene");
            assertEquals(List.of("MOUSE_ENTERED", "MOUSE_MOVED", "MOUSE_PRESSED", "MOUSE_RELEASED", "MOUSE_CLICKED",
                    "MOUSE_EXITED"), types);
            assertEquals(types.size(), f.trace.types().size(), "nothing but the scene saw the tap");
        }
    }

    @Test
    void dragFromNodeOntoEmptySpaceClicksTheCommonAncestor() throws Exception {
        Button[] button = new Button[1];
        try (BridgeFixture f = open(out, button)) {
            int[] on = f.centre(button[0]);
            f.play(Gesture.press(on[0], on[1]).dragTo(200, 100).release());
            // JavaFX's rule: the click goes to the deepest node that is in both the press and the release chain.
            assertEquals(List.of("MOUSE_CLICKED"), f.trace.typesAt("scene").stream()
                    .filter(t -> t.equals("MOUSE_CLICKED")).toList());
            assertTrue(f.trace.typesAt("root/0").stream().noneMatch(t -> t.equals("MOUSE_CLICKED")));
        }
    }
}
