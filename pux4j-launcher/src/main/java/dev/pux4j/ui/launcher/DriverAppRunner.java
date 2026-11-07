// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import dev.pux4j.ui.app.DriverApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a {@link DriverApp} on the calling thread. Deliberately free of any JavaFX reference:
 * a driver app must never cause the toolkit to start or JavaFX classes to load.
 */
final class DriverAppRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DriverAppRunner.class);

    private DriverAppRunner() {
    }

    /** Returns the app's exit code, or {@code 1} if it threw. */
    static int run(DriverApp app, String[] args) {
        LOG.info("Running driver app '{}'", app.name());
        try {
            return app.run(args);
        } catch (Throwable t) {
            LOG.error("Driver app '{}' failed", app.name(), t);
            return 1;
        }
    }
}
