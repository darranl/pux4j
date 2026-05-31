// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import dev.pux4j.ui.core.internal.ExternalPux4jContext;
import dev.pux4j.ui.core.internal.ManagedPux4jContext;

import java.util.Objects;

/**
 * Runtime context that provides access to the Pi4J hardware context and owns its lifecycle.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li><strong>Managed</strong> — pux4j acquires a Pi4J auto-context internally; {@link #close()}
 *       shuts down Pi4J. Use for standalone applications that do not manage Pi4J directly.</li>
 *   <li><strong>External</strong> — caller supplies an existing Pi4J context; {@link #close()} is a
 *       no-op for Pi4J, leaving lifecycle responsibility with the caller. Use when Pi4J is shared
 *       across multiple subsystems.</li>
 * </ul>
 *
 * <p>Both modes implement {@link AutoCloseable} for try-with-resources:
 * <pre>{@code
 * try (Pux4jContext ctx = Pux4jContext.managed()) {
 *     EInkDisplayDriver driver = factory.create(ctx, config);
 *     driver.initialize();
 *     driver.writeFrame(new MonochromeFrame(pixels, RefreshMode.FULL)).get();
 * }
 * }</pre>
 */
public interface Pux4jContext extends AutoCloseable {

    /**
     * Returns the underlying Pi4J context.
     *
     * @return the Pi4J context; never null
     */
    Context pi4j();

    /**
     * Releases resources owned by this context.
     *
     * <p>In managed mode, shuts down the Pi4J context.
     * In external mode, this is a no-op — the caller retains ownership of the Pi4J context.
     */
    @Override
    void close();

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a managed context that acquires and owns a Pi4J auto-context.
     * {@link #close()} will shut down Pi4J.
     *
     * @return a new managed {@link Pux4jContext}
     */
    static Pux4jContext managed() {
        return new ManagedPux4jContext(Pi4J.newAutoContext());
    }

    /**
     * Creates an external context wrapping a caller-supplied Pi4J context.
     * {@link #close()} is a no-op — the caller retains ownership of the Pi4J context.
     *
     * @param pi4j an already-initialised Pi4J context; must not be null
     * @return a new external {@link Pux4jContext}
     * @throws NullPointerException if {@code pi4j} is null
     */
    static Pux4jContext external(Context pi4j) {
        return new ExternalPux4jContext(Objects.requireNonNull(pi4j, "pi4j"));
    }
}
