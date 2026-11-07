// SPDX-License-Identifier: Apache-2.0
/**
 * Generic eInk launcher — see {@link dev.pux4j.ui.launcher.EInkLauncher}. It runs two kinds of
 * app found on the module path under one name space: JavaFX apps ({@code SceneContent}, run on
 * an {@code EInkBridge}) and non-JavaFX driver apps ({@code DriverApp}, run directly). Not an
 * {@code Application} subclass (so nothing needs exporting to {@code javafx.graphics}); the
 * JavaFX path drives {@code Platform.startup(...)} directly, like the bridge smoke test.
 */
module dev.pux4j.ui.launcher {
    requires dev.pux4j.ui.app;
    requires dev.pux4j.ui.core;
    requires dev.pux4j.ui.fx;
    requires javafx.graphics;
    requires org.slf4j;

    uses dev.pux4j.ui.app.DriverApp;
    uses dev.pux4j.ui.fx.SceneContent;
    // Only their names are read, for the --list footer.
    uses dev.pux4j.ui.core.DisplayDriverFactory;
    uses dev.pux4j.ui.core.TouchDriverFactory;
}
