// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import javafx.scene.Parent;

import java.util.Map;

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

    /**
     * Optional tuning hints for this application, as {@code pux4j.*} system properties (for
     * example {@code pux4j.refresh.fullRefreshThreshold}). A host applies each entry only where
     * the property has not already been set, so an explicit {@code -D} option always wins. The
     * hints are an optimisation only: an application must work if they are ignored or the
     * property names are not recognised. The default is no hints.
     */
    default Map<String, String> defaultProperties() {
        return Map.of();
    }
}
