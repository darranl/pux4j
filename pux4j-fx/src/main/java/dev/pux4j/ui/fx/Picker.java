// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.scene.Node;

/** Finds the node under a scene-coordinate point, or {@code null} if there is none. */
@FunctionalInterface
interface Picker {
    Node pick(double x, double y);
}
