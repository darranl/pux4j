// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.bridgesmoke;

import dev.pux4j.ui.bridgesmoke.BridgeFixture.Gesture;
import javafx.scene.control.Pagination;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static dev.pux4j.ui.bridgesmoke.BridgeCheckHarness.callAndWaitOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How two stock controls that look at touch themselves behave on the bridge. {@code ScrollPane}
 * pans on {@code MOUSE_DRAGGED} when it is pannable (or the platform reports touch support,
 * which this one does not), so a drag scrolls a pannable pane and leaves a plain one alone;
 * {@code Pagination} turns pages on a swipe.
 */
class TouchControlsBridgeTest {

    @TempDir Path out;

    @BeforeAll
    static void toolkit() {
        BridgeFixture.requireToolkit();
    }

    private double dragScrollPane(boolean pannable) throws Exception {
        ScrollPane[] sp = new ScrollPane[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            Region content = new Region();
            content.setPrefSize(200, 600);
            sp[0] = new ScrollPane(content);
            sp[0].setPannable(pannable);
            sp[0].setPrefSize(240, 110);
            return new Pane(sp[0]);
        })) {
            f.play(Gesture.press(120, 90).dragTo(120, 30).release());
            return callAndWaitOnFx(sp[0]::getVvalue);
        }
    }

    @Test
    void aPannableScrollPaneScrollsOnADrag() throws Exception {
        double value = dragScrollPane(true);
        assertTrue(value > 0, "vvalue after dragging up 60 px: " + value);
    }

    @Test
    void aScrollPaneThatIsNotPannableStaysPutOnADrag() throws Exception {
        assertEquals(0.0, dragScrollPane(false));
    }

    @Test
    void paginationTurnsPagesOnATouchSwipe() throws Exception {
        Pagination[] p = new Pagination[1];
        try (BridgeFixture f = BridgeFixture.open(out, () -> {
            p[0] = new Pagination(5);
            p[0].setPrefSize(240, 110);
            return new Pane(p[0]);
        })) {
            f.play(Gesture.press(200, 40).dragTo(40, 40).release());
            assertEquals(1, callAndWaitOnFx(p[0]::getCurrentPageIndex), "swipe left goes forward");
            f.play(Gesture.press(40, 40).dragTo(200, 40).release());
            assertEquals(0, callAndWaitOnFx(p[0]::getCurrentPageIndex), "swipe right goes back");
        }
    }
}
