// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import java.util.List;

/**
 * Lets a caller inject synthetic touch events into the {@code "programmatic"}
 * {@link TouchDriverFactory} driver, for headless dev-loop and CI use where there is no
 * physical touch IC to read from. Obtain an instance by selecting the driver
 * ({@code TouchDriverFactory.select("programmatic").create(...)}) and casting the resulting
 * {@link TouchDriver} to this interface.
 *
 * <p>Only this narrow queuing contract is public API; the concrete driver implementation is
 * an internal detail (see {@code notes/project-plan.md} Phase 6.3 — minimum public API).
 */
public interface ProgrammaticTouchSource {

    /** Enqueues a single touch contact as one read batch. */
    void queueTouch(TouchPoint contact);

    /** Enqueues a multi-contact event as one read batch. */
    void queueTouches(List<TouchPoint> contacts);
}
