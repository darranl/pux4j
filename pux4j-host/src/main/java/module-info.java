// SPDX-License-Identifier: Apache-2.0
/**
 * Generic eInk host — see {@link dev.pux4j.ui.host.BridgeHost}. Not an
 * {@code Application} subclass (so nothing needs exporting to {@code javafx.graphics}); it
 * drives {@code Platform.startup(...)} directly, like the bridge smoke test.
 */
module dev.pux4j.ui.host {
    requires dev.pux4j.ui.fx;
    requires javafx.graphics;
    requires org.slf4j;

    uses dev.pux4j.ui.fx.SceneContent;
}
