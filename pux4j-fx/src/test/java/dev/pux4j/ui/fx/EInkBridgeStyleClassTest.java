package dev.pux4j.ui.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pux4j.ui.core.PixelFormat;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

class EInkBridgeStyleClassTest {

    @Test
    void fourGray_addsFourGrayClassOnly() {
        Pane root = new Pane();
        EInkBridge.applyDisplayStyleClass(root, PixelFormat.FOUR_GRAY);
        assertTrue(root.getStyleClass().contains(EInkBridge.STYLE_CLASS_FOUR_GRAY));
        assertFalse(root.getStyleClass().contains(EInkBridge.STYLE_CLASS_MONOCHROME));
    }

    @Test
    void monochrome_addsMonochromeClassOnly() {
        Pane root = new Pane();
        EInkBridge.applyDisplayStyleClass(root, PixelFormat.MONOCHROME);
        assertTrue(root.getStyleClass().contains(EInkBridge.STYLE_CLASS_MONOCHROME));
        assertFalse(root.getStyleClass().contains(EInkBridge.STYLE_CLASS_FOUR_GRAY));
    }

    @Test
    void reapplying_replacesRatherThanAccumulates() {
        Pane root = new Pane();
        root.getStyleClass().add("app-root");
        EInkBridge.applyDisplayStyleClass(root, PixelFormat.FOUR_GRAY);
        EInkBridge.applyDisplayStyleClass(root, PixelFormat.MONOCHROME);
        assertEquals(1, root.getStyleClass().stream()
                .filter(c -> c.startsWith("pux4j-")).count());
        assertTrue(root.getStyleClass().contains("app-root"));
    }
}
