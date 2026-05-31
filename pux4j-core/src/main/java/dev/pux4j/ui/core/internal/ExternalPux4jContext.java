// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import com.pi4j.context.Context;
import dev.pux4j.ui.core.Pux4jContext;

/**
 * Pux4jContext that wraps a caller-supplied Pi4J context. {@link #close()} is a no-op
 * — the caller retains Pi4J lifecycle ownership.
 */
public final class ExternalPux4jContext implements Pux4jContext {

    private final Context pi4j;

    public ExternalPux4jContext(Context pi4j) {
        this.pi4j = pi4j;
    }

    @Override
    public Context pi4j() {
        return pi4j;
    }

    @Override
    public void close() {
        // No-op: caller owns the Pi4J context and is responsible for its lifecycle.
    }
}
