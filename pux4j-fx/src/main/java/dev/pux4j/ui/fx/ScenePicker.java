// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Manual hit-testing for synthetic touch injection. JavaFX exposes no public pick API
 * ({@code Scene.pick} is internal) — this walks the scene graph depth-first from the root in
 * reverse child order (a later sibling paints on top of an earlier one, so it's picked
 * first), skipping invisible, mouse-transparent, and disabled nodes, and returns the first
 * node whose local bounds contain the point. See the "Touch Handling / Event injection"
 * section of {@code notes/design/javafx-bridge.md} in the parent repo.
 *
 * <p>Visibility, disable, and mouse-transparency checks all happen top-down, before
 * descending into children — matching real JavaFX pick semantics, where {@code
 * mouseTransparent} excludes a node <em>and its whole subtree</em>, not just the node itself
 * (checking it only after already having recursed into children, as an earlier version of
 * this class did, would let a mouse-transparent overlay's children still receive events —
 * exactly backwards from the click-through behaviour {@code setMouseTransparent(true)} is
 * for). Because all three checks happen before recursion, a node under a failing ancestor is
 * skipped without needing a separate "effective" query — the recursion never reaches it.
 *
 * <p>Stacking order follows {@code Parent.getOrderedChildren}: children are stable-sorted by
 * {@link Node#getViewOrder()} (higher values are further back) and picked from the front. A
 * node's {@link Node#getClip()} excludes the node <em>and its subtree</em> at points outside
 * the clip shape, as {@code Node.intersectsBounds} does (so scrolled-away {@code ScrollPane}
 * content is not pickable). Remaining difference: 3D picking (depth, cameras) is not modelled.
 */
final class ScenePicker {

    private ScenePicker() {}

    /**
     * Picks the topmost node at the given scene coordinates, or {@code null} if nothing
     * pickable is under the point (including when the scene has no root, or the root itself
     * is invisible/disabled/mouse-transparent).
     */
    static Node pick(Scene scene, double sceneX, double sceneY) {
        Parent root = scene.getRoot();
        return root == null ? null : pick(root, sceneX, sceneY);
    }

    /**
     * Package-private, not {@code private} — the {@code pick(Node, ...)} recursion doesn't
     * need a live {@code Scene} to construct or traverse a detached node tree (unlike {@link
     * #pick(Scene, double, double)}, or unlike anything under {@code javafx.scene.control},
     * both of which need the JavaFX toolkit started), so it can be tested directly against
     * plain {@code Group}/{@code Region}/{@code Rectangle} trees without needing a display
     * server — see {@code ScenePickerTest}.
     */
    static Node pick(Node node, double sceneX, double sceneY) {
        if (!node.isVisible() || node.isDisable() || node.isMouseTransparent()) {
            return null;
        }
        Point2D local = node.sceneToLocal(sceneX, sceneY);
        if (local == null) {
            return null;
        }
        Node clip = node.getClip();
        if (clip != null) {
            Point2D inClip = clip.parentToLocal(local);
            if (inClip == null || !clip.contains(inClip)) { // null: non-invertible clip transform
                return null;
            }
        }
        if (node instanceof Parent parent) {
            List<Node> children = frontToBack(parent.getChildrenUnmodifiable());
            for (Node child : children) {
                Node hit = pick(child, sceneX, sceneY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return node.contains(local) ? node : null;
    }

    /** Children in picking order: front first. Later siblings are in front of earlier ones,
     * and a lower view order is in front of a higher one. */
    private static List<Node> frontToBack(ObservableList<Node> children) {
        List<Node> ordered = new ArrayList<>(children);
        if (ordered.stream().anyMatch(n -> n.getViewOrder() != 0)) {
            ordered.sort(Comparator.comparingDouble(Node::getViewOrder).reversed()); // back to front, stable
        }
        Collections.reverse(ordered);
        return ordered;
    }
}
