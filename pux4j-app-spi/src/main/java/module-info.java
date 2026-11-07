// SPDX-License-Identifier: Apache-2.0
/**
 * Service interface for non-JavaFX apps that the {@code pux4j} launcher can run next to JavaFX
 * apps. This module deliberately has no dependencies, so implementing it never pulls in JavaFX
 * or the driver library.
 */
module dev.pux4j.ui.app {
    exports dev.pux4j.ui.app;
}
