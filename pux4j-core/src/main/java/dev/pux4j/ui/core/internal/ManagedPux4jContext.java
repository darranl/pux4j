// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import com.pi4j.context.Context;
import dev.pux4j.ui.core.Pux4jContext;

/**
 * Pux4jContext that owns and shuts down the Pi4J context on close.
 */
public final class ManagedPux4jContext implements Pux4jContext {

    private final Context pi4j;

    public ManagedPux4jContext(Context pi4j) {
        this.pi4j = pi4j;
    }

    @Override
    public Context pi4j() {
        return pi4j;
    }

    @Override
    public void close() {
        pi4j.shutdown();
    }
}
