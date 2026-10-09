// SPDX-License-Identifier: Apache-2.0
/**
 * Phase 6.4 part D headless smoke test — see {@link dev.pux4j.ui.bridgesmoke.BridgeSmokeTest}.
 * Deliberately a separate module from {@code pux4j-validation} (its hardware dist profiles
 * exclude JavaFX from {@code lib/}) and from {@code pux4j-demo} (6.5's scope). No {@code exports}:
 * this is a runnable application launched by its own main class, not a library, and it never
 * extends {@code javafx.application.Application} (which would need an export to
 * {@code javafx.graphics} for reflective construction) — it drives {@code Platform.startup(...)}
 * directly instead, since constructing a driver on the FX thread can deadlock (see
 * {@link dev.pux4j.ui.fx.EInkBridge}'s class javadoc).
 */
module dev.pux4j.ui.bridgesmoke {
    requires dev.pux4j.ui.core;
    requires dev.pux4j.ui.transform;
    requires dev.pux4j.ui.fx;
    requires javafx.graphics;
    requires org.slf4j;
    requires java.desktop; // ImageIO, for decoding the smoke test's own PNG output back out
}
