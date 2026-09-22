// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers {@link ScenePicker}'s node-level traversal ({@code pick(Node, double, double)},
 * package-private specifically for this) against a detached scene-graph tree — no {@code
 * Scene} and no JavaFX toolkit startup needed (verified empirically: {@code Group}/{@code
 * Rectangle} construct and support {@code sceneToLocal}/{@code contains} without {@code
 * Platform.startup()}; only {@code Scene} construction itself, and anything under {@code
 * javafx.scene.control}, need the toolkit — see {@code EInkBridgeDisplayThreadTest}'s class
 * javadoc for the fuller story). This is deliberately the one piece of touch injection that
 * Phase 6.4 part B tests directly, since it's plain scene-graph traversal with no thread/event
 * involvement, and it's exactly where a real bug (mouse-transparency checked after already
 * recursing into children, letting a click-through overlay's children still swallow events)
 * was found and fixed during this phase's review.
 */
class ScenePickerTest {

    private static Rectangle rect(double x, double y, double w, double h) {
        return new Rectangle(x, y, w, h);
    }

    @Test
    void pick_hitsRectangle() {
        Rectangle r = rect(10, 10, 50, 20);
        Group root = new Group(r);

        assertSame(r, ScenePicker.pick(root, 20, 15));
    }

    @Test
    void pick_missesOutsideAnyChild() {
        Rectangle r = rect(10, 10, 50, 20);
        Group root = new Group(r);

        assertNull(ScenePicker.pick(root, 5, 5));
    }

    /**
     * Later siblings paint on top in JavaFX — the picker must prefer them too. Two overlapping
     * rectangles at the same point; the second (topmost) one must win, not the first.
     */
    @Test
    void pick_prefersLaterSiblingOverEarlierOne() {
        Rectangle under = rect(0, 0, 100, 100);
        Rectangle over = rect(0, 0, 100, 100);
        Group root = new Group(under, over);

        assertSame(over, ScenePicker.pick(root, 50, 50));
    }

    /**
     * An invisible ancestor must remove its whole subtree from picking — not just itself —
     * without needing an "effective visibility" query, since the recursion never descends
     * into it in the first place.
     */
    @Test
    void pick_skipsSubtreeOfInvisibleAncestor() {
        Rectangle child = rect(0, 0, 100, 100);
        Group invisibleParent = new Group(child);
        invisibleParent.setVisible(false);
        Group root = new Group(invisibleParent);

        assertNull(ScenePicker.pick(root, 50, 50));
    }

    /**
     * Unlike invisibility, a disabled node's geometry still contributes to its parent's
     * {@code boundsInLocal} (verified empirically: an invisible child collapses its Group
     * parent's bounds to empty, a disabled one does not — disable affects interaction, not
     * layout/rendering). So the enclosing {@code root} here is still legitimately pickable at
     * this point via its own bounds-based {@code contains()} fallback, same as it would be for
     * a click on empty background next to a disabled button — that's correct, not a picker
     * bug. What must actually hold is narrower: nothing <em>from inside</em> the disabled
     * subtree is ever returned.
     */
    @Test
    void pick_skipsSubtreeOfDisabledAncestor_neverReturnsANodeFromInsideIt() {
        Rectangle child = rect(0, 0, 100, 100);
        Group disabledParent = new Group(child);
        disabledParent.setDisable(true);
        Group root = new Group(disabledParent);

        Node picked = ScenePicker.pick(root, 50, 50);

        assertNotSame(child, picked);
        assertNotSame(disabledParent, picked);
    }

    /**
     * Regression test for the bug this phase's review found: {@code setMouseTransparent(true)}
     * on a {@code Parent} is the standard JavaFX idiom for "click straight through this and
     * everything in it" (real {@code Node} pick semantics: mouse-transparency excludes the
     * whole subtree, not just the node). An earlier version of {@link ScenePicker} checked
     * {@code isMouseTransparent()} only after already recursing into children, so a
     * transparent overlay's child was still picked — exactly backwards from click-through.
     * This asserts the node <em>underneath</em> the transparent overlay is what gets picked.
     */
    @Test
    void pick_mouseTransparentSubtreeIsSkippedEntirely_hitsNodeUnderneathInstead() {
        Rectangle under = rect(0, 0, 100, 100);
        Rectangle overlayChild = rect(0, 0, 100, 100);
        Group overlay = new Group(overlayChild);
        overlay.setMouseTransparent(true);
        Group root = new Group(under, overlay);

        assertSame(under, ScenePicker.pick(root, 50, 50));
    }

    /**
     * A node whose local-to-scene transform is non-invertible (here, scaled to zero) makes
     * {@code sceneToLocal} return {@code null} — the picker must treat that as a miss, not
     * NPE.
     */
    @Test
    void pick_nonInvertibleTransform_treatedAsMiss_doesNotThrow() {
        Rectangle r = rect(0, 0, 100, 100);
        r.setScaleX(0);
        r.setScaleY(0);
        Group root = new Group(r);

        assertNull(ScenePicker.pick(root, 50, 50));
    }

    @Test
    void pick_emptyGroup_isAMiss() {
        Group root = new Group();

        assertNull(ScenePicker.pick(root, 50, 50));
    }

    /**
     * Known, documented divergence from real JavaFX picking (see {@link ScenePicker}'s class
     * javadoc): {@code Node.getClip()} is ignored. This test exists to make that a conscious,
     * visible fact rather than a silent gap — if a future change makes clipping honoured, this
     * assertion should flip to {@code assertNull}, not be deleted.
     */
    @Test
    void pick_ignoresClip_childOutsideClippedRegionIsStillPickable() {
        Rectangle child = rect(0, 0, 200, 200);
        Group clipped = new Group(child);
        clipped.setClip(new Rectangle(0, 0, 50, 50));
        Group root = new Group(clipped);

        assertSame(child, ScenePicker.pick(root, 150, 150));
    }
}
