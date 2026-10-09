// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.scene.Parent;

/**
 * Service interface through which an application hands its UI to a host that wires it to
 * an {@link EInkBridge}. An application module provides an implementation
 * ({@code provides SceneContent with ...}); it needs no knowledge of drivers, the bridge or
 * the display size — the host builds the {@code Scene} at the display's logical dimensions
 * and the returned root is laid out to fill it. Which drivers are in play is decided purely
 * by what the host is bundled with.
 */
public interface SceneContent {

    /** Short unique name, matched against {@code -Dpux4j.app=<name>} when several are present. */
    String name();

    /**
     * Builds the root node. Called on the JavaFX application thread. The root must resize to
     * its parent {@code Scene} (no fixed size) and must not paint a desktop-only bezel.
     */
    Parent createRoot() throws Exception;
}
