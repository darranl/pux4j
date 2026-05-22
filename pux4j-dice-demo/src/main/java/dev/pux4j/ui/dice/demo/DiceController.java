// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.dice.demo;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;

import java.net.URL;
import java.util.Arrays;
import java.util.Random;
import java.util.ResourceBundle;

/**
 * Controller for the Yahtzee-style dice rolling demo.
 *
 * <p>Game rules:
 * <ul>
 *   <li>A player has up to three rolls per turn.</li>
 *   <li>The first roll always rolls all five dice.</li>
 *   <li>Before the second and third rolls, tapping a die saves it (excludes it
 *       from re-rolling). Tapping again un-saves it.</li>
 *   <li>After the third roll the Roll button is disabled.</li>
 *   <li>Clear resets the board at any time after the first roll.</li>
 * </ul>
 *
 * <p>Roll sequence: On each roll after the first, saved dice are moved to the
 * left, the dice to be re-rolled are briefly blanked, then new values are shown
 * after a short pause to make the re-roll visually distinct.
 */
public final class DiceController implements Initializable {

    private enum GameState { CLEARED, ROLLED_1, ROLLED_2, ROLLED_3 }

    private static final int    DIE_COUNT    = 5;
    /** Native display width in eInk pixels — matches DiceDemoApp and FXML prefWidth. */
    private static final double DISPLAY_WIDTH = 296.0;

    @FXML private HBox   diceBox;
    @FXML private Button clearBtn;
    @FXML private Button rollBtn;

    /** Working order of dice — may be sorted on successive rolls. */
    private final DieView[] dice         = new DieView[DIE_COUNT];
    /** Original left-to-right order; used to restore after Clear. */
    private final DieView[] originalDice = new DieView[DIE_COUNT];

    private final Random    rng   = new Random();
    private GameState       state = GameState.CLEARED;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Initial die size: width split evenly; height matches width for square faces.
        double dieSize = DISPLAY_WIDTH / DIE_COUNT;

        diceBox.setAlignment(Pos.CENTER_LEFT);
        diceBox.setFillHeight(false);

        for (int i = 0; i < DIE_COUNT; i++) {
            DieView die = new DieView();
            die.getStyleClass().add("die-view");
            die.setPrefSize(dieSize, dieSize);
            die.setMinSize(dieSize, dieSize);
            die.setMaxSize(dieSize, dieSize);

            dice[i]         = die;
            originalDice[i] = die;

            die.setOnMouseClicked(e -> {
                if (state == GameState.ROLLED_1 || state == GameState.ROLLED_2) {
                    die.setSaved(!die.isSaved());
                    die.redraw();
                }
            });

            diceBox.getChildren().add(die);
            HBox.setHgrow(die, Priority.ALWAYS);
        }

        // Recompute square die size from actual layout width when available.
        diceBox.widthProperty().addListener((obs, o, newW) -> resizeDice(newW.doubleValue()));

        updateButtons();
    }

    private void resizeDice(double boxWidth) {
        double sz = boxWidth / DIE_COUNT;
        for (DieView die : dice) {
            die.setPrefSize(sz, sz);
            die.setMinSize(sz, sz);
            die.setMaxSize(sz, sz);
            die.redraw();
        }
    }

    @FXML
    private void onClear() {
        for (DieView die : dice) {
            die.setValue(0);
            die.setSaved(false);
            die.redraw();
        }
        // Restore original left-to-right order.
        System.arraycopy(originalDice, 0, dice, 0, DIE_COUNT);
        diceBox.getChildren().clear();
        for (DieView d : dice) diceBox.getChildren().add(d);

        state = GameState.CLEARED;
        updateButtons();
    }

    @FXML
    private void onRoll() {
        boolean firstRoll = (state == GameState.CLEARED);

        if (!firstRoll) {
            // Move saved dice to the left for visual clarity.
            Arrays.sort(dice, java.util.Comparator.<DieView>comparingInt(d -> d.isSaved() ? 0 : 1));
            diceBox.getChildren().clear();
            for (DieView d : dice) diceBox.getChildren().add(d);
        }

        // Blank the dice about to be re-rolled so the change is clearly visible.
        for (DieView die : dice) {
            if (!die.isSaved()) {
                die.setValue(0);
                die.redraw();
            }
        }

        // Disable roll immediately to prevent double-clicks during animation.
        rollBtn.setDisable(true);

        new Timeline(new KeyFrame(Duration.millis(250), e -> {
            for (DieView die : dice) {
                if (!die.isSaved()) {
                    die.setValue(rng.nextInt(6) + 1);
                    die.redraw();
                }
            }
            state = switch (state) {
                case CLEARED  -> GameState.ROLLED_1;
                case ROLLED_1 -> GameState.ROLLED_2;
                case ROLLED_2 -> GameState.ROLLED_3;
                default       -> state;
            };
            // After the final roll, show all dice as "saved" to indicate none
            // can be re-rolled.
            if (state == GameState.ROLLED_3) {
                for (DieView die : dice) {
                    die.setSaved(true);
                    die.redraw();
                }
            }
            updateButtons();
        })).play();
    }

    private void updateButtons() {
        clearBtn.setDisable(state == GameState.CLEARED);
        rollBtn.setDisable(state == GameState.ROLLED_3);
        rollBtn.setText(switch (state) {
            case CLEARED  -> "Roll 1";
            case ROLLED_1 -> "Roll 2";
            case ROLLED_2, ROLLED_3 -> "Roll 3";
        });
    }
}

