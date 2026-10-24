// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;

import java.util.List;

/**
 * Gives disabled controls a look that survives a monochrome panel. Modena dims a disabled
 * control with {@code -fx-opacity: 0.4} (and apps often add their own dimming), which
 * thresholds away to nothing on a 1-bit display, so the bridge instead inverts the colours of
 * each disabled {@link Control}'s whole layout rectangle in the captured frame, before it is
 * handed to the transform.
 *
 * <p><b>App requirement:</b> reset the dimming for disabled controls
 * ({@code .my-control:disabled { -fx-opacity: 1; }}). A dimmed control is near-white; inverted,
 * it becomes a near-black block whose text thresholds away.
 *
 * <p>The rectangles are collected from the scene graph (the capture is in logical, i.e.
 * unrotated, scene coordinates, so no orientation mapping is needed) and the inversion is done
 * on the BGRA capture buffer. Both steps are separate so the pixel step is testable without a
 * toolkit.
 *
 * <p>Known limitations: the rectangle is the control's axis-aligned layout bounds, so a rotated
 * control inverts its bounding box, and anything painted over a disabled control (a later
 * sibling, an overlay) is inverted with it; effects such as drop shadows are not included.
 * Translucent controls are inverted like opaque ones (opacity 0 is skipped), and the content of a
 * {@code SubScene} is not searched. Overlapping rectangles are inverted once, not twice.
 */
final class DisabledInverter {

    private DisabledInverter() {}

    /** A pixel rectangle, {@code x0}/{@code y0} inclusive and {@code x1}/{@code y1} exclusive. */
    record Rect(int x0, int y0, int x1, int y1) {}

    /**
     * Appends the scene-pixel rectangle of every visible, disabled {@link Control} under
     * {@code root} to {@code out}, clipped to a {@code width} x {@code height} frame and to the
     * {@link Node#getClip() clips} of its ancestors (so a control scrolled out of a
     * {@code ScrollPane} is not marked). A control's subtree is not searched further (its
     * rectangle already covers it); invisible and fully transparent subtrees are skipped. Must
     * run on the FX thread.
     */
    static void collect(Node root, int width, int height, List<Rect> out) {
        collect(root, new double[] {0, 0, width, height}, out);
    }

    /** {@code clip} is {minX, minY, maxX, maxY} in scene coordinates. */
    private static void collect(Node node, double[] clip, List<Rect> out) {
        if (!node.isVisible() || node.getOpacity() == 0) {
            return;
        }
        if (node.getClip() != null) {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            clip = intersect(clip, b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY());
            if (clip == null) {
                return;
            }
        }
        if (node instanceof Control && node.isDisabled()) {
            Bounds b = node.localToScene(node.getLayoutBounds());
            double[] visible = intersect(clip, b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY());
            if (visible != null) {
                Rect r = snap(visible[0], visible[1], visible[2], visible[3]);
                if (r != null) {
                    out.add(r);
                }
            }
            return;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, clip, out);
            }
        }
    }

    private static double[] intersect(double[] a, double minX, double minY, double maxX, double maxY) {
        if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(maxX) || Double.isNaN(maxY)) {
            return null;
        }
        double x0 = Math.max(a[0], minX);
        double y0 = Math.max(a[1], minY);
        double x1 = Math.min(a[2], maxX);
        double y1 = Math.min(a[3], maxY);
        return x0 < x1 && y0 < y1 ? new double[] {x0, y0, x1, y1} : null;
    }

    private static Rect snap(double minX, double minY, double maxX, double maxY) {
        return new Rect((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY));
    }

    /** Snaps the bounds outward to whole pixels and clips them; {@code null} if nothing is left. */
    static Rect clip(double minX, double minY, double maxX, double maxY, int width, int height) {
        double[] r = intersect(new double[] {0, 0, width, height}, minX, minY, maxX, maxY);
        return r == null ? null : snap(r[0], r[1], r[2], r[3]);
    }

    /**
     * Inverts B, G and R (not alpha) of each rectangle in a tightly packed BGRA buffer. A pixel
     * covered by several rectangles is inverted once.
     */
    static void invert(byte[] bgra, int width, List<Rect> rects) {
        if (rects.isEmpty()) {
            return;
        }
        boolean[] done = new boolean[bgra.length / 4];
        for (Rect r : rects) {
            for (int y = r.y0(); y < r.y1(); y++) {
                for (int x = r.x0(); x < r.x1(); x++) {
                    int p = y * width + x;
                    if (!done[p]) {
                        done[p] = true;
                        int i = p * 4;
                        bgra[i] = (byte) ~bgra[i];
                        bgra[i + 1] = (byte) ~bgra[i + 1];
                        bgra[i + 2] = (byte) ~bgra[i + 2];
                    }
                }
            }
        }
    }
}
