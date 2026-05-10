// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.RefreshMode;
import java.util.Optional;

/**
 * The refresh action the caller should apply after writing a frame.
 * {@code region} is empty for full-frame writes (FULL or FAST mode).
 */
public record RefreshDecision(RefreshMode mode, Optional<Region> region) {}
