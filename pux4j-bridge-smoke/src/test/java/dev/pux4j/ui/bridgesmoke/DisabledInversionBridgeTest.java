// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.awaitCondition;
import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.decodePng;
import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.grayAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disabled control is colour-inverted in the captured frame (see {@code DisabledInverter}),
 * so a white, borderless, text-less control becomes a black block on the panel while the same
 * control enabled stays white. Counting black pixels avoids depending on the panel rotation.
 */
class DisabledInversionBridgeTest {

    private static final int W = 80;
    private static final int H = 40;
    // Placed so that a transposed (x/y swapped) rectangle would fall partly outside the 250x122 scene.
    private static final int X = 150;
    private static final int Y = 20;

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    @Test
    void disabledButtonIsInvertedAndEnabledButtonIsNot() throws Exception {
        assertEquals(W * H, blackPixelsFor(button(true)), "disabled button");
        assertEquals(0, blackPixelsFor(button(false)), "enabled button");
    }

    @Test
    void disabledControlsOfOtherTypesAreInverted() throws Exception {
        assertEquals(W * H, blackPixelsFor(() -> {
            Label label = new Label();
            label.setStyle("-fx-background-color: white; -fx-opacity: 1;");
            label.setDisable(true);
            label.setPrefSize(W, H);
            label.relocate(X, Y);
            return new Pane(label);
        }), "disabled label");
    }

    @Test
    void controlInsideADisabledPaneIsInvertedOnce() throws Exception {
        assertEquals(W * H, blackPixelsFor(() -> {
            Pane root = (Pane) button(false).get();
            root.setDisable(true);
            return root;
        }), "enabled button inside a disabled pane");
    }

    @Test
    void disabledControlScrolledOutOfViewIsNotInverted() throws Exception {
        assertEquals(0, blackPixelsFor(() -> {
            Button b = (Button) ((Pane) button(true).get()).getChildren().get(0);
            b.relocate(0, 0);
            Region filler = new Region();
            filler.setPrefSize(W, 100);
            VBox content = new VBox(filler, b);
            content.setStyle("-fx-background-color: white;");
            ScrollPane scroll = new ScrollPane(content);
            scroll.setStyle("-fx-background-color: white; -fx-background-insets: 0; -fx-padding: 0;");
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefSize(W, 50);
            scroll.relocate(X, Y);
            return new Pane(scroll);
        }), "disabled button below the scroll pane's viewport");
    }

    @Test
    void fullyTransparentDisabledControlIsNotInverted() throws Exception {
        assertEquals(0, blackPixelsFor(() -> {
            Pane inner = button(true).get();
            inner.setOpacity(0);
            return new Pane(inner);
        }), "disabled button inside a fully transparent pane");
    }

    private static Supplier<Pane> button(boolean disabled) {
        return () -> {
            Button b = new Button();
            // Modena dims :disabled with opacity and draws borders/insets; neutralise both.
            b.setStyle("-fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0;"
                    + " -fx-border-width: 0; -fx-padding: 0; -fx-opacity: 1;");
            b.setDisable(disabled);
            b.setPrefSize(W, H);
            b.relocate(X, Y);
            return new Pane(b);
        };
    }

    /** Opens a scene of {@code root}, waits for a frame to be written and counts its black pixels. */
    private int blackPixelsFor(Supplier<? extends Pane> root) throws Exception {
        Path dir = Files.createTempDirectory(out, "run");
        try (BridgeFixture f = BridgeFixture.open(dir, root::get)) {
            int[] black = {-1};
            // The PNG is written on the display thread, not atomically: a partial file is "not yet".
            assertTrue(awaitCondition(() -> {
                try {
                    black[0] = countBlack(decodePng(dir, 1));
                    return true;
                } catch (java.io.IOException | RuntimeException e) {
                    return false;
                }
            }, 10_000), "no readable frame written");
            return black[0];
        }
    }

    private static int countBlack(BufferedImage image) {
        int black = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (grayAt(image, x, y) < 128) {
                    black++;
                }
            }
        }
        return black;
    }
}
